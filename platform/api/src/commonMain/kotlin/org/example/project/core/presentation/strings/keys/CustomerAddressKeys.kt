package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object CustomerAddressKeys {
    // Panel Headers
    val selectCustomerTitle = StringKey("customer_address.select_customer_title")

    // getClose delegates to CommonStrings — skipped
    val back = StringKey("customer_address.back")

    // Customer Search
    val searchLabel = StringKey("customer_address.search_label")
    val addNewCustomer = StringKey("customer_address.add_new_customer")
    val searchForCustomer = StringKey("customer_address.search_for_customer")
    val searchInstructions = StringKey("customer_address.search_instructions")

    // Address Selection
    val selectDeliveryAddress = StringKey("customer_address.select_delivery_address")
    val deliverySession = StringKey("customer_address.delivery_session")
    val orderWillBeDelivered = StringKey("customer_address.order_will_be_delivered")
    val takeawayOrder = StringKey("customer_address.takeaway_order")
    val addNewAddress = StringKey("customer_address.add_new_address")
    val defaultLabel = StringKey("customer_address.default_label")
    val apartmentLabel = StringKey("customer_address.apartment_label") // {0} = apartment

    // Confirmation Screen
    val selectionComplete = StringKey("customer_address.selection_complete")
    val customerLabel = StringKey("customer_address.customer_label")
    val deliveryAddressLabel = StringKey("customer_address.delivery_address_label")
    val changeLabel = StringKey("customer_address.change_label")
    val confirmSelection = StringKey("customer_address.confirm_selection")
    val removeAssignment = StringKey("customer_address.remove_assignment")
    val deliveryNotice = StringKey("customer_address.delivery_notice")

    // Review Screen
    val customerDetailsTitle = StringKey("customer_address.customer_details_title")
    val phoneRequiredForDelivery = StringKey("customer_address.phone_required_for_delivery")
    val addressRequiredForDelivery = StringKey("customer_address.address_required_for_delivery")
    val noAddressesOnFile = StringKey("customer_address.no_addresses_on_file")
    val changeCustomer = StringKey("customer_address.change_customer")
    val noPhone = StringKey("customer_address.no_phone")
    val customerNumberLabel = StringKey("customer_address.customer_number_label") // {0} = number
    val setAsDefaultAddress = StringKey("customer_address.set_as_default_address")
    val defaultAddressHint = StringKey("customer_address.default_address_hint")
    val saveAddress = StringKey("customer_address.save_address")
    val saveCustomer = StringKey("customer_address.save_customer")
    val consentSection = StringKey("customer_address.consent_section")
    val marketingConsent = StringKey("customer_address.marketing_consent")
    val dataProcessingConsent = StringKey("customer_address.data_processing_consent")
    val changeAddress = StringKey("customer_address.change_address")
    val confirmAndContinue = StringKey("customer_address.confirm_and_continue")
}
