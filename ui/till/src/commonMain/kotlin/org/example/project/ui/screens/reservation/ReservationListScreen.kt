package org.example.project.ui.screens.reservation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.reservation.presentation.screens.ReservationFormScreen
import org.example.project.features.reservation.presentation.state.DayStatus
import org.example.project.features.reservation.presentation.state.ReservationListEvent
import org.example.project.features.reservation.presentation.state.TableAssignmentEvent
import org.example.project.features.reservation.presentation.viewmodels.ReservationListViewModel
import org.example.project.features.reservation.presentation.viewmodels.TableAssignmentViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTonalButton
import org.example.project.ui.screens.reservation.ReservationRulesScreen
import org.example.project.ui.screens.reservation.TableAssignmentSheet
import org.example.project.ui.theme.PosTheme
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationListScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReservationListViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
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

    if (showRulesScreen) {
        ReservationRulesScreen(
            onNavigateBack = { showRulesScreen = false },
        )
        return
    }

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
                title = { PosMarqueeText(str(ReservationKeys.title)) },
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
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onEvent(ReservationListEvent.ShowCreateDialog) },
            ) {
                Icon(Icons.Default.Add, contentDescription = str(ReservationKeys.addReservation))
            }
        },
    ) { paddingValues ->
        val isSearchActive = showSearchBar && uiState.searchQuery.isNotBlank()
        val displayReservations = if (isSearchActive) uiState.searchResults else uiState.reservations
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
                                icon = Icons.Default.Clear,
                                contentDescription = null,
                                onClick = { viewModel.onEvent(ReservationListEvent.ClearSearch) },
                            )
                        }
                    },
                    singleLine = true,
                )
            }

            // Date picker row (hidden when search is active)
            if (!isSearchActive) {
                DateNavigationRow(
                    dateString = uiState.selectedDate,
                    onDateChanged = { viewModel.onEvent(ReservationListEvent.DateChanged(it)) },
                )

                // Override reason badge
                uiState.overrideReason?.let { reason ->
                    AssistChip(
                        onClick = {},
                        label = { Text(str(ReservationKeys.overrideBadge, reason), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                    )
                }
            }

            // Loading indicator
            if (isLoadingAny) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Content: search results (flat list) or day-status-based slot view
            if (isSearchActive) {
                // Search mode — flat list, no slot grouping
                if (displayReservations.isEmpty() && !isLoadingAny) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                str(ReservationKeys.noSearchResults),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = displayReservations.sortedBy { it.reservationTime },
                            key = { it.id },
                        ) { reservation ->
                            ReservationCard(
                                reservation = reservation,
                                isManager = isManager,
                                onStatusChange = { status ->
                                    viewModel.onEvent(ReservationListEvent.UpdateStatus(reservation.id, status))
                                },
                                onEdit = {
                                    editingReservation = reservation
                                    viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                                },
                                onDelete = {
                                    viewModel.onEvent(ReservationListEvent.DeleteReservation(reservation.id))
                                },
                                onOpenAssignment = {
                                    viewModel.onEvent(ReservationListEvent.OpenAssignmentSheet(reservation))
                                },
                                onAutoAssign = {
                                    viewModel.onEvent(ReservationListEvent.QuickAutoAssign(reservation.id))
                                },
                            )
                        }
                    }
                }
            } else {
                // Day status based view
                val dayStatus = uiState.dayStatus
                when (dayStatus) {
                    is DayStatus.Closed -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Default.EventBusy,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text =
                                    if (dayStatus.reason !=
                                        null
                                    ) {
                                        str(ReservationKeys.closedDayWithReason, dayStatus.reason)
                                    } else {
                                        str(ReservationKeys.closedDay)
                                    },
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    is DayStatus.AlwaysOpen -> {
                        SlotPanel(
                            title = str(ReservationKeys.allDay),
                            reservations = dayStatus.reservations,
                            slotIndex = 0,
                            onEvent = viewModel::onEvent,
                            isManager = isManager,
                            isBulkAssigning = uiState.isBulkAssigning,
                            onEditReservation = { reservation ->
                                editingReservation = reservation
                                viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is DayStatus.Open -> {
                        if (windowSize.isCompact()) {
                            // Compact: HorizontalPager with tabs
                            val scope = rememberCoroutineScope()
                            val pagerState = rememberPagerState(pageCount = { dayStatus.slots.size })

                            TabRow(selectedTabIndex = pagerState.currentPage) {
                                dayStatus.slots.forEachIndexed { index, slot ->
                                    Tab(
                                        selected = pagerState.currentPage == index,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                        text = { Text("${slot.openTime.take(5)}–${slot.closeTime.take(5)}") },
                                    )
                                }
                            }

                            HorizontalPager(state = pagerState) { page ->
                                val slot = dayStatus.slots[page]
                                SlotPanel(
                                    title = "${slot.openTime.take(5)} – ${slot.closeTime.take(5)}",
                                    reservations = slot.reservations,
                                    slotIndex = page,
                                    onEvent = viewModel::onEvent,
                                    isManager = isManager,
                                    isBulkAssigning = uiState.isBulkAssigning,
                                    onEditReservation = { reservation ->
                                        editingReservation = reservation
                                        viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            // Expanded: side-by-side slots
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                                dayStatus.slots.forEachIndexed { index, slot ->
                                    SlotPanel(
                                        title = "${slot.openTime.take(5)} – ${slot.closeTime.take(5)}",
                                        reservations = slot.reservations,
                                        slotIndex = index,
                                        onEvent = viewModel::onEvent,
                                        isManager = isManager,
                                        isBulkAssigning = uiState.isBulkAssigning,
                                        onEditReservation = { reservation ->
                                            editingReservation = reservation
                                            viewModel.onEvent(ReservationListEvent.ShowCreateDialog)
                                        },
                                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create/Edit dialog
    if (uiState.showCreateDialog) {
        ReservationFormScreen(
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
private fun SlotPanel(
    title: String,
    reservations: List<ReservationDto.ResponseDto>,
    slotIndex: Int,
    onEvent: (ReservationListEvent) -> Unit,
    isManager: Boolean,
    isBulkAssigning: Boolean,
    onEditReservation: (ReservationDto.ResponseDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedReservations =
        remember(reservations) {
            reservations.sortedBy { it.reservationTime }
        }
    val hasUnassignedConfirmed =
        remember(reservations) {
            reservations.any {
                it.status == ReservationStatus.CONFIRMED && it.tableAssignments.isEmpty()
            }
        }

    Column(modifier = modifier) {
        // Header row with title and bulk auto-assign button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (reservations.size == 1) {
                            str(ReservationKeys.reservationCountOne)
                        } else {
                            str(ReservationKeys.reservationCount, reservations.size)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isManager) {
                PosTonalButton(
                    text = str(ReservationKeys.bulkAutoAssign),
                    icon = Icons.Default.PlaylistAddCheck,
                    isLoading = isBulkAssigning,
                    onClick = { onEvent(ReservationListEvent.BulkAutoAssign(slotIndex)) },
                    enabled = !isBulkAssigning && hasUnassignedConfirmed,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        if (sortedReservations.isEmpty()) {
            // Empty state
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.EventBusy,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        str(ReservationKeys.noReservationsInSlot),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = sortedReservations,
                    key = { it.id },
                ) { reservation ->
                    ReservationCard(
                        reservation = reservation,
                        isManager = isManager,
                        onStatusChange = { status ->
                            onEvent(ReservationListEvent.UpdateStatus(reservation.id, status))
                        },
                        onEdit = {
                            onEditReservation(reservation)
                        },
                        onDelete = {
                            onEvent(ReservationListEvent.DeleteReservation(reservation.id))
                        },
                        onOpenAssignment = {
                            onEvent(ReservationListEvent.OpenAssignmentSheet(reservation))
                        },
                        onAutoAssign = {
                            onEvent(ReservationListEvent.QuickAutoAssign(reservation.id))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateNavigationRow(
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = str(ReservationKeys.previousDay),
            onClick = { onDateChanged(date.minus(1, DateTimeUnit.DAY).toString()) },
        )

        Text(
            text = formatDisplayDate(date),
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
private fun formatDisplayDate(date: LocalDate): String {
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

@Composable
private fun ReservationCard(
    reservation: ReservationDto.ResponseDto,
    isManager: Boolean,
    onStatusChange: (ReservationStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenAssignment: () -> Unit,
    onAutoAssign: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
    ) {
        body {
            // Guest name and time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = reservation.guestName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = reservation.reservationTime.take(5),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Party size and status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Party size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.People,
                        contentDescription = str(ReservationKeys.partySize),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${reservation.partySize}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status chip
                StatusChip(reservation.status)
            }

            // Duration if present
            reservation.durationMinutes?.let { duration ->
                Text(
                    text = str(ReservationKeys.durationMinutes, duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Notes preview
            reservation.notes?.let { notes ->
                if (notes.isNotBlank()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Table assignment chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (reservation.tableAssignments.isEmpty()) {
                    Text(
                        text = str(ReservationKeys.noTablesAssigned),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    reservation.tableAssignments.forEach { assignment ->
                        AssistChip(
                            onClick = onOpenAssignment,
                            label = {
                                Text(
                                    "${assignment.tableNumber} (${assignment.seats})",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }
            }
        }

        trailing {
            // Actions
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PosIconButton(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = str(ReservationKeys.autoAssign),
                    onClick = onAutoAssign,
                )
                Box {
                    PosIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = str(CommonKeys.actions),
                        onClick = { showMenu = true },
                    )
                    PosDropdown(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (reservation.status == ReservationStatus.CONFIRMED) {
                            PosDropdownItem(
                                text = str(ReservationKeys.markSeated),
                                onClick = {
                                    showMenu = false
                                    onStatusChange(ReservationStatus.SEATED)
                                },
                            )
                        }
                        if (reservation.status == ReservationStatus.CONFIRMED) {
                            PosDropdownItem(
                                text = str(ReservationKeys.markNoShow),
                                onClick = {
                                    showMenu = false
                                    onStatusChange(ReservationStatus.NO_SHOW)
                                },
                            )
                        }
                        if (reservation.status == ReservationStatus.SEATED) {
                            PosDropdownItem(
                                text = str(ReservationKeys.markCompleted),
                                onClick = {
                                    showMenu = false
                                    onStatusChange(ReservationStatus.COMPLETED)
                                },
                            )
                        }
                        if (reservation.status == ReservationStatus.CONFIRMED ||
                            reservation.status == ReservationStatus.SEATED
                        ) {
                            PosDropdownItem(
                                text = str(CommonKeys.cancel),
                                onClick = {
                                    showMenu = false
                                    onStatusChange(ReservationStatus.CANCELLED)
                                },
                            )
                        }
                        PosDropdownItem(
                            text = str(CommonKeys.edit),
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                        )
                        if (isManager) {
                            PosDropdownItem(
                                text = str(CommonKeys.delete),
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ReservationStatus) {
    val containerColor =
        when (status) {
            ReservationStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
            ReservationStatus.SEATED -> PosTheme.colors.posAvailable
            ReservationStatus.COMPLETED -> MaterialTheme.colorScheme.outline
            ReservationStatus.CANCELLED -> MaterialTheme.colorScheme.outline
            ReservationStatus.NO_SHOW -> MaterialTheme.colorScheme.error
        }

    val label =
        when (status) {
            ReservationStatus.CONFIRMED -> str(ReservationKeys.statusConfirmed)
            ReservationStatus.SEATED -> str(ReservationKeys.statusSeated)
            ReservationStatus.COMPLETED -> str(ReservationKeys.statusCompleted)
            ReservationStatus.CANCELLED -> str(ReservationKeys.statusCancelled)
            ReservationStatus.NO_SHOW -> str(ReservationKeys.statusNoShow)
        }

    Surface(
        color = containerColor.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = containerColor,
        )
    }
}
