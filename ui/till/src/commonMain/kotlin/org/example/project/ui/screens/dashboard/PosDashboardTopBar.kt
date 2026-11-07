package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.resolveServerImageUrl
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.navigation.NavGroup
import org.example.project.ui.navigation.PosNavDestination
import org.example.project.ui.navigation.rememberVisibleDestinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosDashboardTopBar(
    onRefreshClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val settingManager = LocalSettingManager.current
    val baseUrl by settingManager.url.collectAsState()
    val logoUrlPath by settingManager.shopLogoUrl.collectAsState()
    val notificationViewModel = LocalNotificationViewModel.current
    val notificationState by notificationViewModel.uiState.collectAsState()

    val fullLogoUrl = resolveServerImageUrl(baseUrl, logoUrlPath)
    var logoFailed by remember(fullLogoUrl) { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = str(CommonKeys.refresh),
                            onClick = {
                                // A refresh is also the retry for a logo that failed to load.
                                logoFailed = false
                                onRefreshClick()
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (fullLogoUrl != null && !logoFailed) {
                    AsyncImage(
                        model = fullLogoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Fit,
                        onError = { logoFailed = true },
                    )
                } else {
                    Box(
                        modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PointOfSale,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        actions = {
            RotationNextChip()
            if (!compact) {
                PosIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = str(CommonKeys.refresh),
                    onClick = onRefreshClick,
                )
            } else {
                val canSeeNotifications =
                    rememberVisibleDestinations(NavGroup.PRIMARY).any { it == PosNavDestination.NOTIFICATIONS }
                if (canSeeNotifications) {
                    // raw Material 3 by design: badge anchor has no Pos* wrapper
                    BadgedBox(
                        badge = {
                            if (notificationState.unreadCount > 0) {
                                Badge { Text(notificationState.unreadCount.toString()) }
                            }
                        },
                    ) {
                        PosIconButton(
                            icon = Icons.Default.Notifications,
                            contentDescription = str(MainScreenKeys.notifications),
                            onClick = onNotificationsClick,
                        )
                    }
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
    )
}
