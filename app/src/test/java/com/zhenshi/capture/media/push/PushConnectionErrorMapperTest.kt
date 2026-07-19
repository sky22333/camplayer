package com.zhenshi.capture.media.push

import com.zhenshi.capture.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PushConnectionErrorMapperTest {

    private val strings = mapOf(
        R.string.push_error_auth to "推流鉴权失败",
        R.string.push_error_timeout to "推流连接超时",
        R.string.push_error_refused to "推流服务器拒绝连接",
        R.string.push_error_unreachable to "无法访问推流服务器",
        R.string.push_error_connection_lost to "推流连接已断开",
        R.string.push_error_connection_failed to "推流连接失败",
    )

    private fun resolve(id: Int): String = strings.getValue(id)

    @Test
    fun keepsChineseMessage() {
        assertEquals(
            "USB 推流打开失败",
            PushConnectionErrorMapper.toUserMessage("USB 推流打开失败", ::resolve),
        )
    }

    @Test
    fun mapsAuthTimeoutRefused() {
        assertEquals(
            "推流鉴权失败",
            PushConnectionErrorMapper.toUserMessage("Auth error", ::resolve),
        )
        assertEquals(
            "推流连接超时",
            PushConnectionErrorMapper.toUserMessage("Connection timeout", ::resolve),
        )
        assertEquals(
            "推流服务器拒绝连接",
            PushConnectionErrorMapper.toUserMessage("Connection refused", ::resolve),
        )
    }

    @Test
    fun mapsUnreachableAndReset() {
        assertEquals(
            "无法访问推流服务器",
            PushConnectionErrorMapper.toUserMessage("Network is unreachable", ::resolve),
        )
        assertEquals(
            "推流连接已断开",
            PushConnectionErrorMapper.toUserMessage("Connection reset by peer", ::resolve),
        )
    }

    @Test
    fun unknownEnglishFallsBackToGeneric() {
        assertEquals(
            "推流连接失败",
            PushConnectionErrorMapper.toUserMessage("weird library dump XYZ", ::resolve),
        )
        assertEquals(
            "推流连接失败",
            PushConnectionErrorMapper.toUserMessage(null, ::resolve),
        )
        assertEquals(
            "推流连接失败",
            PushConnectionErrorMapper.toUserMessage("  ", ::resolve),
        )
    }
}
