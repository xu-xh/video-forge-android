package com.xuxh.videoforge.ui

import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.VideoJob
import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯逻辑的视频生成任务状态机（无 Android 依赖，可做 JVM 单元测试）。
 *
 * 语义：提交后由 poll() 逐"步"推进，步数与轮询循环一一对应；
 * 首次失败（模拟失败）时降级到低清档重试一次；重试成功后进入 DONE。
 */
internal data class CompatProfile(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val lowEndMode: Boolean
)

internal const val DEFAULT_WIDTH = 1280
internal const val DEFAULT_HEIGHT = 720
internal const val DEFAULT_FPS = 30
internal const val DEFAULT_BITRATE = 3500
internal const val LOW_END_WIDTH = 640
internal const val LOW_END_HEIGHT = 360
internal const val LOW_END_FPS = 24
internal const val LOW_END_BITRATE = 1500
internal const val SIMULATED_OUTPUT_URL_PREFIX = "https://example.com/out-"
internal const val SIMULATED_REMOTE_ID_PREFIX = "r-"
internal const val SIMULATED_FAILURE_ERROR = "模拟生成失败: 码率不足"

internal val DEFAULT_PROFILE = CompatProfile(
    name = "default",
    width = DEFAULT_WIDTH,
    height = DEFAULT_HEIGHT,
    fps = DEFAULT_FPS,
    bitrateKbps = DEFAULT_BITRATE,
    lowEndMode = false,
)

internal val LOW_END_PROFILE = CompatProfile(
    name = "low_end",
    width = LOW_END_WIDTH,
    height = LOW_END_HEIGHT,
    fps = LOW_END_FPS,
    bitrateKbps = LOW_END_BITRATE,
    lowEndMode = true,
)

/** 按轮询步进计算作业的下一状态。只能对非终态作业调用。 */
internal fun transitionForStep(current: VideoJob, step: Int, profile: CompatProfile): VideoJob {
    return when (step) {
        1 -> current.copy(
            status = JobStatus.QUEUED,
            compatTrace = appendTrace(
                current.compatTrace, "queued", profile, "polling heartbeat", current.retryCount
            )
        )
        2, 3 -> current.copy(
            status = JobStatus.PROCESSING,
            remoteId = current.remoteId ?: "$SIMULATED_REMOTE_ID_PREFIX${current.id.take(8)}",
            compatTrace = appendTrace(
                current.compatTrace, "submit", profile, "backend dispatch", current.retryCount
            )
        )
        4 -> if (current.prompt.contains("error", true) && current.retryCount == 0) {
            current.copy(
                status = JobStatus.FAILED,
                error = SIMULATED_FAILURE_ERROR,
                compatTrace = appendTrace(
                    current.compatTrace, "simulate-failure", profile, "decode/processing check", current.retryCount
                )
            )
        } else {
            current.copy(
                status = JobStatus.PROCESSING,
                error = null,
                compatTrace = appendTrace(
                    current.compatTrace, "poll", profile, "decode/processing check", current.retryCount
                )
            )
        }
        5 -> current.copy(
            status = JobStatus.PROCESSING,
            compatTrace = appendTrace(
                current.compatTrace, "poll", profile, "processing warmup", current.retryCount
            )
        )
        else -> current.copy(
            status = JobStatus.DONE,
            outputUrl = current.outputUrl ?: "$SIMULATED_OUTPUT_URL_PREFIX${current.id.take(6)}.mp4",
            error = null,
            compatTrace = appendTrace(
                current.compatTrace, "finish", profile, "done", current.retryCount
            )
        )
    }
}

/** 首次失败后的降级重试：切到低清档、重置为已提交，继续轮询。 */
internal fun fallbackFor(job: VideoJob): VideoJob {
    return job.copy(
        retryCount = job.retryCount + 1,
        status = JobStatus.SUBMITTED,
        lowEndMode = true,
        appliedCompatProfile = LOW_END_PROFILE.name,
        appliedWidth = LOW_END_PROFILE.width,
        appliedHeight = LOW_END_PROFILE.height,
        appliedFps = LOW_END_PROFILE.fps,
        appliedBitrateKbps = LOW_END_PROFILE.bitrateKbps,
        error = null,
        remoteId = null,
        compatTrace = appendTrace(
            job.compatTrace,
            "fallback",
            LOW_END_PROFILE,
            "first attempt failed, fallback to low profile",
            job.retryCount + 1
        )
    )
}

/** 是否允许降级重试：有错误、尚未重试过、当前不是低清档。 */
internal fun shouldFallback(job: VideoJob): Boolean {
    return job.error != null && job.retryCount == 0 && !job.lowEndMode
}

/** 按 Prompt 关键字静态选择兼容档位。 */
internal fun resolveCompatProfile(prompt: String): CompatProfile {
    return if (prompt.contains("low", ignoreCase = true) || prompt.contains("legacy", ignoreCase = true)) {
        LOW_END_PROFILE
    } else {
        DEFAULT_PROFILE
    }
}

/** 向作业的兼容性追踪链追加一段 JSON 记录。 */
internal fun appendTrace(
    previous: String?,
    stage: String,
    profile: CompatProfile,
    reason: String,
    attempt: Int
): String {
    val arr = if (previous.isNullOrBlank()) {
        JSONArray()
    } else {
        try {
            JSONArray(previous)
        } catch (_: Exception) {
            JSONArray()
        }
    }
    arr.put(
        JSONObject().apply {
            put("stage", stage)
            put("attempt", attempt)
            put("profile", profile.name)
            put("low_end_mode", profile.lowEndMode)
            put("width", profile.width)
            put("height", profile.height)
            put("fps", profile.fps)
            put("bitrate", profile.bitrateKbps)
            put("reason", reason)
            put("time_utc", System.currentTimeMillis())
        }
    )
    return arr.toString()
}