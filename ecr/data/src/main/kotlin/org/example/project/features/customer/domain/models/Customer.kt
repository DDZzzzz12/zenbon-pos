/**
 * Customer entity class representing customers in the POS system.
 *
 * Core entity for managing customer information, contact details, and loyalty tracking.
 * Supports both dine-in loyalty programs and delivery customer management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.customer.domain.models

import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.example.project.features.customer.database.tables.CustomerTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

/**
 * Customer entity representing a customer in the POS system.
 *
 * Manages customer data including personal information, contact details,
 * privacy consents, and loyalty tracking. Customers can have multiple addresses
 * and can be associated with table sessions for loyalty programs or delivery orders.
 *
 * Relationships:
 * - One-to-Many with CustomerAddress (multiple addresses per customer)
 * - One-to-Many with TableSession (customer's order history)
 */
class Customer(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Customer>(CustomerTable)

    // Customer Identity

    /** Unique customer number for display purposes */
    var customerNumber by CustomerTable.customerNumber

    /** Customer's first name */
    var firstName by CustomerTable.firstName

    /** Customer's last name */
    var lastName by CustomerTable.lastName

    // Contact Information
    /** Customer's email address (optional, unique) */
    var email by CustomerTable.email

    /** Customer's phone number (optional, unique) */
    var phone by CustomerTable.phone

    // Personal Details
    /** Additional notes about the customer */
    var notes by CustomerTable.notes

    // Marketing & Privacy
    /** Whether customer consented to receive marketing communications */
    var marketingConsent by CustomerTable.marketingConsent

    /** GDPR consent for data processing */
    var dataProcessingConsent by CustomerTable.dataProcessingConsent

    // Loyalty & Statistics
    /** Timestamp of customer's last purchase (nullable if no purchases yet) */
    var lastPurchaseAt by CustomerTable.lastPurchaseAt

    // Status
    /** Whether customer account is active */
    var isActive by CustomerTable.isActive

    // Audit & Tracking
    /** When the customer record was created */
    var createdAt by CustomerTable.createdAt

    /** When the customer record was last updated */
    var updatedAt by CustomerTable.updatedAt

    // Relationships
    /** Customer's addresses (delivery, billing, etc.) */
    val addresses by CustomerAddress referrersOn CustomerAddressTable.customerId

    // Computed Properties
    /**
     * Customer's full name (first + last).
     *
     * @return Combined first and last name
     */
    val fullName: String
        get() = "$firstName $lastName"

    /**
     * Get the default address for this customer.
     *
     * Returns the address marked as default, or null if no default is set.
     *
     * @return Default CustomerAddress or null
     */
    fun getDefaultAddress(): CustomerAddress? = addresses.firstOrNull { it.isDefault }

    /**
     * Get addresses by type.
     *
     * @param type Address type to filter by (e.g., "delivery", "billing")
     * @return List of matching addresses
     */
    fun getAddressesByType(type: String): List<CustomerAddress> = addresses.filter { it.addressType == type }

    /**
     * Get the primary contact method (email preferred, fallback to phone).
     *
     * @return Email if available, otherwise phone, or null if neither
     */
    fun getPrimaryContact(): String? = email ?: phone

    /**
     * Check if customer has given marketing consent.
     *
     * @return true if customer can receive marketing communications
     */
    fun canReceiveMarketing(): Boolean = marketingConsent && isActive
}
