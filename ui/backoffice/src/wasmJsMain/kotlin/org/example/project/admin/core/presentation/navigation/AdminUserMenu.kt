package org.example.project.admin.core.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canReportProblems
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.HttpCall
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.auth.SessionState
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.theme.CjkFontLoader
import org.koin.compose.koinInject

/**
 * Sidebar footer: who is signed in, the language switcher and Abmelden. Logout lives here rather
 * than in the destination list because it is a session action, not a place.
 *
 * The signed-in employee's *name* is not shown — `TokenStore` persists no name. Surfacing it needs
 * the login response's employee name stored alongside it, which is separate work. What is shown is
 * the **permission group** (see [userMenuIdentityLabel]), falling back to the role only when the
 * server reported no group.
 *
 * The current language is still *read* here (checkmark + footer subtitle) via `LocalAppLanguage`,
 * but the *write* is hoisted through [onLanguageSelected] so the app root can persist it.
 */
@Composable
fun AdminUserMenu(
    tokenStore: TokenStore,
    collapsed: Boolean,
    onLogout: () -> Unit,
    onLanguageSelected: (Language) -> Unit,
    /**
     * Opens "Report a problem". The menu never hosts
     * [org.example.project.admin.features.issuereport.presentation.ReportProblemDialog] itself: it sits in the
     * sidebar or the drawer, both rebuilt when the window crosses 600dp, so a dialog hosted here closed
     * on a resize and lost its draft. `AuthenticatedApp` hosts it above that switch.
     */
    onReportProblem: () -> Unit,
    modifier: Modifier = Modifier,
    /** Dropdown row height; the phone drawer passes 48dp. */
    itemMinHeight: Dp = 40.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    val httpCall: HttpCall = koinInject()

    // Pre-warm the CJK font the moment the language menu opens, so a switch to Chinese does not flash
    // tofu boxes while the ~1.6 MB CJK font downloads and Skia parses it. AdminTheme only starts that fetch *after*
    // the switch commits, which is exactly when the missing glyphs are already on screen; opening the
    // menu is the earliest reliable signal of language intent, and the load overlaps with the user
    // reading the options. Idempotent and year-cached, so a German user who merely glances at the
    // menu pays it at most once, and never if they never open it.
    LaunchedEffect(expanded) {
        if (expanded) CjkFontLoader.load(httpCall)
    }

    // Keyed on the store rather than on `role`, whose getter is a token-store lookup —
    // `remember(tokenStore.role)` would still perform it every recomposition. The store is a
    // singleton and the authenticated subtree leaves composition on logout.
    val role = remember(tokenStore) { EmployeeRole.getRole(tokenStore.role) }
    val groupCode = remember(tokenStore) { tokenStore.permissionGroupCode }
    val groupName = remember(tokenStore) { tokenStore.permissionGroupName }
    val translate: (StringKey) -> String = { key -> TranslationRegistry.get(key, language) }
    val identityLabel = userMenuIdentityLabel(groupCode, groupName, role.getName(language), translate)
    // Keyed on the capability epoch, like the sidebar's gates: a silent token refresh can change
    // the capability set without moving any other Compose state.
    val epoch = koinInject<SessionState>().capabilityEpoch
    val canReportProblems = remember(epoch) { tokenStore.canReportProblems }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = true }
                    .padding(horizontal = if (collapsed) 8.dp else 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = if (collapsed) str(AdminStringKeys.NavUserMenu) else null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (!collapsed) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                ) {
                    Text(
                        text = identityLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = language.nativeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = str(AdminStringKeys.NavUserMenu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { entry ->
                PosDropdownItem(
                    text = entry.nativeName,
                    selected = entry == language,
                    minHeight = itemMinHeight,
                    onClick = {
                        onLanguageSelected(entry)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            if (canReportProblems) {
                PosDropdownItem(
                    text = str(IssueReportKeys.menuEntry),
                    leadingIcon = Icons.Default.BugReport,
                    minHeight = itemMinHeight,
                    onClick = {
                        expanded = false
                        onReportProblem()
                    },
                )
            }
            PosDropdownItem(
                text = str(AdminStringKeys.NavLogout),
                leadingIcon = Icons.AutoMirrored.Filled.Logout,
                color = MaterialTheme.colorScheme.error,
                minHeight = itemMinHeight,
                onClick = {
                    expanded = false
                    onLogout()
                },
            )
        }
    }
}

/**
 * The footer's "who is signed in" line: the permission group, because the group — not the role —
 * is what the server enforces (R6-4: an AUDITOR carrying role CASHIER was labelled "Kassierer").
 *
 * A seeded group code is translated ([AdminStringKeys.permissionGroupName]); an operator-made group
 * shows the name the server stored, then its code. [roleLabel] is used only when no group was
 * reported — an employee with none, or a session stored before the server sent the field.
 */
internal fun userMenuIdentityLabel(
    groupCode: String?,
    groupName: String?,
    roleLabel: String,
    translate: (StringKey) -> String,
): String {
    val code = groupCode?.takeIf { it.isNotBlank() } ?: return roleLabel
    val seeded = AdminStringKeys.permissionGroupName(code)?.let(translate)
    return seeded ?: groupName?.takeIf { it.isNotBlank() } ?: code
}
