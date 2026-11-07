package org.example.project.ui.screens.reservation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.components.picker.CustomDatePickerDialog
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.reservation.presentation.state.DayStatus
import org.example.project.features.reservation.presentation.state.ReservationListEvent
import org.example.project.features.reservation.presentation.state.TableAssignmentEvent
import org.example.project.features.reservation.presentation.state.TimeSlotInfo
import org.example.project.features.reservation.presentation.viewmodels.ReservationListViewModel
import org.example.project.features.reservation.presentation.viewmodels.TableAssignmentViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTonalButton
import org.example.project.ui.screens.reservation.ReservationRulesScreen
import org.example.project.ui.screens.reservation.TableAssignmentSheet
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PosReservationScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReservationListViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current
    // RESERVATION_DELETE is the manager-level capability in this domain — RESERVATION_READ and
    // RESERVATION_WRITE are till-level, held by every station. It gates the same people the
    // `isManagerOrHigher()` it replaces did.
    val isManager = holds(Capability.RESERVATION_DELETE)
    val assignmentViewModel: TableAssignmentViewModel = koinViewModel()
    val assignmentState by assignmentViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        assignmentViewModel.onAssignmentChanged = {
            viewModel.onEvent(ReservationListEvent.Refresh)
        }
    }

    var showRulesScreen by remember { mutableStateOf(false) }
    var editingReservation by remember { mutableStateOf<ReservationDto.ResponseDto?>(null) }
    var showSearchBar by remember { mutableStateOf(false) }
    var selectedReservation by remember { mutableStateOf<ReservationDto.ResponseDto?>(null) }
    var selectedSlotIndex by remember { mutableStateOf(0) }

    val reservationTitle = str(ReservationKeys.title)

    PosErrorWatcher(
        error = uiState.error,
        title = reservationTitle,
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.onEvent(ReservationListEvent.Refresh) },
    )

    // Success messages
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = reservationTitle,
        onClear = { viewModel.clearSuccessMessage() },
    )

    // Keep selected reservation in sync with data refreshes
    LaunchedEffect(uiState.reservations, selectedReservation) {
        selectedReservation?.let { selected ->
            val updated = uiState.reservations.find { it.id == selected.id }
            if (updated != null && updated != selected) {
                selectedReservation = updated
            }
        }
    }

    if (showRulesScreen) {
        ReservationRulesScreen(
            onNavigateBack = { showRulesScreen = false },
        )
        return
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scaffoldScope = rememberCoroutineScope()
    BackHandler(navigator.canNavigateBack()) { scaffoldScope.launch { navigator.navigateBack() } }

    val windowSizeForFab = LocalWindowSizeClass.current
    val isCompactFab = windowSizeForFab.isCompact()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(CommonKeys.back),
                        onClick = onNavigateBack,
                    )
                },
                title = { PosMarqueeText(text = str(ReservationKeys.title)) },
                actions = {
                    PosIconButton(
                        icon = if (showSearchBar) Icons.Default.SearchOff else Icons.Default.Search,
                        contentDescription = str(ReservationKeys.searchReservations),
                        onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) {
                                viewModel.onEvent(ReservationListEvent.ClearSearch)
                            }
                        },
                    )
                    PosIconButton(
                        icon = Icons.Default.Settings,
                        contentDescription = str(ReservationKeys.rules),
                        onClick = { showRulesScreen = true },
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        floatingActionButton = {
            if (isCompactFab) {
                FloatingActionButton(
                    onClick = { viewModel.onEvent(ReservationListEvent.ShowCreateDialog) },
                ) {
                    Icon(Icons.Default.Add, contentDescription = str(ReservationKeys.addReservation))
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.onEvent(ReservationListEvent.ShowCreateDialog) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(str(ReservationKeys.addReservation)) },
                )
            }
        },
    ) { paddingValues ->
        val isSearchActive = showSearchBar && uiState.searchQuery.isNotBlank()
        val isLoadingAny = uiState.isLoading || uiState.isSearching

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Search bar
            if (showSearchBar) {
                PosTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onEvent(ReservationListEvent.SearchQueryChanged(it)) },
                    label = "",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = str(ReservationKeys.searchHint),
                    leadingIcon = Icons.Default.Search,
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            PosIconButton(
                                icon = Icons.Default.SearchOff,
                                contentDescription = null,
                                onClick = { viewModel.onEvent(ReservationListEvent.ClearSearch) },
                            )
                        }
                    },
                    singleLine = true,
                )
            }

            if (!isSearchActive) {
                // Date navigation
                DateNav(
                    dateString = uiState.selectedDate,
                    onDateChanged = { viewModel.onEvent(ReservationListEvent.DateChanged(it)) },
                )

                // Override reason badge
                uiState.overrideReason?.let { reason ->
                    androidx.compose.material3.AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                str(ReservationKeys.overrideBadge, reason),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                    )
                }

                // Time slot chips (for Open day status with multiple slots)
                val dayStatus = uiState.dayStatus
                if (dayStatus is DayStatus.Open && dayStatus.slots.size > 1) {
                    TimeSlotChips(
                        slots = dayStatus.slots,
                        selectedIndex = selectedSlotIndex,
                        onSlotSelected = { selectedSlotIndex = it },
                    )
                }
            }

            // Loading indicator
            if (isLoadingAny) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                value = navigator.scaffoldValue,
                listPane = {
                    AnimatedPane {
                        UnifiedReservationListContent(
                            uiState = uiState,
                            isSearchActive = isSearchActive,
                            isLoadingAny = isLoadingAny,
                            isManager = isManager,
                            selectedSlotIndex = selectedSlotIndex,
                            selectedReservation = selectedReservation,
                            onReservationClicked = { reservation ->
                                selectedReservation = reservation
                                scaffoldScope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, reservation.id) }
                            },
                            onStatusChange = { id, status -> viewModel.onEvent(ReservationListEvent.UpdateStatus(id, status)) },
                            onEdit = { reservation ->
                                editingReservation = reservation
                                viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                            },
                            onDelete = { id -> viewModel.onEvent(ReservationListEvent.DeleteReservation(id)) },
                            onOpenAssignment = { reservation -> viewModel.onEvent(ReservationListEvent.OpenAssignmentSheet(reservation)) },
                            onAutoAssign = { id -> viewModel.onEvent(ReservationListEvent.QuickAutoAssign(id)) },
                            onBulkAssign = { slotIdx -> viewModel.onEvent(ReservationListEvent.BulkAutoAssign(slotIdx)) },
                            isBulkAssigning = uiState.isBulkAssigning,
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        PosReservationDetailPanel(
                            reservation = selectedReservation,
                            isManager = isManager,
                            onEdit = {
                                selectedReservation?.let {
                                    editingReservation = it
                                    viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                                }
                            },
                            onDelete = { selectedReservation?.let { viewModel.onEvent(ReservationListEvent.DeleteReservation(it.id)) } },
                            onStatusChange = { status ->
                                selectedReservation?.let { viewModel.onEvent(ReservationListEvent.UpdateStatus(it.id, status)) }
                            },
                            onOpenAssignment = {
                                selectedReservation?.let {
                                    viewModel.onEvent(
                                        ReservationListEvent.OpenAssignmentSheet(it),
                                    )
                                }
                            },
                            onAutoAssign = { selectedReservation?.let { viewModel.onEvent(ReservationListEvent.QuickAutoAssign(it.id)) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
            )
        }
    }

    // Create/Edit dialog
    if (uiState.showCreateDialog) {
        PosReservationFormDialog(
            initialDate = uiState.selectedDate,
            editReservation = editingReservation,
            onDismiss = {
                viewModel.onEvent(ReservationListEvent.HideCreateDialog)
                editingReservation = null
            },
            onSaved = {
                viewModel.onEvent(ReservationListEvent.HideCreateDialog)
                editingReservation = null
                viewModel.onEvent(ReservationListEvent.Refresh)
            },
        )
    }

    // Table Assignment Sheet
    val reservationForAssignment = uiState.reservationForAssignment
    if (reservationForAssignment != null) {
        LaunchedEffect(reservationForAssignment.id) {
            assignmentViewModel.onEvent(TableAssignmentEvent.Open(reservationForAssignment))
        }

        TableAssignmentSheet(
            uiState = assignmentState,
            onEvent = assignmentViewModel::onEvent,
            onDismiss = {
                assignmentViewModel.onEvent(TableAssignmentEvent.Close)
                viewModel.onEvent(ReservationListEvent.CloseAssignmentSheet)
            },
        )
    }
}

@Composable
private fun TimeSlotChips(
    slots: List<TimeSlotInfo>,
    selectedIndex: Int,
    onSlotSelected: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(slots.size) { index ->
            val slot = slots[index]
            FilterChip(
                selected = selectedIndex == index,
                onClick = { onSlotSelected(index) },
                label = {
                    Text("${slot.openTime.take(5)}-${slot.closeTime.take(5)}")
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        }
    }
}

// ------ Date Navigation ------

@Composable
private fun DateNav(
    dateString: String,
    onDateChanged: (String) -> Unit,
) {
    val date =
        try {
            LocalDate.parse(dateString)
        } catch (_: Exception) {
            return
        }

    var showDatePicker by remember { mutableStateOf(false) }
    val language = LocalLanguageManager.current.currentLanguage.value

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = PosSpacing.sm),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = str(ReservationKeys.previousDay),
            onClick = { onDateChanged(date.minus(1, DateTimeUnit.DAY).toString()) },
        )

        Text(
            text = formatDate(date),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { showDatePicker = true },
        )

        PosIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = str(ReservationKeys.nextDay),
            onClick = { onDateChanged(date.plus(1, DateTimeUnit.DAY).toString()) },
        )
    }

    if (showDatePicker) {
        CustomDatePickerDialog(
            onDismiss = { showDatePicker = false },
            onDateSelected = { selectedDate ->
                onDateChanged(selectedDate.toString())
                showDatePicker = false
            },
            language = language,
            initialDate = date,
        )
    }
}

@Composable
private fun formatDate(date: LocalDate): String {
    val shortDay =
        when (date.dayOfWeek) {
            kotlinx.datetime.DayOfWeek.MONDAY -> str(ReservationKeys.dayMon)
            kotlinx.datetime.DayOfWeek.TUESDAY -> str(ReservationKeys.dayTue)
            kotlinx.datetime.DayOfWeek.WEDNESDAY -> str(ReservationKeys.dayWed)
            kotlinx.datetime.DayOfWeek.THURSDAY -> str(ReservationKeys.dayThu)
            kotlinx.datetime.DayOfWeek.FRIDAY -> str(ReservationKeys.dayFri)
            kotlinx.datetime.DayOfWeek.SATURDAY -> str(ReservationKeys.daySat)
            kotlinx.datetime.DayOfWeek.SUNDAY -> str(ReservationKeys.daySun)
        }
    val shortMonth =
        when (date.month) {
            kotlinx.datetime.Month.JANUARY -> str(ReservationKeys.monthJan)
            kotlinx.datetime.Month.FEBRUARY -> str(ReservationKeys.monthFeb)
            kotlinx.datetime.Month.MARCH -> str(ReservationKeys.monthMar)
            kotlinx.datetime.Month.APRIL -> str(ReservationKeys.monthApr)
            kotlinx.datetime.Month.MAY -> str(ReservationKeys.monthMay)
            kotlinx.datetime.Month.JUNE -> str(ReservationKeys.monthJun)
            kotlinx.datetime.Month.JULY -> str(ReservationKeys.monthJul)
            kotlinx.datetime.Month.AUGUST -> str(ReservationKeys.monthAug)
            kotlinx.datetime.Month.SEPTEMBER -> str(ReservationKeys.monthSep)
            kotlinx.datetime.Month.OCTOBER -> str(ReservationKeys.monthOct)
            kotlinx.datetime.Month.NOVEMBER -> str(ReservationKeys.monthNov)
            kotlinx.datetime.Month.DECEMBER -> str(ReservationKeys.monthDec)
        }
    return str(ReservationKeys.dateFormat, shortDay, shortMonth, date.day, date.year)
}

// ------ Shared helpers ------

@Composable
private fun ReservationListHeader(
    count: Int,
    isManager: Boolean,
    isBulkAssigning: Boolean,
    hasUnassignedConfirmed: Boolean,
    onBulkAssign: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = PosSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                if (count == 1) {
                    str(ReservationKeys.reservationCountOne)
                } else {
                    str(ReservationKeys.reservationCount, count)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isManager) {
            PosTonalButton(
                text = str(ReservationKeys.bulkAutoAssign),
                icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                isLoading = isBulkAssigning,
                onClick = onBulkAssign,
                enabled = !isBulkAssigning && hasUnassignedConfirmed,
            )
        }
    }
}

@Composable
private fun EmptyState(isSearchActive: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isSearchActive) Icons.Default.SearchOff else Icons.Default.EventBusy,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isSearchActive) str(ReservationKeys.noSearchResults) else str(ReservationKeys.noReservationsInSlot),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun UnifiedReservationListContent(
    uiState: org.example.project.features.reservation.presentation.state.ReservationListState,
    isSearchActive: Boolean,
    isLoadingAny: Boolean,
    isManager: Boolean,
    selectedSlotIndex: Int,
    selectedReservation: ReservationDto.ResponseDto?,
    onReservationClicked: (ReservationDto.ResponseDto) -> Unit,
    onStatusChange: (Long, ReservationStatus) -> Unit,
    onEdit: (ReservationDto.ResponseDto) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenAssignment: (ReservationDto.ResponseDto) -> Unit,
    onAutoAssign: (Long) -> Unit,
    onBulkAssign: (Int) -> Unit,
    isBulkAssigning: Boolean,
) {
    val reservations =
        resolveDisplayReservations(
            uiState = uiState,
            isSearchActive = isSearchActive,
            selectedSlotIndex = selectedSlotIndex,
        )

    Column(
        modifier = Modifier.fillMaxHeight(),
    ) {
        ReservationListHeader(
            count = reservations.size,
            isManager = isManager,
            isBulkAssigning = isBulkAssigning,
            hasUnassignedConfirmed =
                reservations.any {
                    it.status == ReservationStatus.CONFIRMED && it.tableAssignments.isEmpty()
                },
            onBulkAssign = { onBulkAssign(selectedSlotIndex) },
        )

        if (reservations.isEmpty() && !isLoadingAny) {
            EmptyState(isSearchActive)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = reservations.sortedBy { it.reservationTime },
                    key = { it.id },
                ) { reservation ->
                    PosReservationCard(
                        reservation = reservation,
                        isSelected = selectedReservation?.id == reservation.id,
                        onClick = { onReservationClicked(reservation) },
                    )
                }
            }
        }
    }
}

/**
 * Resolves the list of reservations to display based on search state and day status.
 */
private fun resolveDisplayReservations(
    uiState: org.example.project.features.reservation.presentation.state.ReservationListState,
    isSearchActive: Boolean,
    selectedSlotIndex: Int,
): List<ReservationDto.ResponseDto> {
    if (isSearchActive) return uiState.searchResults

    return when (val dayStatus = uiState.dayStatus) {
        is DayStatus.Closed -> emptyList()
        is DayStatus.AlwaysOpen -> dayStatus.reservations
        is DayStatus.Open -> {
            dayStatus.slots.getOrNull(selectedSlotIndex)?.reservations ?: emptyList()
        }
    }
}
