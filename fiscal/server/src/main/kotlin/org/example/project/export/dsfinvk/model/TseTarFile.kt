package org.example.project.export.dsfinvk.model

/**
 * One TSE TAR shipped in a DSFinV-K package's `tse/` folder, under [fileName]. A package carries the
 * attached device's export and, when the period reaches back into entries an export-and-delete erased
 * from it, the registered archives holding them (real-TSE QA rt14).
 */
class TseTarFile(
    val fileName: String,
    val bytes: ByteArray,
)
