package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.layout.adminListMaxWidth
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact

/**
 * The scaffold's padding on every side. Exposed so a screen that needs the width the scaffold's content
 * actually gets (content width = available width - 2 x this) uses the same number, not a copy.
 */
val AdminListScaffoldPadding = 16.dp

/**
 * The shape every adminApp list screen shares: a title with its actions, a toolbar, a filter-chip
 * row, a result count, and then rows — or a skeleton while loading, or an empty state when there are
 * none.
 *
 * Layout only. It holds no state, calls no ViewModel and knows nothing about paging, so each screen
 * keeps its own view model and decides for itself what "empty" and "loading" mean. That is what lets
 * a plain list and a list-beside-a-detail-pane share it.
 *
 * **Not for Belege.** That screen is a single `LazyColumn`, because a `LazyColumn` nested inside a
 * `verticalScroll` measures to infinite height and crashes on wasm. This scaffold scrolls its own
 * `Column`, so Belege has to build the same arrangement out of `item`s instead.
 *
 * @param subtitle a quiet line under the title; it stays on a phone, where the title itself is dropped.
 * @param isLoading shows the skeleton **in place of** [content]; a refresh is not a page blank.
 * @param isEmpty shows [empty] instead of [content]. The caller decides — usually
 *   `items.isEmpty() && !isLoading` — because "empty" and "not loaded yet" are different states and
 *   only the screen knows which one it is in.
 * @param showHeader `false` drops the title row, toolbar, filters and result count so [content] starts
 *   at the top. A screen uses it on a phone while a detail is open; every other caller keeps the
 *   default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminListScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    toolbar: (@Composable () -> Unit)? = null,
    filters: (@Composable () -> Unit)? = null,
    resultCount: String? = null,
    isLoading: Boolean = false,
    isEmpty: Boolean = false,
    showHeader: Boolean = true,
    empty: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Separate states: the list keeps its offset when the detail closes, a fresh detail starts at its top.
    val listScroll = rememberScrollState()
    val detailScroll = key(showHeader) { rememberScrollState() }
    val scrollState = if (showHeader) listScroll else detailScroll
    // The bound sits on an inner column, inside the padding and the scroll: AdminSplitPane and
    // AdminDrillHost keep deciding on the real width (every split threshold is below the cap, so the
    // cap never changes a pane-count decision), and on a phone the maximum is simply never reached.
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(AdminListScaffoldPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.adminListMaxWidth().fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showHeader) {
                // A FlowRow, not a Row: with a long title and a labelled action button a single row
                // truncated the button's text on a phone ("Neue Re…") or crushed it to a blank stub. When
                // title and actions do not fit side by side the actions drop under the title; on a wide
                // screen they still sit at the right edge (SpaceBetween), exactly as before. The title is
                // deliberately NOT weighted: a weight would make it shrink and wrap before the actions ever
                // drop, which is the Row behaviour this FlowRow replaced.
                // On a phone the title is not composed (AdminPageTitle): the top bar already shows it.
                // The actions stay; with no title they start at the leading edge.
                val showTitle = !LocalWindowSizeClass.current.isCompact()
                if (showTitle || actions != null || subtitle != null) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (subtitle == null) {
                            AdminPageTitle(title)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                AdminPageTitle(title)
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (actions != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                        }
                    }
                }

                toolbar?.invoke()
                filters?.invoke()

                if (resultCount != null) {
                    Text(
                        text = resultCount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                isLoading -> AdminListSkeleton()
                isEmpty -> empty?.invoke()
                else -> content()
            }
        }
    }
}
