/**
 * Android implementation of [DocumentScannerButton].
 *
 * Uses Google ML Kit Document Scanner to provide camera-based document scanning
 * with automatic edge detection, cropping, and PDF generation.
 *
 * Requires Google Play Services to be available on the device.
 *
 * @since 2.0.0
 */
package org.example.project.core.presentation.components.docUtil

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Android document scanner button using ML Kit.
 *
 * Checks for Google Play Services availability and configures the ML Kit Document Scanner
 * to capture documents via camera, returning the scanned result as a [DocumentScanResult]
 * with PDF bytes.
 *
 * If GMS is unavailable, the button is not rendered.
 */
@OptIn(ExperimentalTime::class)
@Composable
actual fun DocumentScannerButton(
    onScanResult: (DocumentScanResult) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    buttonContent: @Composable () -> Unit,
) {
    val context = LocalContext.current

    // Check Google Play Services availability
    val isGmsAvailable =
        remember {
            GoogleApiAvailability
                .getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }

    // If GMS is not available, don't render the button
    if (!isGmsAvailable) return

    // Configure the document scanner to produce PDF
    val scanner: GmsDocumentScanner =
        remember {
            val options =
                GmsDocumentScannerOptions
                    .Builder()
                    .setGalleryImportAllowed(true)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
            GmsDocumentScanning.getClient(options)
        }

    // Register the activity result launcher for the scanner intent
    val scannerLauncher =
        rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pdfUri = scanningResult?.pdf?.uri

                if (pdfUri != null) {
                    try {
                        val bytes = context.contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
                        if (bytes != null && bytes.isNotEmpty()) {
                            // Generate a timestamped filename
                            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            val timestamp =
                                "%04d%02d%02d_%02d%02d".format(
                                    now.year,
                                    now.month.number,
                                    now.day,
                                    now.hour,
                                    now.minute,
                                )
                            val fileName = "scanned_document_$timestamp.pdf"
                            onScanResult(DocumentScanResult(pdfBytes = bytes, fileName = fileName))
                        } else {
                            onError("Scanned PDF is empty")
                        }
                    } catch (e: Exception) {
                        onError("Failed to read scanned document: ${e.message}")
                    }
                }
                // If pdfUri is null and resultCode is OK, user may have cancelled — silent no-op
            }
            // RESULT_CANCELED — user cancelled, silent no-op
        }

    OutlinedButton(
        onClick = {
            val activity = context.findActivity()
            if (activity != null) {
                scanner
                    .getStartScanIntent(activity)
                    .addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build(),
                        )
                    }.addOnFailureListener { e ->
                        onError("Failed to start scanner: ${e.message}")
                    }
            } else {
                onError("Cannot access Activity context")
            }
        },
        modifier = modifier,
    ) {
        buttonContent()
    }
}

/**
 * Unwraps a [Context] to find the hosting [Activity], traversing through [ContextWrapper] layers.
 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
