package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cardinality is read out of the schema, never asserted by a human.
 *
 * That is the whole safety argument for deriving relations: a to-many path joined like a to-one
 * multiplies rows into a file whose recipient cannot tell it is wrong. A rule that reads the unique
 * indexes cannot be wrong the way a hand-written `cardinality = ONE` can.
 */
@DisplayName("Schema graph cardinality")
class SchemaGraphCardinalityTest {
    /** `supplier.partner_id` references one partner row, so following it forwards is to-one. */
    @Test
    fun `an outgoing foreign key is to-one`() {
        assertEquals(Cardinality.ONE, SchemaGraph.cardinalityOfOutgoing(SupplierTable.partnerId))
    }

    /** `supplier` carries UNIQUE(partner_id), so a partner has at most one creditor role. */
    @Test
    fun `an incoming foreign key with a unique child column is to-one`() {
        assertEquals(Cardinality.ONE, SchemaGraph.cardinalityOfIncoming(SupplierTable.partnerId))
    }

    /**
     * `debtor` carries UNIQUE(partner_id) too, so a partner has at most one debtor role —
     * the AR mirror of the creditor case above.
     *
     * This expectation used to be MANY. `debtor.partner_id` carried a plain index, which was an
     * omission and not a decision, and it made the graph derive the relation as to-many so the
     * Debitorennummer could never be offered on a partner export. The migration and `DebtorTable`'s
     * `uniqueIndex(partnerId)` landed together and this flipped with them, deliberately, in the
     * commit that changed the schema.
     */
    @Test
    fun `an incoming foreign key with a unique child column is to-one on the debtor side too`() {
        assertEquals(Cardinality.ONE, SchemaGraph.cardinalityOfIncoming(DebtorTable.partnerId))
    }

    /**
     * The MANY case still has a real example, and needs one: a rule that only ever answers ONE would
     * pass with `isUnique` hard-wired to true. `supplier_bank_account.partner_id` carries a plain
     * index plus a *composite* unique index, which is not a uniqueness guarantee on the column.
     */
    @Test
    fun `an incoming foreign key without a unique child column is to-many`() {
        assertEquals(Cardinality.MANY, SchemaGraph.cardinalityOfIncoming(SupplierBankAccountTable.partnerId))
    }

    /** A single-column primary key is unique even with no index declared for it. */
    @Test
    fun `a primary key column counts as unique`() {
        assertTrue(SchemaGraph.isUnique(BusinessPartnerTable.id))
    }

    /**
     * A composite unique index is NOT a uniqueness guarantee on either column alone.
     *
     * `supplier_bank_account` is UNIQUE(partner_id, iban), which permits many rows per partner —
     * reading that as unique on `partner_id` would reintroduce exactly the row multiplication this
     * class exists to prevent.
     */
    @Test
    fun `a composite unique index does not make its columns unique`() {
        assertTrue(SchemaGraph.isUnique(SupplierBankAccountTable.partnerId).not())
    }
}
