package org.avmedia.smartsync.plugin.homeassistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A plain broadcast sign (a dot and two waves each side): no third-party logo. */
object HaIcon {
    val image: ImageVector by lazy {
        ImageVector.Builder("mqtt_broadcast", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 12f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                close()
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
                moveTo(8.5f, 8.5f)
                arcTo(5f, 5f, 0f, false, false, 8.5f, 15.5f)
                moveTo(15.5f, 8.5f)
                arcTo(5f, 5f, 0f, false, true, 15.5f, 15.5f)
                moveTo(5.6f, 5.6f)
                arcTo(9f, 9f, 0f, false, false, 5.6f, 18.4f)
                moveTo(18.4f, 5.6f)
                arcTo(9f, 9f, 0f, false, true, 18.4f, 18.4f)
            }
        }.build()
    }
}
