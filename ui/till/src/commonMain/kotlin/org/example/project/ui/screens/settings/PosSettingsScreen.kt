package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.navigation.LoadingState
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDrawerController
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.state.SettingsTab
import org.example.project.features.settings.presentation.state.SettingsUiState
import org.example.project.features.settings.presentation.viewmodel.SettingsViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSuccessWatcher
import org.koin.compose.viewmodel.koinViewModel

/**
 * Redesigned Settings screen with PosTheme styling and adaptive layout.
 *
 * - Compact: Top bar + horizontal FilterChip tabs + content
 * - Expanded+: Sidebar tab list (220dp) + content (no top bar)
 *
 * Role-based tab visibility: GENERAL visible to all, others manager+.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosSettingsScreen(
    language: Language,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
) {
    val drawerController = LocalDrawerController.current
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current
    val permissionManager = LocalPermissionManager.current
    val role = permissionManager.effectiveRole.collectAsState().value

    BackHandler(enabled = true, onBack = {
        if (drawerController?.isDrawerAvailable == true) {
            drawerController.toggleDrawer()
        }
    })

    PosErrorWatcher(
        error = uiState.error,
        title = getString(SettingsKeys.title, language),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.clearError() },
    )

    // Handle success messages via global notification system
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = getString(SettingsKeys.title, language),
        onClear = { viewModel.clearSuccessMessage() },
    )

    // Handle saveSuccess via global notification system
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            overlay.showSuccess(
                title = getString(SettingsKeys.title, language),
                message = getString(SettingsKeys.saveSuccess, language),
                displayMode = DisplayMode.TOAST,
            )
        }
    }

    // Resolve current window size using new LocalWindowSizeClass API
    val windowSize = LocalWindowSizeClass.current

    // Filter tabs based on role
    // Every tab past GENERAL edits shop configuration, which the server gates on CONFIG_WRITE.
    val canConfigure = holds(Capability.CONFIG_WRITE)
    val visibleTabs =
        remember(canConfigure) {
            SettingsTab.entries.filter { tab ->
                when (tab) {
                    SettingsTab.GENERAL -> true
                    else -> canConfigure
                }
            }
        }

    when {
        uiState.isLoading -> {
            LoadingState(
                modifier = modifier.fillMaxSize(),
                message = str(SettingsKeys.loading),
            )
        }
        else -> {
            when {
                windowSize.isAtLeastExpanded() -> {
                    PosSettingsSidebarLayout(
                        uiState = uiState,
                        visibleTabs = visibleTabs,
                        language = language,
                        onTabSelected = { viewModel.selectTab(it) },
                        modifier = modifier,
                        onLogout = onLogout,
                        onShutdown = onShutdown,
                    )
                }
                else -> {
                    PosSettingsCompactLayout(
                        uiState = uiState,
                        visibleTabs = visibleTabs,
                        language = language,
                        onTabSelected = { viewModel.selectTab(it) },
                        onRefresh = { viewModel.selectTab(uiState.selectedTab) },
                        drawerController = drawerController,
                        modifier = modifier,
                        onLogout = onLogout,
                        onShutdown = onShutdown,
                    )
                }
            }
        }
    }
}

/**
 * Compact layout: Scaffold with TopAppBar + horizontal FilterChip row + content below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosSettingsCompactLayout(
    uiState: SettingsUiState,
    visibleTabs: List<SettingsTab>,
    language: Language,
    onTabSelected: (SettingsTab) -> Unit,
    onRefresh: () -> Unit,
    drawerController: org.example.project.core.utils.DrawerController?,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (drawerController?.isDrawerAvailable == true) {
                        PosNavIconButton(
                            icon = Icons.Default.Menu,
                            contentDescription = str(AccessibilityKeys.menu),
                            onClick = { drawerController.toggleDrawer() },
                        )
                    }
                },
                title = {
                    PosMarqueeText(
                        str(SettingsKeys.title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    PosIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = str(CommonKeys.refresh),
                        onClick = onRefresh,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // Horizontal scrollable FilterChip tabs
            if (visibleTabs.isNotEmpty()) {
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(visibleTabs) { tab ->
                        val selected = uiState.selectedTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = { onTabSelected(tab) },
                            label = { Text(getTabName(tab, language)) },
                            leadingIcon = {
                                Icon(
                                    getTabIcon(tab),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            // Content area
            PosSettingsTabContent(
                selectedTab = uiState.selectedTab,
                uiState = uiState,
                language = language,
                isCompact = true,
                modifier = Modifier.fillMaxSize(),
                onLogout = onLogout,
                onShutdown = onShutdown,
            )
        }
    }
}

/**
 * Sidebar layout for expanded screens: vertical tab list on the left, content on the right.
 * No Scaffold or TopAppBar — manages its own layout directly.
 */
@Composable
private fun PosSettingsSidebarLayout(
    uiState: SettingsUiState,
    visibleTabs: List<SettingsTab>,
    language: Language,
    onTabSelected: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
) {
    Row(modifier = modifier.fillMaxSize()) {
        // Sidebar tab list
        Surface(
            modifier =
                Modifier
                    .width(220.dp)
                    .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
            ) {
                // Settings header
                val borderColor = MaterialTheme.colorScheme.outlineVariant
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 16.dp)
                            .drawBehind {
                                drawLine(
                                    color = borderColor,
                                    start = Offset(0f, size.height),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        str(SettingsKeys.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Scrollable tab list
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                ) {
                    visibleTabs.forEach { tab ->
                        val selected = uiState.selectedTab == tab
                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    getTabIcon(tab),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            label = {
                                PosAutoSizeText(
                                    getTabName(tab, language),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            selected = selected,
                            onClick = { onTabSelected(tab) },
                            colors =
                                NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }

        VerticalDivider()

        // Content area
        PosSettingsTabContent(
            selectedTab = uiState.selectedTab,
            uiState = uiState,
            language = language,
            isCompact = false,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onLogout = onLogout,
            onShutdown = onShutdown,
        )
    }
}

/**
 * Settings content area - routes to the Pos*Content screens.
 */
@Composable
private fun PosSettingsTabContent(
    selectedTab: SettingsTab,
    uiState: SettingsUiState,
    language: Language,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (selectedTab) {
            SettingsTab.GENERAL ->
                PosGeneralContent(
                    language = language,
                    isCompact = isCompact,
                    onLogout = onLogout,
                    onShutdown = onShutdown,
                )
            SettingsTab.PRINTER ->
                PosPrinterContent(
                    language = language,
                    isCompact = isCompact,
                    canEditPrintSettings = holds(Capability.CONFIG_WRITE),
                )
            SettingsTab.TSE ->
                PosTseContent(
                    language = language,
                    isCompact = isCompact,
                    isAdminOrOwner = holds(Capability.TSE_ADMIN),
                )
            /* DISABLED 2026-05-07 (timetracking): integration removed — feature code preserved
            SettingsTab.TIME_TRACKING -> {
                Column(Modifier.fillMaxSize()) {
                    if (!isCompact) {
                        SettingsContentHeader(
                            icon = Icons.Default.AccessTime,
                            title = str(SettingsKeys.timeTrackingTab),
                        )
                    }
                    TimeTrackingSettingsContent(language = language)
                }
            }
             */
        }
    }
}

/**
 * Get localized tab name.
 */
private fun getTabName(
    tab: SettingsTab,
    language: Language,
): String =
    when (tab) {
        SettingsTab.GENERAL -> getString(SettingsKeys.generalTab, language)
        SettingsTab.PRINTER -> getString(SettingsKeys.printerTab, language)
        SettingsTab.TSE -> getString(SettingsKeys.tseTab, language)
        // DISABLED 2026-05-07 (timetracking): integration removed — feature code preserved
        // SettingsTab.TIME_TRACKING -> getString(SettingsKeys.timeTrackingTab, language)
    }

/**
 * Get tab icon.
 */
private fun getTabIcon(tab: SettingsTab): ImageVector =
    when (tab) {
        SettingsTab.GENERAL -> Icons.Default.Settings
        SettingsTab.PRINTER -> Icons.Default.Print
        SettingsTab.TSE -> Icons.Default.Security
        // DISABLED 2026-05-07 (timetracking): integration removed — feature code preserved
        // SettingsTab.TIME_TRACKING -> Icons.Default.AccessTime
    }
