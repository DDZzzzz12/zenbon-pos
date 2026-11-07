/**
 * Exposed table definition for Pfand (deposit) types.
 *
 * Each Pfand type carries a deposit value + VAT class and owns two system-managed
 * backing products (a deposit charge + a deposit return). Drinks link to a Pfand
 * type so the client can auto-attach the deposit line at order time.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.pfand.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/** Catalog of deposit (Pfand) types — each owns a deposit + a return backing product. */
object PfandTypeTable : LongIdTable("pfand_type", "id") {
    /** Display name (e.g. "Einweg 0,25"). */
    val name = varchar("name", 60)

    /** Deposit amount charged/refunded per unit. */
    val depositValue = decimal("deposit_value", precision = 12, scale = 4)

    /** VAT class key (A=19%, B=7%, C=0%) — same class system VatMappingUtil uses. */
    val vatClass = varchar("vat_class", 2).default("A")

    /** Whether this type (and its backing products) is active. */
    val isActive = bool("is_active").default(true)

    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
}
