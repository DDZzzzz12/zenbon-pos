package org.example.project.features.changelog.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.core.domain.dto.changelog.ChangelogSection
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.ChangelogKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.formatShortDate
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.theme.PosSpacing

@Composable
fun ChangelogScreen(viewModel: ChangelogViewModel) {
    val state by viewModel.state.collectAsState()
    val windowSize = LocalWindowSizeClass.current
    val isCompact = windowSize.isCompact()
    val isExpanded = windowSize.isAtLeastExpanded()

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.isLoading && state.items.isEmpty()) {
            PosLoadingOverlay(title = str(ChangelogKeys.screenTitle))
        } else if (state.items.isEmpty() && state.error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = str(ChangelogKeys.emptyState),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()
            val reachedBottom by remember {
                derivedStateOf {
                    val lastVisible =
                        listState.layoutInfo.visibleItemsInfo
                            .lastOrNull()
                            ?.index ?: -1
                    lastVisible >= state.items.lastIndex - 3
                }
            }
            LaunchedEffect(reachedBottom) {
                if (reachedBottom) viewModel.loadMore()
            }

            val horizontalPadding = if (isCompact) 16.dp else 24.dp
            val contentMaxWidth = if (isCompact) 600.dp else 720.dp

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(state.items, key = { it.releaseVersion }) { release ->
                    Column(modifier = Modifier.widthIn(max = contentMaxWidth)) {
                        ChangelogCard(
                            release = release,
                            isExpanded = isExpanded,
                            isFirst = release == state.items.first(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (state.isLoadingMore) {
                    item {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // raw Material 3 by design: loading-more indicator, not full-page overlay
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        PosErrorWatcher(
            error = state.error,
            title = str(ChangelogKeys.errorLoadFailed),
            onClear = viewModel::clearError,
        )
    }
}

@Composable
private fun ChangelogCard(
    release: ChangelogDto.ResponseDto,
    isExpanded: Boolean,
    isFirst: Boolean,
) {
    val language = LocalAppLanguage.current
    val title = release.localizedTitle(language)
    val summary = release.localizedSummary(language)
    val highlights = release.localizedHighlights(language)
    val knownIssues = release.localizedKnownIssues(language)
    val releaseDate = release.releaseDate.take(10).formatShortDate(language)

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            if (isExpanded && isFirst) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier =
                            Modifier
                                .width(180.dp)
                                .padding(end = 16.dp),
                    ) {
                        ReleaseVersion(release.releaseVersion)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = str(ChangelogKeys.releaseDateLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = releaseDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ReleaseContent(title, summary, highlights, knownIssues)
                    }
                }
            } else {
                ReleaseVersion(release.releaseVersion)
                Text(
                    text = "${str(ChangelogKeys.releaseDateLabel)}: $releaseDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ReleaseContent(title, summary, highlights, knownIssues)
            }
        }
    }
}

/**
 * The release's version number, e.g. "Version 1.1.1". Titles name what a release brings, not which
 * one it is, so without this an operator cannot match an entry to the version their till or the
 * update screen reports. Shown in both card layouts.
 */
@Composable
private fun ReleaseVersion(version: String) {
    Text(
        text = str(ChangelogKeys.versionLabel, version),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ReleaseContent(
    title: String,
    summary: String,
    highlights: List<String>,
    knownIssues: List<String>,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (highlights.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = str(ChangelogKeys.highlightsLabel),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ReleaseNoteList(highlights)
    }

    if (knownIssues.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = str(ChangelogKeys.knownIssuesLabel),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ReleaseNoteList(knownIssues)
    }
}

/**
 * Renders one release-note array. Every entry is a bullet, except an entry that starts with
 * [ChangelogSection.HEADING_PREFIX]: that is a heading over the bullets that follow it, set in the
 * primary colour with breathing room above, so a release marker can lead with a group — v1.1.0
 * puts its TSE-signing and DSFinV-K items first and keeps fixes apart from new functions.
 */
@Composable
private fun ReleaseNoteList(entries: List<String>) {
    entries.forEach { entry ->
        if (ChangelogSection.isHeading(entry)) {
            Spacer(Modifier.height(PosSpacing.md))
            Text(
                text = ChangelogSection.headingText(entry),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
        } else {
            Text(
                text = "• $entry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Locale Adapters ───

private fun ChangelogDto.ResponseDto.localizedTitle(language: Language): String =
    when (language) {
        Language.GERMAN -> titleDe
        Language.CHINESE -> titleZh
        else -> titleEn
    }

private fun ChangelogDto.ResponseDto.localizedSummary(language: Language): String =
    when (language) {
        Language.GERMAN -> summaryDe
        Language.CHINESE -> summaryZh
        else -> summaryEn
    }

private fun ChangelogDto.ResponseDto.localizedHighlights(language: Language): List<String> =
    when (language) {
        Language.GERMAN -> highlightsDe
        Language.CHINESE -> highlightsZh
        else -> highlightsEn
    }

private fun ChangelogDto.ResponseDto.localizedKnownIssues(language: Language): List<String> =
    when (language) {
        Language.GERMAN -> knownIssuesDe
        Language.CHINESE -> knownIssuesZh
        else -> knownIssuesEn
    }
