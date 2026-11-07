package org.example.project.features.customer.database.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Database table for customer address information.
 *
 * Stores detailed address information for customers, supporting multiple
 * addresses per customer (billing, shipping, etc.) with proper normalization.
 *
 * Features:
 * - Multiple addresses per customer support
 * - Address type classification (billing, shipping, delivery)
 * - Default address marking
 * - Complete address details with geographic information
 * - Audit timestamps
 *
 * Relationships:
 * - Many-to-One with CustomerTable (multiple addresses per customer)
 *
 * @property id UUID primary key (address_id)
 * @property customerId Reference to customer who owns this address
 * @property addressType Type of address (billing, shipping, delivery, other)
 * @property isDefault Whether this is the customer's default address
 * @property street Street name
 * @property houseNumber House/building number
 * @property apartment Apartment, suite, or unit number (optional)
 * @property postalCode Postal/ZIP code
 * @property city City name
 * @property state State or province (optional)
 * @property country Country name
 * @property additionalInfo Additional delivery instructions or notes (optional)
 * @property latitude Geographic latitude for delivery routing (optional)
 * @property longitude Geographic longitude for delivery routing (optional)
 * @property createdAt Timestamp when address was created
 * @property updatedAt Timestamp when address was last updated
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object CustomerAddressTable : UUIDTable("customer_addresses", "address_id") {
    // Customer Relationship
    val customerId = reference("customer_id", CustomerTable)

    // Address Classification
    val addressType = varchar("address_type", 20).default("delivery") // billing, shipping, delivery, other
    val isDefault = bool("is_default").default(false)

    // Address Details
    val street = varchar("street", 255)
    val houseNumber = varchar("house_number", 10)
    val apartment = varchar("apartment", 50).nullable()
    val postalCode = varchar("postal_code", 10)
    val city = varchar("city", 100)
    val state = varchar("state", 100).nullable()
    val country = varchar("country", 100).default("Germany")

    // Additional Information
    val additionalInfo = text("additional_info").nullable() // "Ring doorbell 2x", "Leave at door", etc.

    // Geographic Coordinates (for delivery optimization)
    val latitude = decimal("latitude", precision = 10, scale = 8).nullable()
    val longitude = decimal("longitude", precision = 11, scale = 8).nullable()

    // Audit & Tracking
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}
