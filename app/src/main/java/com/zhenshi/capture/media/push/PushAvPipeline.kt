package com.zhenshi.capture.media.push

import android.util.Log
import com.pedro.rtmp.rtmp.RtmpClient
import com.zhenshi.capture.media.VideoProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** H.264 + AAC → [RtmpClient]。 */
internal class PushAvPipeline(
    private val rtmp: RtmpClient,
    private val profile: VideoProfile,
    private val videoBitrateBps: Int,
) {
    private val active = AtomicBoolean(false)
    private val basePtsNs = AtomicLong(0L)
    private var videoEncoder: H264Nv21Encoder? = null
    private var audioEncoder: AacEncoder? = null

    fun start(sampleRate: Int, channelCount: Int) {
        stop()
        basePtsNs.set(0L)
        val video = H264Nv21Encoder(
            width = profile.width,
            height = profile.height,
            fps = profile.fps.coerceIn(15, 60),
            bitrateBps = videoBitrateBps,
            onConfigured = { sps, pps ->
                rtmp.setVideoInfo(sps, pps, null)
                Log.i(TAG, "video info sps=${sps.remaining()} pps=${pps.remaining()}")
            },
            onEncoded = { buffer, info ->
                if (active.get()) {
                    rtmp.sendVideo(buffer, info)
                }
            },
        )
        val audio = AacEncoder(
            sampleRate = sampleRate,
            channelCount = channelCount,
            onConfigured = { rate, stereo ->
                rtmp.setAudioInfo(rate, stereo)
                Log.i(TAG, "audio info rate=$rate stereo=$stereo")
            },
            onEncoded = { buffer, info ->
                if (active.get()) {
                    rtmp.sendAudio(buffer, info)
                }
            },
        )
        videoEncoder = video
        audioEncoder = audio
        video.start()
        audio.start()
        active.set(true)
        Log.i(
            TAG,
            "pipeline started ${profile.label} videoBitrate=$videoBitrateBps " +
                "audio=${sampleRate}Hz/${channelCount}ch",
        )
    }

    /** 无 UAC 时仅视频。 */
    fun startVideoOnly() {
        stop()
        basePtsNs.set(0L)
        val video = H264Nv21Encoder(
            width = profile.width,
            height = profile.height,
            fps = profile.fps.coerceIn(15, 60),
            bitrateBps = videoBitrateBps,
            onConfigured = { sps, pps ->
                rtmp.setVideoInfo(sps, pps, null)
            },
            onEncoded = { buffer, info ->
                if (active.get()) rtmp.sendVideo(buffer, info)
            },
        )
        videoEncoder = video
        audioEncoder = null
        video.start()
        active.set(true)
        Log.i(TAG, "pipeline started video-only ${profile.label}")
    }

    fun stop() {
        active.set(false)
        videoEncoder?.stop()
        audioEncoder?.stop()
        videoEncoder = null
        audioEncoder = null
    }

    fun offerNv21(data: ByteArray, width: Int, height: Int) {
        if (!active.get()) return
        videoEncoder?.offerNv21(data, width, height, ptsUs())
    }

    fun offerPcm(data: ByteArray) {
        if (!active.get()) return
        audioEncoder?.offerPcm(data, ptsUs())
    }

    fun updateVideoBitrate(bitrateBps: Int) {
        videoEncoder?.updateBitrate(bitrateBps)
    }

    private fun ptsUs(): Long {
        val now = System.nanoTime()
        val base = basePtsNs.updateAndGet { current ->
            if (current == 0L) now else current
        }
        return (now - base) / 1_000L
    }

    companion object {
        private const val TAG = "PushAvPipeline"
    }
}
