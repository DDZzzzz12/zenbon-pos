package org.example.project.ui.components.form

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Scroll-to-first-error support for "validate on Save click" forms.
 *
 * Mark each validated field with [formAnchor]; after a failed validation call [revealFirst] with the
 * form's field order and the error map. It scrolls the first invalid field into view inside whatever
 * scroll container holds it (a drawer `verticalScroll` or a self-scrolling `PosDialog`) and moves the
 * keyboard focus into it, so the operator can type the missing value straight away; it adds no
 * scroll container itself.
 *
 * Put the anchor on the field's own modifier (the text field, not a wrapper around it): focus can only
 * land on a focusable node below the requester. An anchor on a non-focusable node (a switch row, a
 * chip group) still scrolls; the focus request is then simply refused.
 */
@OptIn(ExperimentalFoundationApi::class)
class PosFormFocus<K : Any> {
    private val requesters = mutableMapOf<K, BringIntoViewRequester>()
    private val focusRequesters = mutableMapOf<K, FocusRequester>()

    /** Registers this node as the scroll and focus target for [key]. */
    fun Modifier.formAnchor(key: K): Modifier =
        bringIntoViewRequester(requesters.getOrPut(key) { BringIntoViewRequester() })
            .focusRequester(focusRequesters.getOrPut(key) { FocusRequester() })

    /** Scrolls to the first key of [order] that has an entry in [errors] and focuses it; no-op when none. */
    suspend fun revealFirst(
        order: List<K>,
        errors: Map<K, *>,
    ) {
        val key = firstInvalid(order, errors) ?: return
        requesters[key]?.bringIntoView()
        // A focus request on a node that holds no focusable child throws on some Compose versions and
        // returns false on others; either way the scroll has already happened, which is the part that
        // matters for a field the operator cannot type into.
        runCatching { focusRequesters[key]?.requestFocus() }
    }
}

/** The first key in [order] that is present in [errors], or null. Keys absent from [order] are ignored. */
fun <K> firstInvalid(
    order: List<K>,
    errors: Map<K, *>,
): K? = order.firstOrNull { it in errors }

@Composable
fun <K : Any> rememberPosFormFocus(): PosFormFocus<K> = remember { PosFormFocus() }
