package org.example.project.features.scheduler.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.scheduler.presentation.components.JobExecutionsDrawer
import org.example.project.features.scheduler.presentation.components.ScheduledJobRow
import org.example.project.features.scheduler.presentation.viewmodels.ScheduledJobsViewModel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param showTitle only affects the non-compact layout: false drops the in-body title for hosts that
 * already show one (the adminApp phone top bar). Default keeps the title.
 * @param touchTargetSize null keeps the row/drawer icon buttons at their compact sizes; a host that
 * needs finger-sized targets (adminApp) passes 48.dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledJobsScreen(
    isCompact: Boolean = false,
    showTitle: Boolean = true,
    touchTargetSize: Dp? = null,
    modifier: Modifier = Modifier,
    viewModel: ScheduledJobsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()

    val screenTitle = str(SchedulerKeys.screenTitle)

    var showHistory by remember { mutableStateOf(false) }

    // The typed UiError goes to the shared watcher, not the string overload: only the typed path
    // attaches the error to the notification, which is what lets the host render the traceId, the
    // server's detail and the error code — and what records the failure with ClientErrorReporter.
    //
    // No displayMode override: the bus escalates 5xx and validation failures to the dialog and
    // toasts the routine rest. This screen lists and triggers background jobs; a rejected
    // "run now" is routine operational feedback with nothing fiscal riding on it, and the job's
    // own outcome is visible in the list afterwards either way.
    PosErrorWatcher(
        error = error,
        title = screenTitle,
        onClear = viewModel::clearError,
    )

    // History drawer
    if (showHistory) {
        state.selectedJobDetail?.let { detail ->
            JobExecutionsDrawer(
                detail = detail,
                onRunNow = {
                    viewModel.runNow(detail.job.id)
                },
                onLoadMore = {
                    viewModel.loadMoreExecutions(detail.job.id)
                },
                touchTargetSize = touchTargetSize,
                onDismiss = {
                    viewModel.clearSelectedDetail()
                    showHistory = false
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isCompact) {
                TopAppBar(
                    title = {
                        PosMarqueeText(
                            text = str(SchedulerKeys.screenTitle),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(if (isCompact) 0.dp else 16.dp),
        ) {
            if (!isCompact && showTitle) {
                Text(
                    text = str(SchedulerKeys.screenTitle),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                )
            }

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.jobs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = str(SchedulerKeys.emptyState),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = if (isCompact) 12.dp else 0.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.jobs, key = { it.id }) { dto ->
                            ScheduledJobRow(
                                dto = dto,
                                onRunNow = { viewModel.runNow(dto.id) },
                                onViewHistory = {
                                    viewModel.loadDetail(dto.id)
                                    showHistory = true
                                },
                                touchTargetSize = touchTargetSize,
                            )
                        }
                    }
                }
            }
        }
    }
}
