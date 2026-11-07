package org.example.project.features.employees.presentation.components.hub

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfilePanel(
    currentInfo: EmployeeInfoDto.EmployeeInfoResponseDto?,
    onSave: (EmployeeInfoDto.UpdateEmployeeInfoRequestDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(currentInfo?.email ?: "") }
    var phoneNumber by remember { mutableStateOf(currentInfo?.phoneNumber ?: "") }
    var street by remember { mutableStateOf(currentInfo?.street ?: "") }
    var city by remember { mutableStateOf(currentInfo?.city ?: "") }
    var state by remember { mutableStateOf(currentInfo?.state ?: "") }
    var zipCode by remember { mutableStateOf(currentInfo?.zipCode ?: "") }
    var country by remember { mutableStateOf(currentInfo?.country ?: "") }
    var dateOfBirth by remember { mutableStateOf(currentInfo?.dateOfBirth ?: "") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    PosMarqueeText(
                        text = str(EmployeeHubKeys.editProfileTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = str(EmployeeHubKeys.cancel),
                        onClick = onDismiss,
                    )
                },
                actions = {
                    // raw Material 3 by design: TextButton in TopAppBar actions slot with custom fontWeight — no PosTextButton equivalent for slot placement
                    TextButton(
                        onClick = {
                            val request =
                                EmployeeInfoDto.UpdateEmployeeInfoRequestDto(
                                    email = email.trim().ifEmpty { null },
                                    phoneNumber = phoneNumber.trim().ifEmpty { null },
                                    street = street.trim().ifEmpty { null },
                                    city = city.trim().ifEmpty { null },
                                    state = state.trim().ifEmpty { null },
                                    zipCode = zipCode.trim().ifEmpty { null },
                                    country = country.trim().ifEmpty { null },
                                    dateOfBirth = dateOfBirth.trim().ifEmpty { null },
                                )
                            onSave(request)
                        },
                    ) {
                        Text(str(EmployeeHubKeys.save), fontWeight = FontWeight.SemiBold)
                    }
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Read-only fields
                if (currentInfo != null) {
                    ReadOnlyField(label = str(EmployeeHubKeys.fullName), value = currentInfo.fullName)
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // Section header
                Text(
                    text = str(EmployeeHubKeys.contactInformation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PosTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = str(EmployeeHubKeys.email),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                PosTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = str(EmployeeHubKeys.phoneNumber),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Address section header
                Text(
                    text = str(EmployeeHubKeys.address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PosTextField(
                    value = street,
                    onValueChange = { street = it },
                    label = str(EmployeeHubKeys.street),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = str(EmployeeHubKeys.city),
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                    )
                    PosTextField(
                        value = zipCode,
                        onValueChange = { zipCode = it },
                        label = str(EmployeeHubKeys.postalCode),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = state,
                        onValueChange = { state = it },
                        label = str(EmployeeHubKeys.state),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    PosTextField(
                        value = country,
                        onValueChange = { country = it },
                        label = str(EmployeeHubKeys.country),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Personal details section header
                Text(
                    text = str(EmployeeHubKeys.personalDetails),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PosTextField(
                    value = dateOfBirth,
                    onValueChange = { dateOfBirth = it },
                    label = str(EmployeeHubKeys.dateOfBirthFormat),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = str(EmployeeHubKeys.dateOfBirthPlaceholder),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Save button at bottom of scroll content
                PosButton(
                    text = str(EmployeeHubKeys.save),
                    onClick = {
                        val request =
                            EmployeeInfoDto.UpdateEmployeeInfoRequestDto(
                                email = email.trim().ifEmpty { null },
                                phoneNumber = phoneNumber.trim().ifEmpty { null },
                                street = street.trim().ifEmpty { null },
                                city = city.trim().ifEmpty { null },
                                state = state.trim().ifEmpty { null },
                                zipCode = zipCode.trim().ifEmpty { null },
                                country = country.trim().ifEmpty { null },
                                dateOfBirth = dateOfBirth.trim().ifEmpty { null },
                            )
                        onSave(request)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyField(
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
