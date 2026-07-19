package com.zhenshi.capture.media.usb

/**
 * 从 USB 配置描述符解析 UVC 帧尺寸，无需 libuvc 开流。
 *
 * 帧率不从描述符解析：采集卡常在 MJPEG/YUV 多格式下重复上报 interval，
 * 且 continuous/discrete 混用会导致错误 FPS。帧率由 [VideoProfile.fromResolutions] 固定为 30/60。
 */
internal object UvcDescriptorParser {
    private const val DESC_TYPE_CS_INTERFACE = 0x24
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FRAME_FRAME_BASED = 0x0B
    private const val MIN_VS_FRAME_LENGTH = 26
    private const val MIN_DIMENSION = 160
    private const val MAX_DIMENSION = 4096

    fun parseFrameSizes(rawDescriptors: ByteArray): List<Pair<Int, Int>> {
        if (rawDescriptors.isEmpty()) return emptyList()
        val sizes = linkedSetOf<Pair<Int, Int>>()
        var offset = 0
        while (offset < rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xFF
            if (length < 2) break
            if (offset + length > rawDescriptors.size) break

            val descriptorType = rawDescriptors[offset + 1].toInt() and 0xFF
            if (descriptorType == DESC_TYPE_CS_INTERFACE && length >= MIN_VS_FRAME_LENGTH) {
                val subType = rawDescriptors[offset + 2].toInt() and 0xFF
                if (subType == VS_FRAME_UNCOMPRESSED ||
                    subType == VS_FRAME_MJPEG ||
                    subType == VS_FRAME_FRAME_BASED
                ) {
                    val width = readUInt16(rawDescriptors, offset + 5)
                    val height = readUInt16(rawDescriptors, offset + 7)
                    if (isPlausibleFrame(width, height)) {
                        sizes += width to height
                    }
                }
            }
            offset += length
        }
        return sizes
            .sortedByDescending { (width, height) -> width * height }
            .toList()
    }

    private fun isPlausibleFrame(width: Int, height: Int): Boolean {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) return false
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return false
        if (width.toLong() * height > 16_000_000L) return false
        val ratio = width.toDouble() / height
        return ratio in 0.2..5.0
    }

    private fun readUInt16(data: ByteArray, index: Int): Int {
        val low = data[index].toInt() and 0xFF
        val high = data[index + 1].toInt() and 0xFF
        return low or (high shl 8)
    }
}
