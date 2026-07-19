package com.zhenshi.capture.media.push

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** NV21 → H.264；只保留最新帧。 */
internal class H264Nv21Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val onConfigured: (sps: ByteBuffer, pps: ByteBuffer) -> Unit,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<Frame?>(null)
    private var codec: MediaCodec? = null
    private var encodeThread: Thread? = null
    private var videoInfoSent = false
    private var frameCount = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            runCatching {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
        }
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec
        videoInfoSent = false
        frameCount = 0L
        encodeThread = Thread({ encodeLoop(mediaCodec) }, "push-h264").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "started ${width}x${height}@${fps} bitrate=$bitrateBps")
    }

    fun stop() {
        running.set(false)
        latest.set(null)
        encodeThread?.join(1_000L)
        encodeThread = null
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        videoInfoSent = false
    }

    fun offerNv21(data: ByteArray, frameWidth: Int, frameHeight: Int, ptsUs: Long) {
        if (!running.get()) return
        if (frameWidth != width || frameHeight != height) return
        // 回调缓冲可能被复用，必须拷贝
        latest.set(Frame(data.copyOf(), ptsUs))
    }

    fun updateBitrate(bitrateBps: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
                },
            )
        }
    }

    private fun encodeLoop(mediaCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val frame = latest.getAndSet(null)
            if (frame != null) {
                queueInput(mediaCodec, frame)
            }
            drainOutput(mediaCodec, info)
            if (frame == null) {
                try {
                    Thread.sleep(2L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        // 收尾排空
        runCatching {
            val inIndex = mediaCodec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                mediaCodec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainOutput(mediaCodec, info)
        }
    }

    private fun queueInput(mediaCodec: MediaCodec, frame: Frame) {
        val inIndex = mediaCodec.dequeueInputBuffer(0L)
        if (inIndex < 0) return
        val input = mediaCodec.getInputBuffer(inIndex) ?: return
        input.clear()
        // MediaCodec SemiPlanar = NV12；AUSBC 回调为 NV21，需交换 VU→UV
        nv21ToNv12InPlace(frame.nv21, width, height)
        if (frame.nv21.size > input.capacity()) {
            Log.w(TAG, "frame too large ${frame.nv21.size} > ${input.capacity()}")
            mediaCodec.queueInputBuffer(inIndex, 0, 0, frame.ptsUs, 0)
            return
        }
        input.put(frame.nv21)
        mediaCodec.queueInputBuffer(inIndex, 0, frame.nv21.size, frame.ptsUs, 0)
    }

    /** NV21(Y+VU) → NV12(Y+UV)，原地交换色度对。 */
    private fun nv21ToNv12InPlace(frame: ByteArray, w: Int, h: Int) {
        val ySize = w * h
        var i = ySize
        while (i + 1 < frame.size) {
            val v = frame[i]
            frame[i] = frame[i + 1]
            frame[i + 1] = v
            i += 2
        }
    }

    private fun drainOutput(mediaCodec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = mediaCodec.outputFormat
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    if (sps != null && pps != null && !videoInfoSent) {
                        onConfigured(sps.duplicate(), pps.duplicate())
                        videoInfoSent = true
                    }
                }
                outIndex >= 0 -> {
                    val output = mediaCodec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            val bytes = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(bytes)
                            H264CodecConfig.parseSpsPps(bytes)?.let { (sps, pps) ->
                                if (!videoInfoSent) {
                                    onConfigured(sps, pps)
                                    videoInfoSent = true
                                }
                            }
                        } else if (videoInfoSent) {
                            val slice = output.duplicate()
                            slice.position(info.offset)
                            slice.limit(info.offset + info.size)
                            onEncoded(slice, info)
                            frameCount++
                            if (frameCount == 1L || frameCount % 90L == 0L) {
                                Log.i(
                                    TAG,
                                    "sent frames=$frameCount key=${info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0} " +
                                        "size=${info.size}",
                                )
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private data class Frame(val nv21: ByteArray, val ptsUs: Long)

    companion object {
        private const val TAG = "H264Nv21Encoder"
    }
}
