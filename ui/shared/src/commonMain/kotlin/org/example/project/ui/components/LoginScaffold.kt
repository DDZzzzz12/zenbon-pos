package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/** Accent color paired with the theme primary in the compact brand gradient. */
private val BrandAccent = Color(0xFF7C3AED)
private val CompactBreakpoint = 600.dp

/**
 * Branded, responsive login page shared by composeApp (POS terminal) and adminApp (web).
 *
 * - Compact (< 600dp wide): gradient brand header above a rounded form sheet.
 * - Expanded: brand panel on the left, form on the right.
 *
 * All text is passed in already-resolved, so the scaffold stays free of any i18n runtime.
 * [utilities] is an optional slot for host-specific controls (server status, settings,
 * language) rendered in the corner — the web admin typically passes none. [form] is the
 * credential form (e.g. [LoginForm]).
 *
 * [utilitiesOnTop] puts the slot above the welcome text on a phone instead of at the bottom of the
 * sheet, where the bottom toast of a rejected sign-in covered it (adminApp's language switch).
 */
@Composable
fun LoginScaffold(
    brandTitle: String,
    brandSubtitle: String,
    welcomeTitle: String,
    welcomeSubtitle: String,
    modifier: Modifier = Modifier,
    brandIcon: ImageVector = Icons.Default.PointOfSale,
    utilities: (@Composable () -> Unit)? = null,
    utilitiesOnTop: Boolean = false,
    form: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth < CompactBreakpoint) {
            CompactLayout(brandTitle, brandSubtitle, welcomeTitle, welcomeSubtitle, brandIcon, utilities, utilitiesOnTop, form)
        } else {
            ExpandedLayout(brandTitle, brandSubtitle, welcomeTitle, welcomeSubtitle, brandIcon, utilities, form)
        }
    }
}

@Composable
private fun CompactLayout(
    brandTitle: String,
    brandSubtitle: String,
    welcomeTitle: String,
    welcomeSubtitle: String,
    brandIcon: ImageVector,
    utilities: (@Composable () -> Unit)?,
    utilitiesOnTop: Boolean,
    form: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(MaterialTheme.colorScheme.primary, BrandAccent),
                            ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 60.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
            ) {
                Icon(
                    imageVector = brandIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    text = brandTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = brandSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize().offset(y = (-24).dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 32.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (utilities != null && utilitiesOnTop) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        utilities()
                    }
                }
                WelcomeText(welcomeTitle, welcomeSubtitle)
                form()
                if (utilities != null && !utilitiesOnTop) {
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        utilities()
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedLayout(
    brandTitle: String,
    brandSubtitle: String,
    welcomeTitle: String,
    welcomeSubtitle: String,
    brandIcon: ImageVector,
    utilities: (@Composable () -> Unit)?,
    form: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        BrandPanel(
            brandTitle = brandTitle,
            brandSubtitle = brandSubtitle,
            brandIcon = brandIcon,
            modifier = Modifier.weight(0.45f).fillMaxHeight(),
        )

        Box(
            modifier = Modifier.weight(0.55f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (utilities != null) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    utilities()
                }
            }

            Column(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .padding(horizontal = 48.dp)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
            ) {
                WelcomeText(welcomeTitle, welcomeSubtitle)
                form()
            }
        }
    }
}

@Composable
private fun BrandPanel(
    brandTitle: String,
    brandSubtitle: String,
    brandIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = brandIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(80.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = brandTitle,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = brandSubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WelcomeText(
    title: String,
    subtitle: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
