package org.example.project.admin.features.staff.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.canEditStaffRecords
import org.example.project.admin.core.auth.canGrantRole
import org.example.project.admin.core.auth.canListPermissionGroups
import org.example.project.admin.core.auth.canResetStaffCredentials
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.components.*
import org.example.project.admin.features.staff.presentation.hr.HrProfileState
import org.example.project.admin.features.staff.presentation.hr.HrProfileViewModel
import org.example.project.admin.features.staff.presentation.hr.hrDisplayName
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosSecondaryButton
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StaffScreen(viewModel: StaffViewModel = koinViewModel()) {
    val policy = LocalAccessPolicy.current
    // NEW-22: the group list is only requested by a caller the server would answer (PERMISSION_ADMIN).
    val mayListGroups = canListPermissionGroups(policy)
    LaunchedEffect(mayListGroups) { viewModel.refresh(mayListGroups) }
    // EMPLOYEE_WRITE is what the server asks of create/edit/deactivate/schedule on another employee;
    // the role-hierarchy guards (canGrantRole, canDeactivate, canChangeRole) stay on top of it.
    val canManage = canEditStaffRecords(policy)
    val canResetCredentials = canResetStaffCredentials(policy)
    var pendingDeactivate by remember { mutableStateOf<EmployeeDto.EmployeeSummaryResponseDto?>(null) }

    val currentError =
        when (val s = viewModel.state) {
            is StaffState.Error -> s.error
            is StaffState.Data -> s.error
            else -> null
        }
    PosErrorWatcher(
        error = currentError,
        title = str(AdminStringKeys.StaffTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.refresh() },
    )

    Box(Modifier.fillMaxSize()) {
        when (val s = viewModel.state) {
            is StaffState.Loading ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
            is StaffState.Error ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            str(AdminStringKeys.StaffEmpty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        PosSecondaryButton(text = str(AdminStringKeys.StaffRetry), onClick = { viewModel.refresh() })
                    }
                }
            is StaffState.Data -> {
                val navigator = rememberListDetailPaneScaffoldNavigator<String>()
                val scope = rememberCoroutineScope()

                Scaffold(
                    topBar = {
                        // The shell's phone top bar already names the screen; the add action
                        // stays reachable through the directory's own button.
                        if (!LocalWindowSizeClass.current.isCompact()) {
                            TopAppBar(
                                title = {
                                    PosMarqueeText(
                                        text = str(EmployeeHubKeys.hubTitle),
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                actions = {
                                    if (canManage) {
                                        // raw Material 3: TextButton in TopAppBar actions slot
                                        TextButton(onClick = { viewModel.openCreate() }) {
                                            Icon(Icons.Default.Add, str(EmployeeHubKeys.newEmployee), Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(str(EmployeeHubKeys.newEmployee))
                                        }
                                    }
                                },
                                colors =
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                            )
                        }
                    },
                ) { padding ->
                    ListDetailPaneScaffold(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        directive = navigator.scaffoldDirective,
                        value = navigator.scaffoldValue,
                        listPane = {
                            AnimatedPane {
                                StaffDirectory(
                                    employees = s.visible,
                                    selectedId = s.selectedId,
                                    search = s.search,
                                    showInactive = s.showInactive,
                                    canManage = canManage,
                                    onSearch = viewModel::onSearch,
                                    onToggleInactive = viewModel::toggleShowInactive,
                                    onSelect = { id ->
                                        viewModel.select(id)
                                        scope.launch {
                                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                                        }
                                    },
                                    onAdd = viewModel::openCreate,
                                    onResetPassword = viewModel::openResetPassword,
                                    onResetPin = viewModel::openResetPin,
                                )
                            }
                        },
                        detailPane = {
                            AnimatedPane {
                                val sel = s.selected
                                if (sel == null) {
                                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                                        Text(
                                            str(AdminStringKeys.StaffSelectPrompt),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    val selSummary = s.all.firstOrNull { it.id == sel.id }
                                    // ADMIN records are shown read-only: no edit, role, group, credential,
                                    // deactivation or schedule control (decision D5; the server refuses too).
                                    val recordCanManage = canManage && !isReadOnlyStaffTarget(sel.role)
                                    val recordCanResetCredentials = canResetCredentials && !isReadOnlyStaffTarget(sel.role)
                                    // Reads what the HR section already loaded for this employee; never
                                    // triggers a request of its own, so the directory name shows until it has.
                                    val hrState = koinViewModel<HrProfileViewModel>().state
                                    val hrName =
                                        (hrState as? HrProfileState.Data)
                                            ?.takeIf { it.employeeId == sel.id }
                                            ?.info
                                            .hrDisplayName()
                                    val shownName = hrName ?: selSummary?.displayName?.takeIf { it.isNotBlank() } ?: sel.username
                                    Column(Modifier.fillMaxSize()) {
                                        EmployeeDetailHeader(
                                            employee = sel,
                                            displayName = selSummary?.displayName,
                                            hrName = hrName,
                                            canManage = recordCanManage,
                                            canResetCredentials = recordCanResetCredentials,
                                            canDeactivate = selSummary != null && viewModel.canDeactivate(selSummary),
                                            onResetPassword = { viewModel.openResetPassword(sel.id) },
                                            onResetPin = { viewModel.openResetPin(sel.id) },
                                            onDeactivate = { selSummary?.let { pendingDeactivate = it } },
                                            // canNavigateBack() is true only once the scaffold has
                                            // collapsed and the directory is off screen.
                                            onBack =
                                                if (navigator.canNavigateBack()) {
                                                    { scope.launch { navigator.navigateBack() } }
                                                } else {
                                                    null
                                                },
                                        )

                                        val detailTabs = DetailTab.entries
                                        val detailIndex = detailTabs.indexOf(s.detailTab)
                                        PrimaryTabRow(selectedTabIndex = detailIndex) {
                                            detailTabs.forEachIndexed { i, tab ->
                                                Tab(
                                                    selected = detailIndex == i,
                                                    onClick = { viewModel.selectDetailTab(tab) },
                                                    text = {
                                                        Text(
                                                            when (tab) {
                                                                DetailTab.OVERVIEW -> str(EmployeeHubKeys.overviewTab)
                                                                DetailTab.SCHEDULE -> str(EmployeeHubKeys.scheduleTab)
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        }

                                        when (s.detailTab) {
                                            DetailTab.OVERVIEW ->
                                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                                    EmployeeProfileSection(
                                                        employee = sel,
                                                        canManage = recordCanManage,
                                                        groups = s.groups,
                                                        canAssignGroup = viewModel.canAssignGroup(sel.id),
                                                        onEdit = viewModel::openEditSelected,
                                                        onAssignGroup = viewModel::openGroupPicker,
                                                    )
                                                    HrProfileSection(
                                                        employeeId = sel.id,
                                                        canManage = recordCanManage,
                                                        employeeName = shownName,
                                                    )
                                                }
                                            DetailTab.SCHEDULE ->
                                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                                    ScheduleSection(employeeId = sel.id, employeeName = shownName, canManage = recordCanManage)
                                                }
                                        }
                                    }
                                }
                            }
                        },
                    )
                    // NEW-08: the scaffold moves focus into the pane it shows (adaptive-layout's
                    // ThreePaneScaffold requests it on every destination change), which focused the
                    // first staff card and drew it tinted as if selected. A mouse console wants no
                    // focus there; Tab still enters the list.
                    val focusManager = LocalFocusManager.current
                    LaunchedEffect(navigator.scaffoldValue) { focusManager.clearFocus() }
                }
            }
        }

        // Dialogs (preserved from current StaffScreen)
        viewModel.editor?.let { e ->
            val summary = (viewModel.state as? StaffState.Data)?.all?.firstOrNull { it.id == e.id }
            // Someone whose current role the actor could not grant is also out of reach: the server
            // refuses moving them off it just as it refuses moving anyone onto it.
            val outranksActor = summary != null && !canGrantRole(policy, summary.role)
            EmployeeEditorDialog(
                editor = e,
                roleChangeBlocked =
                    !e.isCreate && (summary == null || outranksActor || !viewModel.canChangeRole(summary, e.role)),
                canEditAdministrativeFields = canEditStaffRecords(policy),
                canGrant = { role -> canGrantRole(policy, role) },
                onField = viewModel::onEditorField,
                onSave = viewModel::saveEditor,
                onCancel = viewModel::closeEditor,
                dirty = viewModel.editorDirty,
            )
        }
        (viewModel.state as? StaffState.Data)?.groupPicker?.let { picker ->
            PermissionGroupDialog(
                picker = picker,
                groups = (viewModel.state as? StaffState.Data)?.groups.orEmpty(),
                onPick = viewModel::pickGroup,
                onDismiss = viewModel::closeGroupPicker,
                onConfirm = viewModel::assignGroup,
            )
        }
        viewModel.credentialsEditor?.let { ce ->
            CredentialsDialog(
                editor = ce,
                employeeName =
                    (viewModel.state as? StaffState.Data)
                        ?.all
                        ?.firstOrNull { it.id == ce.employeeId }
                        ?.username
                        .orEmpty(),
                onField = viewModel::onCredentialField,
                onSave = viewModel::saveCredentials,
                onDismiss = viewModel::closeCredentials,
            )
        }
        pendingDeactivate?.let { emp ->
            DeleteConfirmDialog(
                entityType = str(AdminStringKeys.StaffEntityEmployee),
                entityName = emp.username,
                onConfirm = {
                    viewModel.deactivate(emp.id)
                    pendingDeactivate = null
                },
                onDismiss = { pendingDeactivate = null },
                consequence = str(AdminStringKeys.StaffDeactivateConfirm),
                confirmText = str(AdminStringKeys.StaffDeactivate),
                irreversible = false,
                titleKey = AdminStringKeys.DeactivateConfirmNamedTitle,
            )
        }
    }
}
