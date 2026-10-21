package com.qmusic.wear.data.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** 一次识曲录音的产物：16kHz 单声道 16bit PCM + 实际录制时长 */
data class CapturedAudio(
    val pcm16Mono16k: ByteArray,
    val durationMs: Long,
) {
    fun base64(): String = Base64.encodeToString(pcm16Mono16k, Base64.NO_WRAP)
}

/**
 * 识曲用录音工具：录音 + 降采样 + base64，全部在 IO 线程完成，不涉及任何协议明文
 * （识别接口调用在音乐源插件的 recognizeSong handler 里，见 source 目录下的 js 插件）。
 *
 * 采集固定用 44.1kHz（几乎所有 Wear OS 设备都支持该标准率），再线性抽取降采样到
 * 16kHz 单声道——直接以 16kHz 采集在部分低端手表上会初始化失败，故不直采。
 */
object AudioCapture {

    /** 识别接口通用要求：16kHz 单声道 16bit PCM */
    const val TARGET_SAMPLE_RATE = 16000
    private const val CAPTURE_SAMPLE_RATE = 44100

    /** 默认录制时长：3~6 秒足够指纹匹配，也控制上传体积与耗电 */
    const val DEFAULT_DURATION_MS = 6000L

    /** 硬上限：防止调用方误传过大值导致长时间占用麦克风耗电 */
    const val MAX_DURATION_MS = 8000L

    /** 设备是否有麦克风硬件（部分 Wear OS 设备无麦克风，需前置探测隐藏入口） */
    fun hasMicrophone(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    /** 是否已授予录音权限 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 录制 [durationMs]（截断到 [MAX_DURATION_MS]）环境音，产出 [CapturedAudio]。
     * 未授予录音权限时抛 [IllegalStateException]，调用方需先经 [hasPermission] 检查并引导授权。
     */
    suspend fun record(context: Context, durationMs: Long = DEFAULT_DURATION_MS): CapturedAudio =
        withContext(Dispatchers.IO) {
            check(hasPermission(context)) { "未授予录音权限" }
            val cappedMs = durationMs.coerceIn(1000L, MAX_DURATION_MS)
            val minBuf = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(2048)

            @Suppress("MissingPermission") // 上方 hasPermission 已校验，check 失败直接抛异常
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }

            try {
                val pcm44 = ByteArrayOutputStream()
                val buf = ShortArray(minBuf / 2)
                recorder.startRecording()
                try {
                    val deadline = System.currentTimeMillis() + cappedMs
                    while (System.currentTimeMillis() < deadline) {
                        coroutineContext.ensureActive()
                        val n = recorder.read(buf, 0, buf.size)
                        if (n > 0) {
                            var i = 0
                            while (i < n) {
                                val v = buf[i]
                                pcm44.write(v.toInt() and 0xFF)
                                pcm44.write((v.toInt() shr 8) and 0xFF)
                                i++
                            }
                        }
                    }
                } finally {
                    runCatching { recorder.stop() }
                }
                CapturedAudio(
                    pcm16Mono16k = downmixTo16k(pcm44.toByteArray()),
                    durationMs = cappedMs,
                )
            } finally {
                runCatching { recorder.release() }
            }
        }

    /** 44.1kHz 16bit 单声道 PCM 线性抽取降采样到 16kHz（每 ~2.756 样本取一次，简单可靠，无需 FFT 库） */
    private fun downmixTo16k(pcm44Le: ByteArray): ByteArray {
        val srcSamples = pcm44Le.size / 2
        val ratio = CAPTURE_SAMPLE_RATE.toFloat() / TARGET_SAMPLE_RATE
        val outSamples = (srcSamples / ratio).toInt().coerceAtLeast(0)
        // 16bit 小端单声道：一个样本占两个字节，降采样只做"按索引取样本"，字节原样拷贝即可保留小端序
        val out = ByteArray(outSamples * 2)
        var i = 0
        var pos = 0f
        while (i < outSamples) {
            val srcIdx = pos.toInt().coerceAtMost(srcSamples - 1)
            out[i * 2] = pcm44Le[srcIdx * 2]
            out[i * 2 + 1] = pcm44Le[srcIdx * 2 + 1]
            pos += ratio
            i++
        }
        return out
    }
}
