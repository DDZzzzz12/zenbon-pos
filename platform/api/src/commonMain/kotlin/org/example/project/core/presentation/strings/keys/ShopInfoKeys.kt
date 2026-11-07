package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object ShopInfoKeys {
    val screenTitle = StringKey("shop_info.screen_title")
    val logoSection = StringKey("shop_info.logo_section")
    val uploadLogo = StringKey("shop_info.upload_logo")
    val removeLogo = StringKey("shop_info.remove_logo")
    val uploadingLogo = StringKey("shop_info.uploading_logo")
    val basicInfoSection = StringKey("shop_info.basic_info_section")
    val contactInfoSection = StringKey("shop_info.contact_info_section")
    val financialInfoSection = StringKey("shop_info.financial_info_section")
    val restaurantMode = StringKey("shop_info.restaurant_mode")
    val restaurantModeDescription = StringKey("shop_info.restaurant_mode_description")
    val shopName = StringKey("shop_info.shop_name")
    val companyName = StringKey("shop_info.company_name")
    val address = StringKey("shop_info.address")
    val city = StringKey("shop_info.city")
    val zipCode = StringKey("shop_info.zip_code")
    val country = StringKey("shop_info.country")
    val phone = StringKey("shop_info.phone")
    val email = StringKey("shop_info.email")
    val website = StringKey("shop_info.website")
    val taxNumber = StringKey("shop_info.tax_number")
    val bankName = StringKey("shop_info.bank_name")
    val bankBic = StringKey("shop_info.bank_bic")
    val bankIban = StringKey("shop_info.bank_iban")

    /**
     * Server refuses a shop-data edit while the business day is open
     * (`PosErrorCode.SHOP_INFO_CLOSING_REQUIRED`, HTTP 409 — DSFinV-K round-5 KABS-2).
     * DSFinV-K requires master data to be snapshotted per Kassenabschluss, so the change has to
     * land on a closing boundary. Without this key the operator saw only a generic error toast.
     */
    val closingRequiredError = StringKey("shop_info.closing_required_error")
}
