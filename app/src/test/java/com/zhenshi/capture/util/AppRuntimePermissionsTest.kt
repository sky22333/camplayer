package com.zhenshi.capture.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuntimePermissionsTest {

    @Test
    fun urlRequiresLocalNetwork_privateIpv4_returnsTrue() {
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://192.168.1.100/live"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtsp://10.0.0.5/stream"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://172.16.0.2/app"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://172.31.255.1/app"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://169.254.1.1/app"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://127.0.0.1/live"))
    }

    @Test
    fun urlRequiresLocalNetwork_localhostAndMdns_returnsTrue() {
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://localhost/live"))
        assertTrue(AppRuntimePermissions.urlRequiresLocalNetwork("rtsp://cam.local/stream"))
    }

    @Test
    fun urlRequiresLocalNetwork_publicHost_returnsFalse() {
        assertFalse(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://live.example.com/app"))
        assertFalse(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://8.8.8.8/live"))
        assertFalse(AppRuntimePermissions.urlRequiresLocalNetwork("rtmp://172.32.0.1/app"))
        assertFalse(AppRuntimePermissions.urlRequiresLocalNetwork("not-a-url"))
    }
}
