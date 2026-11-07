package org.example.project.admin.core.util.camera

import org.w3c.dom.HTMLCanvasElement

/** The browser implementation of [ScanImage]: pixels live in a canvas. */
class CanvasImage(
    val canvas: HTMLCanvasElement,
) : ScanImage {
    override val width: Int get() = canvas.width
    override val height: Int get() = canvas.height
}
