package dev.exe.kindleconverter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.exe.kindleconverter.data.AppSettings
import dev.exe.kindleconverter.data.KINDLE_PROFILES
import dev.exe.kindleconverter.data.ThemeMode
import dev.exe.kindleconverter.data.profileName
import dev.exe.kindleconverter.ui.components.PortDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverRunningPort: Int?,
    onSetProfile: (String) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetDynamic: (Boolean) -> Unit,
    onSetPort: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var showPortDialog by remember { mutableStateOf(false) }
    if (showPortDialog) {
        PortDialog(
            current = serverRunningPort ?: settings.serverPort,
            onDismiss = { showPortDialog = false },
            onConfirm = { showPortDialog = false; onSetPort(it) },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxWidth().padding(horizontal = 20.dp)) {
            SectionLabel("Kindle model")
            Text(
                "New conversions are formatted for this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProfileDropdown(settings.lastProfile, onSetProfile)

            Divider()
            SectionLabel("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(settings.themeMode == mode) { onSetTheme(mode) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.themeMode == mode, onClick = { onSetTheme(mode) })
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Match my wallpaper colors", style = MaterialTheme.typography.bodyLarge)
                    Text("Material You dynamic color (Android 12+)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.dynamicColor, onCheckedChange = onSetDynamic)
            }

            Divider()
            SectionLabel("Server")
            val port = serverRunningPort ?: settings.serverPort
            Row(
                Modifier.fillMaxWidth().clickable { showPortDialog = true }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Port $port",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showPortDialog = true }) { Text("Change") }
            }
            Text(
                "The Kindle connects to this device's address on this port.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDropdown(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) {
        Text(profileName(current))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ArrowDropDown, null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        KINDLE_PROFILES.forEach { p ->
            DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onPick(p.id) })
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(8.dp))
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
