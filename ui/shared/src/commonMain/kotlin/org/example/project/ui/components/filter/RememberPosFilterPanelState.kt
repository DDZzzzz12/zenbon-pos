package org.example.project.ui.components.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * A [PosFilterPanelState] that survives recomposition and keeps [PosFilterPanelState.applied] equal
 * to [applied]. Call it above the screen's layout-switching branches (see [PosFilterPanelState]).
 *
 * With a [key] and a [LocalPosFilterPanelStore] the state is looked up in (or created in) the store,
 * so it also survives the screen being disposed and rebuilt by the host (adminApp's compact/wide
 * shell swap). Without a key or a store it is a plain `remember`, as in the till.
 */
@Composable
fun <T> rememberPosFilterPanelState(
    applied: T,
    key: String? = null,
): PosFilterPanelState<T> {
    val store = LocalPosFilterPanelStore.current
    val state =
        if (key != null && store != null) {
            remember(store, key) { store.getOrCreate(key, applied) }
        } else {
            remember { PosFilterPanelState(applied) }
        }
    SideEffect { state.applied = applied }
    return state
}

/**
 * Call it first in a screen's panel composable, after `if (!isOpen) return`: when [hasFields] is
 * false the panel has nothing to show (see [PosFilterPanelState.closeIfEmpty]), so it is closed from
 * an effect and this returns true; the caller then returns without composing the [PosFilterPanel].
 */
@Composable
fun PosFilterPanelState<*>.closeWhenEmpty(hasFields: Boolean): Boolean {
    if (hasFields) return false
    LaunchedEffect(Unit) { closeIfEmpty(hasFields = false) }
    return true
}
