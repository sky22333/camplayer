package com.zhenshi.capture.data

import java.util.UUID

data class PushTarget(
    val id: String,
    val name: String,
    val url: String,
) {
    companion object {
        fun create(name: String, url: String): PushTarget =
            PushTarget(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                url = url.trim(),
            )
    }
}

fun validatePushTargetName(raw: String): String? {
    val name = raw.trim()
    return name.takeIf { it.isNotEmpty() }
}

fun validatePushTargetUrl(raw: String): String? {
    val url = raw.trim()
    if (url.isEmpty()) return null
    val lower = url.lowercase()
    return if (lower.startsWith("rtmp://") || lower.startsWith("rtmps://")) url else null
}
