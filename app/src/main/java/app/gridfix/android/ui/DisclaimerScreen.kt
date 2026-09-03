package app.gridfix.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridfix.android.AppInfo
import app.gridfix.android.ui.theme.LabelFamily

/**
 * Shown once, before anything else. A GPS receiver in a phone is an aid, not a
 * primary means of navigation, and someone about to walk into terrain on the
 * strength of a ten-digit grid should be told so in plain words before they start.
 */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Before you use this",
                fontFamily = LabelFamily,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
            )
            Spacer(Modifier.height(20.dp))

            Point(
                "This is not a primary means of navigation.",
                "Carry a map, a compass and a pace count, and know how to use them. " +
                    "Treat every grid this app gives you as something to confirm against the ground.",
            )
            Point(
                "A phone GPS can be wrong or absent.",
                "Tree cover, buildings, terrain, a dead battery, a cracked screen or the cold " +
                    "will each take it away. The fix quality shown on the Position screen tells " +
                    "you how much to trust the number — read it.",
            )
            Point(
                "Maps and elevation come from open data.",
                "Coverage and accuracy vary by country, and contours are modelled, not surveyed. " +
                    "Nothing here is an authoritative or certified navigation source.",
            )
            Point(
                "Nothing leaves your phone.",
                "No account, no tracking, no analytics. Your waypoints, tracks and overlays stay " +
                    "on the device until you export or back them up yourself.",
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
            ) {
                Text(
                    "I UNDERSTAND",
                    fontFamily = LabelFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { uriHandler.openUri(AppInfo.PRIVACY_URL) }) {
                    Text("Privacy policy", fontFamily = LabelFamily, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "▸",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontFamily = LabelFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 25.dp, top = 4.dp),
        )
    }
}
