package org.example.project.features.customer.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Database table for customer information.
 *
 * Stores customer details including personal information, contact details,
 * and metadata for CRM and marketing purposes in a POS system.
 *
 * Features:
 * - Personal information (name, email, phone)
 * - Address details
 * - Marketing preferences and GDPR consent
 * - Customer loyalty tracking
 * - Audit timestamps
 *
 * @property id UUID primary key (customer_id)
 * @property customerNumber Unique customer identifier for display
 * @property firstName Customer's first name
 * @property lastName Customer's last name
 * @property email Customer's email address (nullable, unique)
 * @property phone Customer's phone number (nullable)
 * @property notes Additional notes about the customer
 * @property marketingConsent Whether customer consented to marketing communications
 * @property dataProcessingConsent GDPR consent for data processing
 * @property lastPurchaseAt Timestamp of last purchase (nullable)
 * @property isActive Whether customer account is active
 * @property createdAt Timestamp when customer was created
 * @property updatedAt Timestamp when customer was last updated
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object CustomerTable : UUIDTable("customers", "customer_id") {
    // Customer Identity
    val customerNumber = varchar("customer_number", 20).uniqueIndex()
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)

    // Contact Information
    val email = varchar("email", 255).nullable().uniqueIndex()
    val phone = varchar("phone", 20).nullable().uniqueIndex()

    // Personal Details
    val notes = text("notes").nullable()

    // Marketing & Privacy
    val marketingConsent = bool("marketing_consent").default(false)
    val dataProcessingConsent = bool("data_processing_consent").default(true)

    // Loyalty & Statistics
    val lastPurchaseAt = datetime("last_purchase_at").nullable()

    // Status
    val isActive = bool("is_active").default(true)

    // Audit & Tracking
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at")
}
