package org.example.project.ui.screens.employees.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.formatTimeHHmmss
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.employees.presentation.components.hub.EditProfilePanel
import org.example.project.features.employees.presentation.state.CashierProfileUiState
import org.example.project.features.employees.presentation.state.CredentialChangeMode
import org.example.project.features.employees.presentation.viewmodel.CashierProfileViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashierProfileView(onNavigateBack: () -> Unit) {
    // Resolve window size class from current context (no prop required)
    val windowSizeClass = LocalWindowSizeClass.current
    val viewModel: CashierProfileViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current
    val languageManager = LocalLanguageManager.current
    val profileTitle = str(EmployeeHubKeys.myProfile)

    val isCompact = windowSizeClass.isCompact()
    val isExpanded = windowSizeClass.isAtLeastExpanded()

    PosErrorWatcher(
        error = uiState.error,
        title = profileTitle,
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.loadProfile() },
    )

    PosSuccessWatcher(
        message = uiState.successMessage,
        title = profileTitle,
        onClear = { viewModel.clearSuccessMessage() },
    )

    uiState.credentialChangeMode?.let { mode ->
        uiState.employee?.let { employee ->
            CredentialChangeDialog(
                mode = mode,
                employeeUsername = employee.username,
                onChangePassword = { request -> viewModel.changePassword(request) },
                onChangePin = { newPin -> viewModel.changePin(newPin) },
                onDismiss = { viewModel.dismissDialog() },
            )
        }
    }

    if (isCompact) {
        CompactCashierProfileLayout(
            viewModel = viewModel,
            uiState = uiState,
            languageManager = languageManager,
            onNavigateBack = onNavigateBack,
        )
    } else if (isExpanded) {
        ExpandedCashierProfileLayout(
            viewModel = viewModel,
            uiState = uiState,
            languageManager = languageManager,
        )
    } else {
        ExpandedCashierProfileLayout(
            viewModel = viewModel,
            uiState = uiState,
            languageManager = languageManager,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactCashierProfileLayout(
    viewModel: CashierProfileViewModel,
    uiState: CashierProfileUiState,
    languageManager: org.example.project.core.utils.LanguageManager,
    onNavigateBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        PosMarqueeText(
                            text = str(EmployeeHubKeys.myProfile),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        PosNavIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            onClick = onNavigateBack,
                        )
                    },
                )
            },
        ) { padding ->
            CashierProfileContent(
                viewModel = viewModel,
                uiState = uiState,
                languageManager = languageManager,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }

        // Slide-in edit panel from right
        AnimatedVisibility(
            visible = uiState.showEditProfileDialog,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
            ) {
                EditProfilePanel(
                    currentInfo = uiState.employeeInfo,
                    onSave = { request -> viewModel.updateMyProfile(request) },
                    onDismiss = { viewModel.dismissDialog() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedCashierProfileLayout(
    viewModel: CashierProfileViewModel,
    uiState: CashierProfileUiState,
    languageManager: org.example.project.core.utils.LanguageManager,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PosMarqueeText(
                        text = str(EmployeeHubKeys.myProfile),
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Left: Profile content
            CashierProfileContent(
                viewModel = viewModel,
                uiState = uiState,
                languageManager = languageManager,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            )

            // Right: Edit panel (shown when editing)
            AnimatedVisibility(
                visible = uiState.showEditProfileDialog,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                VerticalDivider()
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.4f)
                            .fillMaxHeight(),
                    tonalElevation = 1.dp,
                ) {
                    EditProfilePanel(
                        currentInfo = uiState.employeeInfo,
                        onSave = { request -> viewModel.updateMyProfile(request) },
                        onDismiss = { viewModel.dismissDialog() },
                    )
                }
            }
        }
    }
}

@Composable
private fun CashierProfileContent(
    viewModel: CashierProfileViewModel,
    uiState: CashierProfileUiState,
    languageManager: org.example.project.core.utils.LanguageManager,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Loading indicator
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Hero Section
            item {
                CashierHeroSection(uiState)
            }

            // Action Buttons
            item {
                CashierActionButtons(
                    onEditProfile = { viewModel.showEditProfileDialog() },
                    onChangePassword = { viewModel.showCredentialDialog(CredentialChangeMode.PASSWORD) },
                    onChangePin = { viewModel.showCredentialDialog(CredentialChangeMode.PIN) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Personal Information Section
            item {
                CashierPersonalInfoSection(
                    uiState = uiState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // My Schedule Section
            item {
                CashierScheduleSection(
                    schedule = uiState.schedule,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Monthly Overview Section
            item {
                CashierMonthlyOverviewSection(
                    overview = uiState.overview,
                    onViewEntries = { viewModel.viewEntries() },
                    onSubmitMonth = { viewModel.submitMonth() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Time Entries (expanded when View Entries is clicked)
            if (uiState.showEntries) {
                item {
                    CashierEntriesSection(
                        entries = uiState.entries,
                        onHide = { viewModel.hideEntries() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // Preferences Section
            item {
                CashierPreferencesSection(
                    language = uiState.employee?.languagePreference ?: "de",
                    onLanguageChange = { langCode ->
                        viewModel.changeLanguage(langCode)
                        languageManager.setLanguage(Language.fromCode(langCode))
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CashierHeroSection(uiState: CashierProfileUiState) {
    val employee = uiState.employee
    val employeeInfo = uiState.employeeInfo

    val displayName =
        when {
            employeeInfo != null -> employeeInfo.fullName
            employee != null -> employee.username
            else -> "—"
        }
    val initials =
        displayName
            .split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Avatar circle
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Display name
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Role badge
        if (employee != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = localizedRoleName(employee.role),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = PosSpacing.xs),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Employee number
            Text(
                text = "#${employee.employeeNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CashierActionButtons(
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onChangePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PosSecondaryButton(
                text = str(EmployeeHubKeys.editProfile),
                onClick = onEditProfile,
                modifier = Modifier.weight(1f),
            )
            PosSecondaryButton(
                text = str(EmployeeHubKeys.resetPassword),
                onClick = onChangePassword,
                modifier = Modifier.weight(1f),
            )
        }
        PosButton(
            text = str(EmployeeHubKeys.changePin),
            onClick = onChangePin,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CashierPersonalInfoSection(
    uiState: CashierProfileUiState,
    modifier: Modifier = Modifier,
) {
    val employee = uiState.employee
    val info = uiState.employeeInfo

    ProfileSectionCard(title = str(EmployeeHubKeys.personalInformation), modifier = modifier) {
        ProfileInfoRow(label = str(EmployeeHubKeys.username), value = employee?.username)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ProfileInfoRow(label = str(EmployeeHubKeys.email), value = info?.email)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ProfileInfoRow(label = str(EmployeeHubKeys.phone), value = info?.phoneNumber)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ProfileInfoRow(
            label = str(EmployeeHubKeys.role),
            value = employee?.role?.let { localizedRoleName(it) },
            valueSuffix = str(EmployeeHubKeys.readOnly),
        )
    }
}

@Composable
private fun CashierScheduleSection(
    schedule: WorkScheduleDto.WeeklyScheduleResponseDto?,
    modifier: Modifier = Modifier,
) {
    ProfileSectionCard(title = str(EmployeeHubKeys.mySchedule), modifier = modifier) {
        if (schedule == null) {
            Text(
                text = str(EmployeeHubKeys.noScheduleConfigured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            val dayNames =
                mapOf(
                    1 to str(EmployeeHubKeys.monShort),
                    2 to str(EmployeeHubKeys.tueShort),
                    3 to str(EmployeeHubKeys.wedShort),
                    4 to str(EmployeeHubKeys.thuShort),
                    5 to str(EmployeeHubKeys.friShort),
                    6 to str(EmployeeHubKeys.satShort),
                    7 to str(EmployeeHubKeys.sunShort),
                )

            // Show all 7 days
            (1..7).forEach { dayOfWeek ->
                val daySchedule = schedule.days.find { it.dayOfWeek == dayOfWeek }
                val dayName = dayNames[dayOfWeek] ?: "Day $dayOfWeek"
                val offText = str(EmployeeHubKeys.off)
                val shiftsText =
                    if (daySchedule == null || daySchedule.shifts.isEmpty()) {
                        offText
                    } else {
                        daySchedule.shifts.joinToString(", ") { "${it.startTime} – ${it.endTime}" }
                    }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = shiftsText,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (shiftsText == offText) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Contracted hours
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = str(EmployeeHubKeys.contractedHours),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hoursText =
                    schedule.contractedWeeklyHours?.let { str(EmployeeHubKeys.hoursPerWeek, it) }
                        ?: str(EmployeeHubKeys.hoursPerWeek, schedule.scheduledWeeklyMinutes / 60)
                Text(
                    text = hoursText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CashierMonthlyOverviewSection(
    overview: TimeEntryDto.MonthlyOverviewDto?,
    onViewEntries: () -> Unit,
    onSubmitMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val monthlyTitle =
        if (overview !=
            null
        ) {
            "${monthNameLocalized(overview.month)} ${overview.year} ${str(EmployeeHubKeys.monthlyOverview)}"
        } else {
            str(EmployeeHubKeys.monthlyOverview)
        }
    ProfileSectionCard(title = monthlyTitle, modifier = modifier) {
        if (overview == null) {
            Text(
                text = str(EmployeeHubKeys.noMonthlyData),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            val actualHours = overview.actualWorkMinutes / 60.0
            val targetHours = (overview.targetMonthlyMinutes ?: 0) / 60.0
            val remainingMinutes = (overview.targetMonthlyMinutes ?: 0) - overview.actualWorkMinutes
            val remainingHours = remainingMinutes / 60.0
            val isOvertime = remainingMinutes < 0

            // Stats row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                MonthlyStatItem(
                    value = formatHours(actualHours),
                    label = str(EmployeeHubKeys.actual),
                    valueColor = MaterialTheme.colorScheme.primary,
                )
                MonthlyStatItem(
                    value = formatHours(targetHours),
                    label = str(EmployeeHubKeys.target),
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonthlyStatItem(
                    value = if (isOvertime) "+${formatHours(-remainingHours)}" else "-${formatHours(remainingHours)}",
                    label = str(EmployeeHubKeys.remaining),
                    valueColor =
                        if (isOvertime) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                MonthlyStatItem(
                    value = "${overview.workingDays}",
                    label = str(EmployeeHubKeys.days),
                    valueColor = MaterialTheme.colorScheme.secondary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            val isDraft = overview.status == "DRAFT" || overview.status == "NO_SUBMISSION"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(
                    text = str(EmployeeHubKeys.viewEntries),
                    onClick = onViewEntries,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp),
                )
                // raw Material 3 by design: Button with tertiary color — tertiary not exposed by PosButton colors
                Button(
                    onClick = onSubmitMonth,
                    enabled = isDraft,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                ) {
                    Text(
                        text = if (isDraft) str(EmployeeHubKeys.submitMonth) else str(EmployeeHubKeys.submitted),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyStatItem(
    value: String,
    label: String,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashierPreferencesSection(
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val languageOptions = Language.entries
    val currentLanguage =
        remember(language) {
            languageOptions.find { it.code == language.lowercase() } ?: Language.GERMAN
        }

    ProfileSectionCard(title = str(EmployeeHubKeys.preferences), modifier = modifier) {
        Column {
            Text(
                text = str(EmployeeHubKeys.language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box {
                PosPickerField(
                    value = currentLanguage.nativeName,
                    onClick = { expanded = true },
                    label = "",
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    languageOptions.forEach { lang ->
                        PosDropdownItem(
                            text = lang.nativeName,
                            selected = lang.code == currentLanguage.code,
                            onClick = {
                                if (lang.code != language.lowercase()) {
                                    onLanguageChange(lang.code)
                                }
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = PosSpacing.sm),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    label: String,
    value: String?,
    valueSuffix: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = PosSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color =
                        if (value != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (valueSuffix != null) {
                    Text(
                        text = valueSuffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CashierEntriesSection(
    entries: List<TimeEntryDto.TimeEntrySummaryDto>,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalLanguageManager.current.currentLanguage.value
    ProfileSectionCard(title = str(EmployeeHubKeys.timeEntries), modifier = modifier) {
        if (entries.isEmpty()) {
            Text(
                text = str(EmployeeHubKeys.noTimeEntries),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            entries.forEachIndexed { index, entry ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = PosSpacing.sm),
                ) {
                    // Date on its own line
                    Text(
                        text = entry.date.formatReadableDateTime(language, false),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // Time range and hours on second line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${entry.clockIn.formatTimeHHmmss()} – ${entry.clockOut?.formatTimeHHmmss() ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatHours(entry.netWorkMinutes / 60.0),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        PosTextButton(
            text = str(EmployeeHubKeys.hideDetails),
            onClick = onHide,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatHours(hours: Double): String {
    val h = hours.toLong()
    val m = ((hours - h) * 60).toLong()
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}

@Composable
private fun monthNameLocalized(month: Int): String =
    when (month) {
        1 -> str(EmployeeHubKeys.january)
        2 -> str(EmployeeHubKeys.february)
        3 -> str(EmployeeHubKeys.march)
        4 -> str(EmployeeHubKeys.april)
        5 -> str(EmployeeHubKeys.may)
        6 -> str(EmployeeHubKeys.june)
        7 -> str(EmployeeHubKeys.july)
        8 -> str(EmployeeHubKeys.august)
        9 -> str(EmployeeHubKeys.september)
        10 -> str(EmployeeHubKeys.october)
        11 -> str(EmployeeHubKeys.november)
        12 -> str(EmployeeHubKeys.december)
        else -> "Month $month"
    }

/** The role as the operator reads it ("Kassierer"), not the enum constant ("CASHIER"). */
@Composable
private fun localizedRoleName(role: String): String {
    val language = LocalAppLanguage.current
    return runCatching { EmployeeRole.valueOf(role).getName(language) }.getOrDefault(role)
}
