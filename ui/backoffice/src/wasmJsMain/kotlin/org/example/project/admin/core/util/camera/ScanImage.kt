package org.example.project.admin.core.util.camera

/** A raster the scanner works on. An interface so the ViewModel can be tested without a DOM. */
interface ScanImage {
    val width: Int
    val height: Int
}
