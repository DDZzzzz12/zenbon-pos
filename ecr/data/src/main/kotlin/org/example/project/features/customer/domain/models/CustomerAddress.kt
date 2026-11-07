/**
 * CustomerAddress entity class representing customer addresses in the POS system.
 *
 * Manages delivery and billing addresses for customers with support for
 * multiple addresses per customer and geographic coordinates for delivery routing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.customer.domain.models

import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.math.BigDecimal
import java.util.UUID

/**
 * CustomerAddress entity representing an address for a customer.
 *
 * Supports multiple address types (delivery, billing, shipping) with
 * geographic coordinates for delivery optimization and detailed address
 * information for accurate deliveries.
 *
 * Relationships:
 * - Many-to-One with Customer (each address belongs to one customer)
 * - One-to-Many with TableSession (for delivery sessions)
 */
class CustomerAddress(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CustomerAddress>(CustomerAddressTable)

    // Customer Relationship

    /** Reference to the customer who owns this address */
    var customer by Customer referencedOn CustomerAddressTable.customerId

    // Address Classification
    /** Type of address: "delivery", "billing", "shipping", "other" */
    var addressType by CustomerAddressTable.addressType

    /** Whether this is the customer's default address */
    var isDefault by CustomerAddressTable.isDefault

    // Address Details
    /** Street name */
    var street by CustomerAddressTable.street

    /** House or building number */
    var houseNumber by CustomerAddressTable.houseNumber

    /** Apartment, suite, or unit number (optional) */
    var apartment by CustomerAddressTable.apartment

    /** Postal/ZIP code */
    var postalCode by CustomerAddressTable.postalCode

    /** City name */
    var city by CustomerAddressTable.city

    /** State or province (optional) */
    var state by CustomerAddressTable.state

    /** Country name */
    var country by CustomerAddressTable.country

    // Additional Information
    /** Additional delivery instructions or notes */
    var additionalInfo by CustomerAddressTable.additionalInfo

    // Geographic Coordinates
    /** Geographic latitude for delivery routing (optional) */
    var latitude by CustomerAddressTable.latitude

    /** Geographic longitude for delivery routing (optional) */
    var longitude by CustomerAddressTable.longitude

    // Audit & Tracking
    /** When the address was created */
    var createdAt by CustomerAddressTable.createdAt

    /** When the address was last updated */
    var updatedAt by CustomerAddressTable.updatedAt

    // Computed Properties
    /**
     * Get the full formatted address as a single string.
     *
     * Format: "Street HouseNumber, Apartment, PostalCode City, State, Country"
     *
     * @return Formatted address string
     */
    val formattedAddress: String
        get() {
            val parts = mutableListOf<String>()

            // Street and house number
            parts.add("$street $houseNumber")

            // Apartment if present
            apartment?.let { parts.add(it) }

            // Postal code and city
            parts.add("$postalCode $city")

            // State if present
            state?.let { parts.add(it) }

            // Country
            parts.add(country)

            return parts.joinToString(", ")
        }

    /**
     * Get a short formatted address (street and city only).
     *
     * Format: "Street HouseNumber, City"
     *
     * @return Short address string
     */
    val shortAddress: String
        get() = "$street $houseNumber, $city"

    /**
     * Check if this address has geographic coordinates.
     *
     * @return true if both latitude and longitude are set
     */
    fun hasCoordinates(): Boolean = latitude != null && longitude != null

    /**
     * Get coordinates as a pair if available.
     *
     * @return Pair of (latitude, longitude) or null if not available
     */
    fun getCoordinates(): Pair<BigDecimal, BigDecimal>? =
        if (latitude != null && longitude != null) {
            Pair(latitude!!, longitude!!)
        } else {
            null
        }

    /**
     * Check if this is a delivery address.
     *
     * @return true if addressType is "delivery"
     */
    fun isDeliveryAddress(): Boolean = addressType == "delivery"

    /**
     * Check if this is a billing address.
     *
     * @return true if addressType is "billing"
     */
    fun isBillingAddress(): Boolean = addressType == "billing"
}
