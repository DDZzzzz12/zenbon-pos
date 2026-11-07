package org.example.project.features.employee.data.repository

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Exposed-backed [PermissionGroupRepository].
 *
 * Every method assumes an ambient transaction, per the repository contract — the service owns the
 * transaction boundary so a guardrail rejection rolls the whole edit back rather than leaving a
 * group half-changed.
 */
class PermissionGroupRepositoryImpl : PermissionGroupRepository {
    private val logger = LoggerFactory.getLogger(PermissionGroupRepositoryImpl::class.java)

    override fun listGroups(): List<PermissionGroupRepository.GroupRecord> = PermissionGroupTable.selectAll().map { row -> row.toRecord() }

    override fun findGroup(code: String): PermissionGroupRepository.GroupRecord? =
        PermissionGroupTable
            .selectAll()
            .where { PermissionGroupTable.code eq code }
            .firstOrNull()
            ?.toRecord()

    override fun createGroup(
        code: String,
        name: String,
        description: String?,
        capabilities: Set<Capability>,
    ): Boolean {
        if (findGroup(code) != null) return false
        val now = currentTimeStamp()
        val id =
            PermissionGroupTable.insertAndGetId {
                it[PermissionGroupTable.code] = code
                it[PermissionGroupTable.name] = name
                it[PermissionGroupTable.description] = description
                // Operator-created, so never a system group: its capability set stays editable and
                // it can be deleted once nobody points at it.
                it[isSystem] = false
                it[isDeletable] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
        capabilities.forEach { cap ->
            PermissionGroupCapabilityTable.insert {
                it[group] = id
                it[capability] = cap.name
            }
        }
        return true
    }

    override fun updateGroupMetadata(
        code: String,
        name: String?,
        description: String?,
    ): Boolean {
        val id = idOf(code) ?: return false
        PermissionGroupTable.update({ PermissionGroupTable.id eq id }) {
            name?.let { value -> it[PermissionGroupTable.name] = value }
            description?.let { value -> it[PermissionGroupTable.description] = value }
            it[updatedAt] = currentTimeStamp()
        }
        return true
    }

    override fun replaceCapabilities(
        code: String,
        capabilities: Set<Capability>,
    ): Boolean {
        val id = idOf(code) ?: return false
        // Remove first, then insert — the same ordering SchemaManager's reconciling seed uses, so a
        // capability moved between groups is never momentarily held by both.
        PermissionGroupCapabilityTable.deleteWhere { PermissionGroupCapabilityTable.group eq id }
        capabilities.forEach { cap ->
            PermissionGroupCapabilityTable.insert {
                it[group] = id
                it[capability] = cap.name
            }
        }
        PermissionGroupTable.update({ PermissionGroupTable.id eq id }) { it[updatedAt] = currentTimeStamp() }
        return true
    }

    override fun deleteGroup(code: String): Boolean {
        val id = idOf(code) ?: return false
        PermissionGroupCapabilityTable.deleteWhere { PermissionGroupCapabilityTable.group eq id }
        PermissionGroupTable.deleteWhere { PermissionGroupTable.id eq id }
        return true
    }

    override fun memberCount(code: String): Int {
        val id = idOf(code) ?: return 0
        return EmployeePermissionGroupTable
            .selectAll()
            .where { EmployeePermissionGroupTable.group eq id }
            .count()
            .toInt()
    }

    override fun groupCodesOf(employeeIds: Collection<UUID>): Map<UUID, String> {
        if (employeeIds.isEmpty()) return emptyMap()
        val wanted = employeeIds.toSet()

        // Two reads, not one per employee: the group table is tiny (seven seeded rows plus whatever
        // an operator added), so resolving id -> code from a map beats a join per row.
        val codeById =
            PermissionGroupTable
                .selectAll()
                .associate { it[PermissionGroupTable.id] to it[PermissionGroupTable.code] }

        return EmployeePermissionGroupTable
            .selectAll()
            .mapNotNull { row ->
                val employeeId = row[EmployeePermissionGroupTable.employee].value
                if (employeeId !in wanted) return@mapNotNull null
                codeById[row[EmployeePermissionGroupTable.group]]?.let { employeeId to it }
            }.toMap()
    }

    override fun activeHolderCount(capability: Capability): Int {
        // Groups conferring it, then ACTIVE employees pointing at one of those groups. Employment
        // status matters: a deactivated admin cannot log in to undo a lockout, so counting them
        // would make the guardrail pass on a shop that has in fact locked itself out.
        val groupIds =
            PermissionGroupCapabilityTable
                .selectAll()
                .where { PermissionGroupCapabilityTable.capability eq capability.name }
                .map { it[PermissionGroupCapabilityTable.group] }
                .toSet()
        if (groupIds.isEmpty()) return 0

        return EmployeePermissionGroupTable
            .selectAll()
            .filter { it[EmployeePermissionGroupTable.group] in groupIds }
            .count { row ->
                EmployeeTable
                    .selectAll()
                    .where { EmployeeTable.id eq row[EmployeePermissionGroupTable.employee] }
                    .firstOrNull()
                    ?.get(EmployeeTable.employmentStatus) == EmploymentStatus.ACTIVE
            }
    }

    private fun idOf(code: String): EntityID<Int>? =
        PermissionGroupTable
            .selectAll()
            .where { PermissionGroupTable.code eq code }
            .firstOrNull()
            ?.get(PermissionGroupTable.id)

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord(): PermissionGroupRepository.GroupRecord {
        val id = this[PermissionGroupTable.id]
        val capabilities =
            PermissionGroupCapabilityTable
                .selectAll()
                .where { PermissionGroupCapabilityTable.group eq id }
                .mapNotNull { row ->
                    val name = row[PermissionGroupCapabilityTable.capability]
                    Capability.entries.firstOrNull { it.name == name }
                        ?: null.also {
                            // No FK to the enum by design, so an unknown value means the database is
                            // ahead of this build. Report the group without it rather than failing.
                            logger.error("Unknown capability '{}' in permission_group_capability", name)
                        }
                }.toSet()

        return PermissionGroupRepository.GroupRecord(
            code = this[PermissionGroupTable.code],
            name = this[PermissionGroupTable.name],
            description = this[PermissionGroupTable.description],
            isSystem = this[PermissionGroupTable.isSystem],
            isDeletable = this[PermissionGroupTable.isDeletable],
            capabilities = capabilities,
            memberCount =
                EmployeePermissionGroupTable
                    .selectAll()
                    .where { EmployeePermissionGroupTable.group eq id }
                    .count()
                    .toInt(),
        )
    }
}
