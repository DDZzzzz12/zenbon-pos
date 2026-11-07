package org.example.project.ui.components

/** How a [PosDialog] presents itself in a window narrower than the phone breakpoint. */
enum class PosDialogPhoneMode {
    /** Full-screen when it has a footer and is wider than the default card; small confirms stay compact. */
    Auto,

    /** Always full-screen on a phone. */
    FullScreen,

    /** Always the compact card. */
    Compact,
}
