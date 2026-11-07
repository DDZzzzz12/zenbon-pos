package org.example.project.features.dashboard.presentation.components.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.models.Product
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardDefaults
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.koin.compose.koinInject

@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isSelected: Boolean = false,
    isFavourite: Boolean = false,
    settingManager: SettingManager = koinInject(),
) {
    val baseUrl by settingManager.url.collectAsState()
    val fullImageUrl = product.resolveImageUrl(baseUrl)

    PosCard(
        onClick = onClick,
        modifier = modifier,
        selected = isSelected,
        inactive = !product.isActive,
        status = if (product.isActive) PosCardStatus.Available else null,
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(-PosCardDefaults.CardPadding),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Image area — full-bleed, no padding
                    if (fullImageUrl != null) {
                        AsyncImage(
                            model = fullImageUrl,
                            contentDescription = product.name,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.2f),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    // Text content
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(PosCardDefaults.CardPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = product.name,
                            style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = product.price.toFormattedCurrency(),
                            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (isFavourite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = str(AccessibilityKeys.favourite),
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                    shape = CircleShape,
                                ).padding(2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
