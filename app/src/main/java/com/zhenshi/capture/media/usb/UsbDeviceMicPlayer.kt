package com.zhenshi.capture.media.usb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.jiangdg.uac.UACAudioCallBack
import com.jiangdg.uac.UACAudioHandler
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** UAC PCM：预览听声或推流采集；关流等 [UACAudioHandler.isReleased]。 */
@Singleton
class UsbDeviceMicPlayer @Inject constructor() {
    private val mutex = Mutex()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var handler: UACAudioHandler? = null
    private var track: AudioTrack? = null
    private var pcmCallback: UACAudioCallBack? = null
    private val uacStopIssued = AtomicBoolean(false)

    data class MicParams(
        val sampleRate: Int,
        val channelCount: Int,
        val bitResolution: Int,
    )

    fun interface PcmSink {
        fun onPcm(data: ByteArray)
    }

    /**
     * @param pcmSink 非空则只回调 PCM（推流编码）；空则本机 AudioTrack 听声。
     * @return 实际采样参数（推流侧配置 AAC）
     */
    suspend fun start(
        ctrlBlock: USBMonitor.UsbControlBlock,
        pcmSink: PcmSink? = null,
    ): MicParams {
        mutex.withLock {
            stopLocked()
            val h = UACAudioHandler.createHandler(ctrlBlock)
                ?: error("UAC AudioThread 未就绪")
            handler = h

            val params = awaitInitParams(h)
            currentCoroutineContext().ensureActive()

            val callback = if (pcmSink != null) {
                UACAudioCallBack { data ->
                    if (data == null || data.isEmpty()) return@UACAudioCallBack
                    pcmSink.onPcm(data)
                }
            } else {
                val audioTrack = createTrack(params)
                UACAudioCallBack { data ->
                    if (data == null || data.isEmpty()) return@UACAudioCallBack
                    val t = track ?: return@UACAudioCallBack
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return@UACAudioCallBack
                    runCatching { t.write(data, 0, data.size) }
                }.also {
                    audioTrack.play()
                    track = audioTrack
                }
            }
            pcmCallback = callback
            h.addDataCallBack(callback)
            h.startRecording()
            Log.i(
                TAG,
                "mic started mode=${if (pcmSink != null) "capture" else "playback"} " +
                    "rate=${params.sampleRate} ch=${params.channelCount} bit=${params.bitResolution}",
            )
            return params
        }
    }

    suspend fun stop() {
        mutex.withLock { stopLocked() }
    }

    fun halt() {
        val h: UACAudioHandler?
        val t: AudioTrack?
        val cb: UACAudioCallBack?
        synchronized(this) {
            h = handler
            t = track
            cb = pcmCallback
            track = null
            pcmCallback = null
        }
        if (cb != null && h != null) {
            runCatching { h.removeDataCallBack(cb) }
        }
        if (t != null) {
            releaseScope.launch {
                runCatching {
                    t.pause()
                    t.flush()
                    t.stop()
                    t.release()
                }.onFailure { Log.w(TAG, "AudioTrack halt release failed", it) }
            }
        }
        issueUacStop(h)
    }

    private suspend fun stopLocked() {
        val h: UACAudioHandler?
        val t: AudioTrack?
        val cb: UACAudioCallBack?
        synchronized(this) {
            h = handler
            t = track
            cb = pcmCallback
            track = null
            pcmCallback = null
            handler = null
        }

        if (t != null) {
            withContext(Dispatchers.Default) {
                runCatching {
                    t.pause()
                    t.flush()
                    t.stop()
                    t.release()
                }.onFailure { Log.w(TAG, "AudioTrack release failed", it) }
            }
        }

        if (h == null) {
            uacStopIssued.set(false)
            return
        }
        if (cb != null) {
            runCatching { h.removeDataCallBack(cb) }
        }
        if (!h.isReleased) {
            issueUacStop(h)
            awaitReleased(h)
        }
        uacStopIssued.set(false)
        Log.i(TAG, "mic stopped")
    }

    private fun issueUacStop(h: UACAudioHandler?) {
        if (h == null || h.isReleased) return
        if (!uacStopIssued.compareAndSet(false, true)) return
        runCatching { h.stopRecording() }
        runCatching { h.releaseAudioRecord() }
    }

    private suspend fun awaitInitParams(h: UACAudioHandler): MicParams =
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<MicParams>()
            h.initAudioRecord()
            val posted = h.post {
                if (deferred.isCompleted) return@post
                val rate = h.sampleRate
                val channels = h.channelCount
                val bits = h.bitResolution
                if (rate > 0 && channels > 0) {
                    deferred.complete(MicParams(rate, channels, bits))
                } else {
                    deferred.completeExceptionally(
                        IllegalStateException("UAC init 无效 rate=$rate ch=$channels"),
                    )
                }
            }
            if (!posted) {
                deferred.completeExceptionally(IllegalStateException("UAC Handler 已退出"))
            }
            try {
                deferred.await()
            } catch (e: Throwable) {
                runCatching { h.releaseAudioRecord() }
                throw e
            }
        }

    private suspend fun awaitReleased(h: UACAudioHandler) {
        while (!h.isReleased) {
            currentCoroutineContext().ensureActive()
            yield()
        }
    }

    private fun createTrack(params: MicParams): AudioTrack {
        val channelMask = if (params.channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(params.sampleRate, channelMask, encoding)
            .coerceAtLeast(params.sampleRate / 10 * params.channelCount * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(params.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "UsbDeviceMicPlayer"

        fun preloadNativeLibrary() {
            Thread(
                {
                    runCatching { System.loadLibrary("UACAudio") }
                        .onSuccess { Log.i(TAG, "preload UACAudio ok") }
                        .onFailure { Log.w(TAG, "preload UACAudio failed", it) }
                },
                "preload-UACAudio",
            ).apply { isDaemon = true }.start()
        }
    }
}
