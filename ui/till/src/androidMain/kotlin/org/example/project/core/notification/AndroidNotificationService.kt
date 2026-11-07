package org.example.project.core.notification

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNotificationService(
    private val context: Context,
) : PlatformNotificationService {
    private val logger = Logger.withTag("AndroidNotificationService")

    private val _pendingNavigation = MutableStateFlow<NotificationNavigation?>(null)
    override val pendingNavigation: StateFlow<NotificationNavigation?> = _pendingNavigation.asStateFlow()

    private val _backgroundExecutionPromptVisible = MutableStateFlow(false)
    override val backgroundExecutionPromptVisible: StateFlow<Boolean> = _backgroundExecutionPromptVisible.asStateFlow()

    override fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    override fun setPendingNavigation(navigation: NotificationNavigation) {
        _pendingNavigation.value = navigation
    }

    override fun startService() {
        logger.i { "AndroidNotificationService.startService: starting POS notification service" }
        PosNotificationService.start(context)
        // Explain first; the settings open only when the operator chooses "set up now". Opening
        // them straight after login, unexplained, is what the spec's dialog was meant to prevent.
        _backgroundExecutionPromptVisible.value = needsBackgroundExecutionPrompt()
    }

    override fun stopService() {
        logger.i { "AndroidNotificationService.stopService: stopping POS notification service" }
        _backgroundExecutionPromptVisible.value = false
        PosNotificationService.stop(context)
    }

    override fun openBackgroundExecutionSettings() {
        _backgroundExecutionPromptVisible.value = false
        prefs().edit { putBoolean(PREF_BATTERY_OPT_PROMPTED, true) }
        logger.i { "AndroidNotificationService.openBackgroundExecutionSettings: opening battery optimization settings" }
        val intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            logger.w(e) {
                "AndroidNotificationService.openBackgroundExecutionSettings: no battery optimization settings screen, " +
                    "falling back to app details settings"
            }
            val fallbackIntent =
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: ActivityNotFoundException) {
                logger.w(e2) { "AndroidNotificationService.openBackgroundExecutionSettings: no app details settings screen either" }
            }
        }
    }

    override fun postponeBackgroundExecutionPrompt() {
        logger.i { "AndroidNotificationService.postponeBackgroundExecutionPrompt: asking again in a week" }
        _backgroundExecutionPromptVisible.value = false
        prefs().edit { putLong(PREF_BATTERY_OPT_POSTPONED_UNTIL, System.currentTimeMillis() + BackgroundPromptPolicy.POSTPONE_MILLIS) }
    }

    private fun needsBackgroundExecutionPrompt(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return BackgroundPromptPolicy.shouldPrompt(
            ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            alreadySentToSettings = prefs().getBoolean(PREF_BATTERY_OPT_PROMPTED, false),
            postponedUntilMillis = prefs().getLong(PREF_BATTERY_OPT_POSTPONED_UNTIL, 0L),
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "pos_prefs"

        /** Same key as before this change, so a till that was already sent to settings is not asked again. */
        const val PREF_BATTERY_OPT_PROMPTED = "battery_opt_prompted"

        /** Epoch millis before which "Später" keeps the prompt away. */
        const val PREF_BATTERY_OPT_POSTPONED_UNTIL = "battery_opt_postponed_until"
    }
}
