package org.avmedia.smartsync.plugin.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the plugin on asks for Health Connect's write permissions at once: the host can do it only when the plugin
 * declares them, checks them itself (they are not runtime permissions before Android 14) and gives Health Connect's own
 * request screen. Without these the person had to open the plugin's screen and press "Allow".
 */
class PermissionRequestTest {
    @Test fun theWritePermissionsAreDeclaredWithTheirOwnCheckAndRequestScreen() {
        val declared = HealthConnectPlugin.ACCESS.androidPermissions.map { it.name }.toSet()
        assertEquals(HealthConnectSink.permissions(RecordType.entries.toSet()), declared)
        assertTrue(HealthConnectPlugin.ACCESS.androidPermissions.all { it.reason.isNotBlank() })
        val own = HealthConnectPlugin::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("Health Connect's own request screen for the host", "permissionIntent" in own)
        assertTrue("its own check of what is granted", "missingPermissions" in own)
    }
}
