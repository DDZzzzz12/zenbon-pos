package org.example.project.core.auth

import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.presentation.settings.DeviceSettingsMirror

/** Writes the mirror through to this device's [SettingManager], which backs it with local storage. */
class SettingManagerDeviceMirror(
    private val settingManager: SettingManager,
) : DeviceSettingsMirror {
    override fun setEmployeeSelectionOnFreeTable(value: Boolean) = settingManager.setEmployeeSelectionOnFreeTable(value)

    override fun setRoundRobinAssignment(value: Boolean) = settingManager.setRoundRobinAssignment(value)

    override fun setAllowCrossEmployeeBilling(value: Boolean) = settingManager.setAllowCrossEmployeeBilling(value)

    override fun setRestaurantMode(value: Boolean) = settingManager.setRestaurantMode(value)

    override fun setShopLogoUrl(value: String?) = settingManager.setShopLogoUrl(value)

    override fun setVoucherSystemEnabled(value: Boolean) = settingManager.setVoucherSystemEnabled(value)

    override fun setVoucherSource(value: List<VoucherSource>) = settingManager.setVoucherSource(value)
}
