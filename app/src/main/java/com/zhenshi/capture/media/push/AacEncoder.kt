package com.zhenshi.capture.media.push

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** PCM 16-bit LE → AAC-LC；与视频共用墙钟 PTS，保 A/V 对齐。 */
internal class AacEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitrateBps: Int = 128_000,
    private val onConfigured: (sampleRate: Int, isStereo: Boolean) -> Unit,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val pcmQueue = ArrayBlockingQueue<PcmChunk>(8)
    private var codec: MediaCodec? = null
    private var encodeThread: Thread? = null
    private var audioInfoSent = false
    private val bytesPerSample = 2 * channelCount.coerceAtLeast(1)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate * bytesPerSample)
        }
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec
        audioInfoSent = false
        encodeThread = Thread({ encodeLoop(mediaCodec) }, "push-aac").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "started rate=$sampleRate ch=$channelCount bitrate=$bitrateBps")
    }

    fun stop() {
        running.set(false)
        pcmQueue.clear()
        encodeThread?.join(1_000L)
        encodeThread = null
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        audioInfoSent = false
    }

    fun offerPcm(data: ByteArray, ptsUs: Long) {
        if (!running.get() || data.isEmpty()) return
        val chunk = PcmChunk(data.copyOf(), ptsUs)
        while (!pcmQueue.offer(chunk) && running.get()) {
            pcmQueue.poll()
        }
    }

    private fun encodeLoop(mediaCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val pcm = pcmQueue.poll()
            if (pcm != null) {
                queueInput(mediaCodec, pcm)
            }
            drainOutput(mediaCodec, info)
            if (pcm == null) {
                try {
                    Thread.sleep(2L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun queueInput(mediaCodec: MediaCodec, chunk: PcmChunk) {
        var offset = 0
        var pts = chunk.ptsUs
        while (offset < chunk.data.size && running.get()) {
            val inIndex = mediaCodec.dequeueInputBuffer(5_000L)
            if (inIndex < 0) return
            val input = mediaCodec.getInputBuffer(inIndex) ?: return
            input.clear()
            val toWrite = minOf(input.capacity(), chunk.data.size - offset)
            input.put(chunk.data, offset, toWrite)
            mediaCodec.queueInputBuffer(inIndex, 0, toWrite, pts, 0)
            val samples = toWrite / bytesPerSample
            pts += samples * 1_000_000L / sampleRate.coerceAtLeast(1)
            offset += toWrite
        }
    }

    private fun drainOutput(mediaCodec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!audioInfoSent) {
                        onConfigured(sampleRate, channelCount >= 2)
                        audioInfoSent = true
                    }
                }
                outIndex >= 0 -> {
                    val output = mediaCodec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!audioInfoSent) {
                                onConfigured(sampleRate, channelCount >= 2)
                                audioInfoSent = true
                            }
                        } else if (audioInfoSent) {
                            val slice = output.duplicate()
                            slice.position(info.offset)
                            slice.limit(info.offset + info.size)
                            onEncoded(slice, info)
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private data class PcmChunk(val data: ByteArray, val ptsUs: Long)

    companion object {
        private const val TAG = "AacEncoder"
    }
}
