package org.example.project.admin.features.auth.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.selectAdminLanguage
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.HttpCall
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.theme.CjkFontLoader
import org.koin.compose.koinInject
import androidx.compose.material.icons.filled.Language as LanguageIcon

/**
 * Language switch for the sign-in screen's `utilities` slot (FO-38), mirroring the signed-in user
 * menu: choosing a language writes [org.example.project.admin.core.presentation.strings.AdminLocale]
 * and the persisted choice through [selectAdminLanguage]. Language names are shown in their own
 * language, so the control needs no translated text.
 */
@Composable
fun LoginLanguageMenu() {
    val store: KeyValueStore = koinInject()
    val httpCall: HttpCall = koinInject()
    val language = LocalAppLanguage.current
    var expanded by remember { mutableStateOf(false) }

    // Same pre-warm as AdminUserMenu: fetch the CJK font when the menu opens, not after the switch.
    LaunchedEffect(expanded) {
        if (expanded) CjkFontLoader.load(httpCall)
    }

    Box {
        // 48dp tall with a globe icon: the bare ~40dp text link was a small target (UI review nit).
        TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.LanguageIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(language.nativeName)
        }
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { entry ->
                PosDropdownItem(
                    text = entry.nativeName,
                    selected = entry == language,
                    onClick = {
                        selectAdminLanguage(store, entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
