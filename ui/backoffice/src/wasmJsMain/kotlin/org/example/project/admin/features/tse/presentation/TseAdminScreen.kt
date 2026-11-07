package org.example.project.admin.features.tse.presentation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.canAdministerTse
import org.example.project.core.auth.TokenStore
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.layout.PaneLayout
import org.example.project.ui.screens.settings.PosTseContent
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Host for the shared TSE surface. All content comes from `shared-ui`'s [PosTseContent], the same
 * composable composeApp renders, so the two clients cannot drift.
 *
 * The `LaunchedEffect` re-load exists because adminApp VMs can outlive a logout while
 * [TseSettingsViewModel] does its loading in `init`; re-entering the destination must show the
 * current session's data, not the previous one's. Both calls are idempotent.
 *
 * `refreshAdminSessionStatus()` is re-run alongside the data load for the reason the ViewModel's
 * own init states: a TSE admin sitting can outlive the screen, or lapse while it is open, so the
 * indicator has to ask the server rather than assume. Without it a retained ViewModel would carry
 * the previous session's `isAdminUnlocked` into the next one and the lock/unlock controls would
 * lie.
 *
 * [onOpenTerminals] leads from the setup dialog's "no terminal yet" state to the Terminals screen.
 *
 * `AdminApp` provides [LocalWindowSizeClass] at the root, so no local provider is needed. The header
 * follows the **window** class (below 600dp the shell's top bar names the page); the column count and
 * the phone styling follow the **content** width, via [PaneBreakpoints]: two columns only where two
 * ~420dp columns fit.
 */
@Composable
fun TseAdminScreen(
    onOpenTerminals: () -> Unit,
    vm: TseSettingsViewModel = koinViewModel(),
    tokenStore: TokenStore = koinInject(),
) {
    LaunchedEffect(Unit) {
        vm.loadTseData()
        vm.refreshAdminSessionStatus()
    }

    // Only the configuration form is a draft; Discard leaves edit mode and restores the saved values.
    val uiState by vm.uiState.collectAsState()
    rememberDirtySource(dirty = uiState.hasUnsavedConfigEdits(), onDiscard = vm::cancelEditing)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        PosTseContent(
            language = LocalAppLanguage.current,
            isCompact = LocalWindowSizeClass.current.isCompact(),
            isAdminOrOwner = tokenStore.canAdministerTse,
            viewModel = vm,
            // The setup dialog's empty state: no fiscal terminal exists yet, so create one first.
            // Closed on the way out — the VM outlives the screen and would reopen it with a stale list.
            onOpenTerminals = {
                vm.hideSetupDialog()
                onOpenTerminals()
            },
            stackColumns = PaneBreakpoints.layoutFor(maxWidth, maxColumns = 2) !is PaneLayout.Columns,
            phoneLayout = maxWidth < PaneBreakpoints.PHONE_MAX,
        )
    }
}
