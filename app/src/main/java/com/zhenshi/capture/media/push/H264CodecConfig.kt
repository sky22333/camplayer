package com.zhenshi.capture.media.push

import java.nio.ByteBuffer

/** H.264 SPS/PPS 解析（Annex-B / avcC）。 */
internal object H264CodecConfig {
    fun parseSpsPps(data: ByteArray): Pair<ByteBuffer, ByteBuffer>? {
        if (data.isEmpty()) return null
        if (data[0] == 1.toByte()) {
            return parseAvcC(data)
        }
        return parseAnnexB(data)
    }

    private fun parseAnnexB(data: ByteArray): Pair<ByteBuffer, ByteBuffer>? {
        val nals = splitAnnexBNals(data)
        val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 } ?: return null
        val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 } ?: return null
        return ByteBuffer.wrap(sps) to ByteBuffer.wrap(pps)
    }

    private fun parseAvcC(data: ByteArray): Pair<ByteBuffer, ByteBuffer>? {
        if (data.size < 7) return null
        var index = 5
        val spsCount = data[index++].toInt() and 0x1F
        if (spsCount <= 0 || index + 2 > data.size) return null
        val spsLength = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
        index += 2
        if (spsLength <= 0 || index + spsLength > data.size) return null
        val sps = data.copyOfRange(index, index + spsLength)
        index += spsLength
        if (index >= data.size) return null
        val ppsCount = data[index++].toInt() and 0xFF
        if (ppsCount <= 0 || index + 2 > data.size) return null
        val ppsLength = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
        index += 2
        if (ppsLength <= 0 || index + ppsLength > data.size) return null
        val pps = data.copyOfRange(index, index + ppsLength)
        return ByteBuffer.wrap(sps) to ByteBuffer.wrap(pps)
    }

    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val isFourByte = i + 3 < data.size &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            val isThreeByte = !isFourByte &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            when {
                isFourByte -> {
                    starts.add(i + 4)
                    i += 4
                }
                isThreeByte -> {
                    starts.add(i + 3)
                    i += 3
                }
                else -> i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        return buildList {
            for (index in starts.indices) {
                val start = starts[index]
                val end = if (index + 1 < starts.size) {
                    val next = starts[index + 1]
                    when {
                        next >= 4 && data[next - 4] == 0.toByte() &&
                            data[next - 3] == 0.toByte() &&
                            data[next - 2] == 0.toByte() &&
                            data[next - 1] == 1.toByte() -> next - 4
                        next >= 3 && data[next - 3] == 0.toByte() &&
                            data[next - 2] == 0.toByte() &&
                            data[next - 1] == 1.toByte() -> next - 3
                        else -> next
                    }
                } else {
                    data.size
                }
                if (start < end) add(data.copyOfRange(start, end))
            }
        }
    }
}
