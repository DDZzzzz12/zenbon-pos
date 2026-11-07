package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * `true` while the last finished load succeeded, so a section keeps rendering its previous data
 * during a refresh.
 *
 * A section shows its placeholder only until the first load has finished. A refresh sets `isLoading`
 * again, but the view model keeps the previous data, so the card renders it instead of collapsing to
 * nothing and popping back in. After a *failed* load there is no previous data (the state holds
 * defaults that would read as a real answer), so this is `false` again until a load succeeds.
 *
 * The holder is a plain array, not snapshot state: it is only read in the composition that writes
 * it, and every load result arrives as a state change that recomposes the card anyway.
 */
@Composable
fun rememberSectionSettled(
    isLoading: Boolean,
    failed: Boolean,
): Boolean {
    val settled = remember { BooleanArray(1) }
    if (!isLoading) settled[0] = !failed
    return settled[0]
}
