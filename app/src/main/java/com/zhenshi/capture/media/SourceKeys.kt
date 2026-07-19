package com.zhenshi.capture.media

object SourceKeys {
    fun encode(source: SignalSource): String = when (source) {
        is SignalSource.UsbDevice ->
            "usb:${source.deviceId}|${source.name}|${source.vendorId}|${source.productId}"
        is SignalSource.RtmpUrl -> source.url
        is SignalSource.RtspUrl -> source.url
    }

    fun decode(raw: String): SignalSource? {
        val value = android.net.Uri.decode(raw)
        if (value.startsWith("usb:")) {
            val parts = value.removePrefix("usb:").split("|")
            val id = parts.firstOrNull()?.toIntOrNull() ?: return null
            return when (parts.size) {
                1 -> SignalSource.UsbDevice(id, "USB $id")
                2 -> SignalSource.UsbDevice(id, parts[1])
                else -> SignalSource.UsbDevice(
                    deviceId = id,
                    name = parts[1],
                    vendorId = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                    productId = parts.getOrNull(3)?.toIntOrNull() ?: 0,
                )
            }
        }
        return parseNetworkSource(value)
    }

    fun encodeProfile(profile: VideoProfile?): String {
        if (profile == null) return ""
        return "${profile.width}x${profile.height}@${profile.fps}"
    }

    fun decodeProfile(raw: String): VideoProfile? {
        val value = android.net.Uri.decode(raw)
        if (value.isBlank()) return null
        val regex = Regex("""(\d+)x(\d+)@(\d+)""")
        val match = regex.matchEntire(value) ?: return null
        return VideoProfile(
            width = match.groupValues[1].toInt(),
            height = match.groupValues[2].toInt(),
            fps = match.groupValues[3].toInt(),
        )
    }
}
