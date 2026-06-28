package dev.exe.kindleconverter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.exe.kindleconverter.net.LanAddress
import dev.exe.kindleconverter.net.discoverAddresses
import dev.exe.kindleconverter.ui.theme.MonoUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun rememberAddresses(): List<LanAddress> {
    val state by produceState(initialValue = emptyList<LanAddress>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { discoverAddresses() }
            delay(4000)
        }
    }
    return state
}

/**
 * The "type this on your Kindle" card. Text-first by design: the Kindle has no camera,
 * so a QR code would be useless. The most likely (hotspot) address is shown large and
 * monospaced; tapping copies it. Any other addresses are offered quietly below.
 */
@Composable
fun AddressCard(port: Int, modifier: Modifier = Modifier) {
    val addresses = rememberAddresses()
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val primary = addresses.firstOrNull()
    val others = addresses.drop(1)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text(
            "On your Kindle, open the Experimental Browser and go to:",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (primary == null) {
            Text("Looking for your network…", style = MaterialTheme.typography.bodyLarge)
        } else {
            val url = "${primary.ip}:$port"
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surface)
                    .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
                    .clickable { clipboard.setText(AnnotatedString("http://$url/")) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(url, style = MonoUrl, color = cs.onSurface)
                Icon(LocalIcons.ContentCopy, "Copy", tint = cs.primary)
            }
            if (primary.isHotspotLikely) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Looks like your hotspot address — try this one first.",
                    style = MaterialTheme.typography.labelMedium, color = cs.primary,
                )
            }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Other addresses", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                others.forEach { a ->
                    Text(
                        "${a.ip}:$port",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { clipboard.setText(AnnotatedString("http://${a.ip}:$port/")) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}
