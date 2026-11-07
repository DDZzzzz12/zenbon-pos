package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isDesktop
import org.example.project.core.utils.openUrlInBrowser
import org.example.project.features.settings.presentation.viewmodel.GeneralSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.SyncState
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.screens.settings.components.SettingsContentHeader
import org.example.project.ui.screens.settings.components.SettingsInfoCard
import org.example.project.ui.screens.settings.components.SettingsSectionLabel
import org.example.project.ui.screens.settings.components.SettingsToggleCard
import org.example.project.ui.screens.settings.components.ThemeSegmentedButtons
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PosGeneralContent(
    language: Language,
    isCompact: Boolean,
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
) {
    val settingManager = LocalSettingManager.current
    val themeMode by settingManager.themeMode.collectAsState()
    val tableMode by settingManager.tablePlanMode.collectAsState(false)
    val uiDensity by settingManager.uiDensity.collectAsState()
    val scrollSensitivity by settingManager.scrollSensitivity.collectAsState()

    val localDb: LocalDatabaseManager = koinInject()
    val generalSettingsViewModel: GeneralSettingsViewModel = koinViewModel()
    val syncState by generalSettingsViewModel.syncState.collectAsState()
    val scope = rememberCoroutineScope()
    var manualBaseUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            manualBaseUrl = "${localDb.getApiBaseUrl()}manual/"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isCompact) {
            SettingsContentHeader(
                icon = Icons.Default.Settings,
                title = str(SettingsKeys.generalTab),
            )
        }

        if (isCompact) {
            CompactGeneralContent(
                themeMode = themeMode,
                onThemeSelected = { settingManager.setThemeMode(it) },
                tableMode = tableMode,
                onTableModeChange = { settingManager.setTablePlanMode(it) },
                uiDensity = uiDensity,
                onUiDensityChange = { settingManager.setUiDensity(it) },
                scrollSensitivity = scrollSensitivity,
                onScrollSensitivityChange = { settingManager.setScrollSensitivity(it) },
                manualBaseUrl = manualBaseUrl,
                syncState = syncState,
                showProductSync = generalSettingsViewModel.showProductSync,
                onSyncProducts = { generalSettingsViewModel.syncProductData() },
                onLogout = onLogout,
                onShutdown = onShutdown,
            )
        } else {
            DesktopGeneralContent(
                themeMode = themeMode,
                onThemeSelected = { settingManager.setThemeMode(it) },
                tableMode = tableMode,
                onTableModeChange = { settingManager.setTablePlanMode(it) },
                uiDensity = uiDensity,
                onUiDensityChange = { settingManager.setUiDensity(it) },
                scrollSensitivity = scrollSensitivity,
                onScrollSensitivityChange = { settingManager.setScrollSensitivity(it) },
                manualBaseUrl = manualBaseUrl,
                syncState = syncState,
                showProductSync = generalSettingsViewModel.showProductSync,
                onSyncProducts = { generalSettingsViewModel.syncProductData() },
            )
        }
    }
}

@Composable
private fun DesktopGeneralContent(
    themeMode: org.example.project.features.settings.domain.enums.ThemeMode,
    onThemeSelected: (org.example.project.features.settings.domain.enums.ThemeMode) -> Unit,
    tableMode: Boolean,
    onTableModeChange: (Boolean) -> Unit,
    uiDensity: Float,
    onUiDensityChange: (Float) -> Unit,
    scrollSensitivity: Float,
    onScrollSensitivityChange: (Float) -> Unit,
    manualBaseUrl: String?,
    syncState: SyncState,
    showProductSync: Boolean,
    onSyncProducts: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LocalPosLayout.current.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Left column - Appearance
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSectionLabel(
                icon = Icons.Default.Palette,
                title = str(SettingsKeys.appearanceSection),
            )

            ThemeSegmentedButtons(
                selectedTheme = themeMode,
                onThemeSelected = onThemeSelected,
            )

            SettingsToggleCard(
                title = str(SettingsKeys.tablePlanMode),
                description = str(SettingsKeys.tablePlanModeDescription),
                checked = tableMode,
                onCheckedChange = onTableModeChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isDesktop()) {
                UiDensitySlider(
                    uiDensity = uiDensity,
                    onUiDensityChange = onUiDensityChange,
                )

                ScrollSensitivitySlider(
                    scrollSensitivity = scrollSensitivity,
                    onScrollSensitivityChange = onScrollSensitivityChange,
                )
            }
        }

        // Right column - Data & Help
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showProductSync) {
                SettingsSectionLabel(
                    icon = Icons.Default.Sync,
                    title = str(SettingsKeys.sync),
                )

                SyncProductDataCard(
                    syncState = syncState,
                    onSync = onSyncProducts,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            SettingsSectionLabel(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = str(SettingsKeys.helpSupportSection),
            )

            SettingsInfoCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = str(SettingsKeys.openManual),
                description = str(SettingsKeys.openManualDesc),
                onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}index.html") } },
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsInfoCard(
                icon = Icons.Default.Description,
                title = str(SettingsKeys.dsfinvkDocSection),
                description = str(SettingsKeys.dsfinvkDocDescription),
                onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}dsfinvk-export.html") } },
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsInfoCard(
                icon = Icons.Default.Security,
                title = str(SettingsKeys.tseDocSection),
                description = str(SettingsKeys.tseDocDescription),
                onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}tse-documentation.html") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CompactGeneralContent(
    themeMode: org.example.project.features.settings.domain.enums.ThemeMode,
    onThemeSelected: (org.example.project.features.settings.domain.enums.ThemeMode) -> Unit,
    tableMode: Boolean,
    onTableModeChange: (Boolean) -> Unit,
    uiDensity: Float,
    onUiDensityChange: (Float) -> Unit,
    scrollSensitivity: Float,
    onScrollSensitivityChange: (Float) -> Unit,
    manualBaseUrl: String?,
    syncState: SyncState,
    showProductSync: Boolean,
    onSyncProducts: () -> Unit,
    onLogout: () -> Unit,
    onShutdown: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Appearance
        SettingsSectionLabel(
            icon = Icons.Default.Palette,
            title = str(SettingsKeys.appearanceSection),
        )

        ThemeSegmentedButtons(
            selectedTheme = themeMode,
            onThemeSelected = onThemeSelected,
        )

        SettingsToggleCard(
            title = str(SettingsKeys.tablePlanMode),
            description = str(SettingsKeys.tablePlanModeDescription),
            checked = tableMode,
            onCheckedChange = onTableModeChange,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isDesktop()) {
            UiDensitySlider(
                uiDensity = uiDensity,
                onUiDensityChange = onUiDensityChange,
            )

            ScrollSensitivitySlider(
                scrollSensitivity = scrollSensitivity,
                onScrollSensitivityChange = onScrollSensitivityChange,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sync — absent entirely on clients with no local cache to sync into.
        if (showProductSync) {
            SettingsSectionLabel(
                icon = Icons.Default.Sync,
                title = str(SettingsKeys.sync),
            )

            SyncProductDataCard(
                syncState = syncState,
                onSync = onSyncProducts,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Help & Support
        SettingsSectionLabel(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = str(SettingsKeys.helpSupportSection),
        )

        SettingsInfoCard(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = str(SettingsKeys.openManual),
            description = str(SettingsKeys.openManualDesc),
            onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}index.html") } },
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsInfoCard(
            icon = Icons.Default.Description,
            title = str(SettingsKeys.dsfinvkDocSection),
            description = str(SettingsKeys.dsfinvkDocDescription),
            onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}dsfinvk-export.html") } },
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsInfoCard(
            icon = Icons.Default.Security,
            title = str(SettingsKeys.tseDocSection),
            description = str(SettingsKeys.tseDocDescription),
            onClick = { manualBaseUrl?.let { openUrlInBrowser("${it}tse-documentation.html") } },
            modifier = Modifier.fillMaxWidth(),
        )

        // Account section (compact only)
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionLabel(
            icon = Icons.AutoMirrored.Filled.Logout,
            title = str(SettingsKeys.accountSection),
        )

        if (!isDesktop()) {
            PosDangerButton(
                text = str(SettingsKeys.logout),
                icon = Icons.AutoMirrored.Filled.Logout,
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UiDensitySlider(
    uiDensity: Float,
    onUiDensityChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = str(SettingsKeys.uiDensity),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = str(SettingsKeys.uiDensityDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(uiDensity * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Slider(
            value = uiDensity,
            onValueChange = onUiDensityChange,
            valueRange = 1.0f..5.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "100%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "500%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScrollSensitivitySlider(
    scrollSensitivity: Float,
    onScrollSensitivityChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = str(SettingsKeys.scrollSensitivity),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = str(SettingsKeys.scrollSensitivityDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(scrollSensitivity * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Slider(
            value = scrollSensitivity,
            onValueChange = onScrollSensitivityChange,
            valueRange = 0.1f..1.0f,
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "10%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "100%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncProductDataCard(
    syncState: SyncState,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText =
        when (syncState) {
            SyncState.Idle -> str(SettingsKeys.syncProductDataDesc)
            SyncState.InProgress -> str(SettingsKeys.syncInProgress)
            SyncState.Success -> str(SettingsKeys.syncSuccess)
            SyncState.Failed -> str(SettingsKeys.syncFailed)
        }

    SettingsInfoCard(
        icon = Icons.Default.Sync,
        title = str(SettingsKeys.syncProductData),
        description = statusText,
        onClick = onSync,
        modifier = modifier,
    )
}
