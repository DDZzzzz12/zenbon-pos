/**
 * Implementation of ZBonRepository for Z-report data access operations.
 *
 * Provides concrete implementation of daily closing report persistence operations
 * using Exposed ORM. Follows the established repository pattern for data access.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.data.repository

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.features.zBon.domain.StammdatenSnapshot
import org.example.project.features.zBon.domain.ZBonPeriodOverlapException
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.repository.ZBonRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal
import java.util.UUID

/**
 * Exposed ORM implementation of ZBonRepository.
 *
 * Handles all database operations for Z-reports using Exposed ORM.
 */
class ZBonRepositoryImpl : ZBonRepository {
    override fun getAllReports(): List<ZBon> = ZBon.all().toList()

    override fun getReportById(id: Long): ZBon? = ZBon.findById(id)

    override fun getReportsByShop(shopInfoId: Long): List<ZBon> {
        ShopInfo.findById(shopInfoId)
            ?: throw IllegalArgumentException("Shop not found: $shopInfoId")

        return ZBon.find { ZBonTable.shopInfoId eq shopInfoId }.toList()
    }

    override fun getReportsByEmployee(employeeId: UUID): List<ZBon> {
        Employee.findById(employeeId)
            ?: throw IllegalArgumentException("Employee not found: $employeeId")

        return ZBon.find { ZBonTable.employeeId eq employeeId }.toList()
    }

    override fun getReportsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<ZBon> =
        ZBon
            .find {
                (ZBonTable.endTime greaterEq startDate) and (ZBonTable.endTime lessEq endDate)
            }.orderBy(ZBonTable.startTime to SortOrder.DESC)
            .toList()

    override fun getLatestReportByShop(): ZBon? =
        ZBon
            .all()
            .orderBy(ZBonTable.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()

    override fun createReport(
        employee: Employee,
        shopInfo: ShopInfo,
    ): ZBon {
        // Validate references exist
        val lastZbon = ZBon.all().orderBy(ZBonTable.id to SortOrder.DESC).firstOrNull()

        val time = lastZbon?.endTime

        val startTime =
            if (time != null) {
                time
            } else {
                // First-ever closing: the period starts at the beginning of the day the shop first
                // did business. Sessions are the usual source, but they are no longer the ONLY one.
                //
                // Round-7 C9 follow-up. `BillTable.tableSession` is a non-nullable reference, so while
                // "open business" meant "a bill exists" this fell back to a session that was
                // guaranteed to exist and the throw below was structurally unreachable from
                // createReport. Widening OpenBusinessCheck to unlocked Kassenbuch entries severed that
                // implication — Kassenbuch has no TableSession coupling whatsoever — so a shop whose
                // only business is a booked Privateinlage reached the throw. That left the operator
                // wedged: the entry blocks a fiscal Stammdaten edit with a 409 telling them to take a
                // Kassenabschluss, and the Kassenabschluss answered 500.
                //
                // A Kassenbuch-only shop is a legitimate closing scenario now, so express it: fall
                // back to the earliest unlocked entry, the same rows the closing is about to sweep.
                //
                // Deliberately a STRICT fallback, not min(session, entry): when a session exists this
                // must resolve exactly as before. Taking the minimum would move the period start of a
                // first closing on any shop that happens to hold an older unlocked entry, changing
                // fiscal output on a path that has no defect.
                val periodStart =
                    TableSession
                        .all()
                        .orderBy(TableSessionTable.id to SortOrder.ASC)
                        .firstOrNull()
                        ?.startTime
                        ?: KassenbuchHeader
                            .find { KassenbuchHeaderTable.isLocked eq false }
                            .orderBy(KassenbuchHeaderTable.createdAt to SortOrder.ASC)
                            .firstOrNull()
                            ?.createdAt
                        // Defensive: ZBonServiceImpl's OpenBusinessCheck fail-fast makes this unreachable
                        // from that path (open business now implies a session OR an unlocked entry). Kept
                        // as a PosException regardless — a bare Exception on a fiscal endpoint is a 500
                        // with nothing the operator can act on, which is how the defect above stayed
                        // invisible.
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Cannot determine the closing period: the shop has no session and no open " +
                                "Kassenbuch entry to start it from",
                        )
                periodStart.date.atTime(0, 0, 0)
            }

        // Check for overlapping periods
        val existingReport =
            ZBon
                .find {
                    (ZBonTable.shopInfoId eq shopInfo.id) and
                        (ZBonTable.endTime greater startTime)
                }.firstOrNull()

        if (existingReport != null) {
            // Translated into a 409 ZBON_PERIOD_OVERLAP by ZBonServiceImpl.createReport (NEW-25).
            throw ZBonPeriodOverlapException(
                "Z-report already exists for overlapping period: Z-Bon ${existingReport.zbonNummer} ends at " +
                    "${existingReport.endTime}, after the new period start $startTime",
            )
        }

        // Create the report with all required fields
        // Note: Financial fields will be updated later by the service after processing bills
        val zBon =
            ZBon.new {
                this.shopInfo = shopInfo
                this.employee = employee
                this.startTime = startTime
                this.endTime = currentTimeStamp() // Will be updated by service
                this.cancelledAmount = BigDecimal.ZERO // Will be calculated by service
                this.totalPosRevenue = BigDecimal.ZERO // Will be calculated by service
                this.openingBalance = java.math.BigDecimal.ZERO // Will be set by service
                this.expenses = java.math.BigDecimal.ZERO // Will be set by service
                this.cashRevenue = java.math.BigDecimal.ZERO // Will be calculated by service
                this.closingBalance = java.math.BigDecimal.ZERO // Will be calculated by service
                this.createdAt = currentTimeStamp()
                // Compute gapless sequential number per shop
                val maxNummer =
                    ZBonTable
                        .select(ZBonTable.zbonNummer.max())
                        .where { ZBonTable.shopInfoId eq shopInfo.id }
                        .firstOrNull()
                        ?.get(ZBonTable.zbonNummer.max()) ?: 0L
                this.zbonNummer = maxNummer + 1

                // Per-closing Stammdaten snapshot — DSFinV-K Tz. 3.2 (p.23), audit KABS-2. The
                // Kassenabschluss must carry the master data that applied AT CLOSING TIME; without
                // this the export re-reads the live shop_info row and a later address/tax-id edit
                // rewrites the bytes of an already-delivered closing.
                //
                // Written per insert, deliberately NOT as a column default: `Column.default(value)`
                // evaluates once at table-object init (audit KABS-1) and would freeze one shop's data
                // across every future closing.
                //
                // The field list lives in StammdatenSnapshot, shared with the freeze-on-edit path in
                // ShopInfoServiceImpl.updateShopInfo (round-6 C5), so the two cannot drift apart.
                StammdatenSnapshot.applyTo(this, shopInfo)
                // tseTransactionId is nullable, will be set if TSE signing is performed
            }

        // Round-7 C3: the CASH REGISTER Stammdaten (cashregister.csv) and the terminals
        // (slaves.csv) are per-closing for the same Tz. 3.2 reason, but come from the
        // KASSEN_MELDUNG_CONFIG setting and the `terminals` table rather than from shop_info —
        // and KASSE_SW_VERSION is rewritten on every server start. Applied after the insert so
        // the child terminal rows have a persisted parent id to reference.
        KassenStammdatenSnapshot.applyTo(zBon, shopInfo.id.value)

        return zBon
    }

    override fun existsForPeriod(
        shopInfoId: Long,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
    ): Boolean =
        ZBon
            .find {
                (ZBonTable.shopInfoId eq shopInfoId) and
                    (
                        ((ZBonTable.startTime lessEq startTime) and (ZBonTable.endTime greater startTime)) or
                            ((ZBonTable.startTime less endTime) and (ZBonTable.endTime greaterEq endTime)) or
                            ((ZBonTable.startTime greaterEq startTime) and (ZBonTable.endTime lessEq endTime))
                    )
            }.count() > 0

    override fun findClosedInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<ZBon> =
        ZBon
            .find {
                (ZBonTable.endTime greaterEq start) and (ZBonTable.endTime less end)
            }.orderBy(ZBonTable.startTime to SortOrder.ASC)
            .toList()

    override fun findLastClosedBefore(before: LocalDateTime): ZBon? =
        ZBon
            .find {
                ZBonTable.endTime less before
            }.orderBy(ZBonTable.endTime to SortOrder.DESC)
            .limit(1)
            .firstOrNull()

    override fun getRecentReports(limit: Int): List<ZBon> =
        ZBon
            .all()
            .orderBy(ZBonTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .toList()

    override fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto> {
        // Parse dates to LocalDateTime for querying
        val start = LocalDate.parse(startDate).atTime(0, 0, 0)
        val end = LocalDate.parse(endDate).atTime(23, 59, 59)

        // Query Z-Bons overlapping the date range and map to DTOs. A Z-Bon spans from the previous
        // close until its own, so its startTime usually precedes the requested window — requiring
        // full containment would hide the Kassenabschluss from the period it was actually closed in.
        val zbons =
            ZBon
                .find {
                    (ZBonTable.startTime lessEq end) and (ZBonTable.endTime greaterEq start)
                }.orderBy(ZBonTable.startTime to SortOrder.DESC)

        return zbons.map { zbon ->
            ZBonDto.ZBonSummeryDto(
                id = zbon.id.value,
                zbonNummer = zbon.zbonNummer,
                startTime = zbon.startTime.toString(),
                endTime = zbon.endTime.toString(),
                totalRevenue = zbon.totalPosRevenue.toDecimalString(),
            )
        }
    }
}
