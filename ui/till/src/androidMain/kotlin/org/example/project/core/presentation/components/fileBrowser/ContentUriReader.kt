package org.example.project.core.presentation.components.fileBrowser

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.example.project.core.domain.fileBrowser.models.SelectedFile

/** Reads a SAF content URI into a [SelectedFile]. Blocking — call off the main thread. */
class ContentUriReader(
    private val resolver: ContentResolver,
) {
    fun read(uri: Uri): SelectedFile {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "document"
        val bytes =
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot open $uri")
        return SelectedFile(path = uri.toString(), name = name, bytes = bytes, mimeType = resolver.getType(uri))
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
