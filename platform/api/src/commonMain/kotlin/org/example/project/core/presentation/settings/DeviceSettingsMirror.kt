package org.example.project.core.presentation.settings

import org.example.project.core.domain.models.setup.VoucherSource

/**
 * Device-local mirror of server-side settings, so a till can act on them while offline.
 *
 * The settings ViewModels render from the API; these calls only push a copy down to the device.
 * That makes the mirror a *device* concern rather than a settings-screen one, which is why the web
 * admin — which runs no till and has no local store — supplies [NoOpDeviceSettingsMirror].
 */
interface DeviceSettingsMirror {
    fun setEmployeeSelectionOnFreeTable(value: Boolean)

    fun setRoundRobinAssignment(value: Boolean)

    fun setAllowCrossEmployeeBilling(value: Boolean)

    fun setRestaurantMode(value: Boolean)

    fun setShopLogoUrl(value: String?)

    fun setVoucherSystemEnabled(value: Boolean)

    fun setVoucherSource(value: List<VoucherSource>)
}

/** The web-admin implementation: there is no device to mirror to. */
object NoOpDeviceSettingsMirror : DeviceSettingsMirror {
    override fun setEmployeeSelectionOnFreeTable(value: Boolean) = Unit

    override fun setRoundRobinAssignment(value: Boolean) = Unit

    override fun setAllowCrossEmployeeBilling(value: Boolean) = Unit

    override fun setRestaurantMode(value: Boolean) = Unit

    override fun setShopLogoUrl(value: String?) = Unit

    override fun setVoucherSystemEnabled(value: Boolean) = Unit

    override fun setVoucherSource(value: List<VoucherSource>) = Unit
}
