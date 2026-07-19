package com.zhenshi.capture.media.push

import com.zhenshi.capture.R

/** RootEncoder / 网络失败原因 → 中文用户文案。 */
object PushConnectionErrorMapper {

    fun toUserMessage(raw: String?, string: (Int) -> String): String {
        val reason = raw?.trim().orEmpty()
        if (reason.isEmpty()) return string(R.string.push_error_connection_failed)
        if (reason.any { it in '\u4e00'..'\u9fff' }) return reason

        val lower = reason.lowercase()
        return when {
            "auth" in lower -> string(R.string.push_error_auth)
            "timeout" in lower || "timed out" in lower -> string(R.string.push_error_timeout)
            "refused" in lower -> string(R.string.push_error_refused)
            "unreachable" in lower ||
                "network is unreachable" in lower ||
                "no route" in lower -> string(R.string.push_error_unreachable)
            "reset" in lower || "broken pipe" in lower || "closed" in lower ->
                string(R.string.push_error_connection_lost)
            else -> string(R.string.push_error_connection_failed)
        }
    }
}
