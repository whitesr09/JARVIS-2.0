package com.nshd.jarvis2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsScreenTest {
    @Test
    fun preAndroid12DoesNotRequestBluetoothConnectOrNotificationPermission() {
        val permissions = runtimePermissionsForApi(30).toSet()
        assertFalse("android.permission.BLUETOOTH_CONNECT" in permissions)
        assertFalse("android.permission.POST_NOTIFICATIONS" in permissions)
        assertTrue("android.permission.RECORD_AUDIO" in permissions)
        assertTrue("android.permission.CAMERA" in permissions)
        assertEquals(5, permissions.size)
    }

    @Test
    fun Android12AddsBluetoothConnectButNotPostNotifications() {
        val permissions = runtimePermissionsForApi(31).toSet()
        assertTrue("android.permission.BLUETOOTH_CONNECT" in permissions)
        assertFalse("android.permission.POST_NOTIFICATIONS" in permissions)
        assertEquals(6, permissions.size)
    }

    @Test
    fun Android13AddsPostNotifications() {
        val permissions = runtimePermissionsForApi(33).toSet()
        assertTrue("android.permission.BLUETOOTH_CONNECT" in permissions)
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertEquals(7, permissions.size)
    }
}
