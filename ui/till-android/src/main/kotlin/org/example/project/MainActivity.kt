package org.example.project

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import co.touchlab.kermit.Logger
import org.example.project.core.notification.NotificationNavigation
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.utils.FileSaver
import org.example.project.core.utils.LanguageProvider
import org.example.project.core.utils.PdfPrinter
import org.koin.java.KoinJavaComponent.get

class MainActivity : ComponentActivity() {
    private val logger = Logger.withTag("MainActivity")

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            logger.i { "MainActivity.notificationPermission: granted=$granted" }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        FileSaver.initialize(applicationContext)
        PdfPrinter.initialize(this)

        requestNotificationPermissionIfNeeded()

        setContent {
            LanguageProvider {
                App()
            }
        }

        handleNotificationIntent(intent)
    }

    /**
     * The till is a full-screen app: hide the system navigation/gesture bar and let an edge swipe
     * bring it back for a moment. Dialogs, the keyboard and focus changes un-hide it, so it is
     * re-applied whenever the window regains focus. The status bar is left alone.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent) {
        val notificationType = intent.getStringExtra("notification_type") ?: return
        val entityId = intent.getStringExtra("notification_entity_id")

        logger.i { "MainActivity.handleNotificationIntent: type=$notificationType, entityId=$entityId" }

        val service = get<PlatformNotificationService>(PlatformNotificationService::class.java)
        service.setPendingNavigation(NotificationNavigation(notificationType, entityId))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                logger.i { "MainActivity.requestNotificationPermission: requesting POST_NOTIFICATIONS permission" }
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
