package com.xuxh.videoforge.net

import com.xuxh.videoforge.AdapterType
import com.xuxh.videoforge.ProviderProfile
import org.json.JSONObject
import java.util.UUID

/** 提交结果：remoteId 用于后续轮询。 */
data class SubmitResult(val remoteId: String, val submitted: Boolean, val error: String?)

/** 轮询结果。 */
data class PollResult(val status: RemoteStatus, val outputUrl: String?, val error: String?, val raw: String?)

/** 远端视频生成服务适配器（docs/provider-contract.md）。 */
interface RemoteVideoSource {
    fun submit(prompt: String, model: String): SubmitResult
    fun poll(remoteId: String): PollResult
}

/**
 * Generic async REST：POST {base}/videos -> GET {base}/videos/{id}。
 * 提交响应须含 id/request_id/job_id/prediction_id；轮询按 status/state 映射；
 * 输出字段取 output_url/video_url/url。
 */
class GenericRestVideoSource(
    private val profile: ProviderProfile
) : RemoteVideoSource {
    init {
        require(profile.adapter == AdapterType.GENERIC_REST)
    }
    private val base = profile.baseUrl.trimEnd('/')

    override fun submit(prompt: String, model: String): SubmitResult {
        val body = JSONObject()
            .put("prompt", prompt)
            .put("model", model)
            .put("width", 1024)
            .put("height", 576)
            .put("duration", 5)
            .put("size", "1024x576")
        val (code, text) = Http.post("$base/videos", body.toString(), headers())
        if (code !in 200..299) {
            return SubmitResult("", false, "提交失败 HTTP $code: ${RemoteParsers.errorFrom(text).ifBlank { text.take(200) }}")
        }
        val id = RemoteParsers.resolveId(text)
        return if (id.isNullOrEmpty()) {
            SubmitResult("", false, "响应缺少 id/request_id/job_id/prediction_id")
        } else {
            SubmitResult(id, true, null)
        }
    }

    override fun poll(remoteId: String): PollResult {
        val (code, text) = Http.get("$base/videos/$remoteId", headers())
        if (code !in 200..299) {
            return PollResult(RemoteStatus.PROCESSING, null, "轮询失败 HTTP $code", text.take(200))
        }
        return RemoteParsers.parseGenericPoll(text)
    }

    private fun headers(): Map<String, String> {
        val key = profile.apiKey
        return if (key.isNotEmpty()) {
            mapOf(profile.authHeader to "${profile.authPrefix} $key".trim())
        } else {
            emptyMap()
        }
    }
}

/**
 * ComfyUI：POST {base}/prompt -> GET {base}/history/{prompt_id}；
 * 输出媒体链接使用 {base}/view。
 */
class ComfyUIVideoSource(
    private val profile: ProviderProfile
) : RemoteVideoSource {
    init {
        require(profile.adapter == AdapterType.COMFY_UI)
    }
    private val base = profile.baseUrl.trimEnd('/')
    private val clientId = UUID.randomUUID().toString()

    override fun submit(prompt: String, model: String): SubmitResult {
        val workflow = try {
            val obj = JSONObject(profile.workflowJson)
            injectPrompt(obj, prompt)
            obj
        } catch (e: Exception) {
            return SubmitResult("", false, "workflow JSON 无效或缺少可注入的 text 节点: ${e.message}")
        }
        val body = JSONObject().put("prompt", workflow).put("client_id", clientId)
        val (code, text) = Http.post("$base/prompt", body.toString(), headers())
        if (code !in 200..299) {
            return SubmitResult("", false, "提交失败 HTTP $code: ${RemoteParsers.errorFrom(text).ifBlank { text.take(200) }}")
        }
        val id = RemoteParsers.resolveId(text)
        return if (id.isNullOrEmpty()) {
            SubmitResult("", false, "响应缺少 prompt_id")
        } else {
            SubmitResult(id, true, null)
        }
    }

    override fun poll(remoteId: String): PollResult {
        val (code, text) = Http.get("$base/history/$remoteId", headers())
        // 404 表示提示词尚未入队，视为排队中
        if (code == 404) return PollResult(RemoteStatus.QUEUED, null, null, "404")
        if (code !in 200..299) {
            return PollResult(RemoteStatus.PROCESSING, null, "轮询失败 HTTP $code", text.take(200))
        }
        return RemoteParsers.parseComfyHistory(text, remoteId, base)
    }

    /** 把用户 Prompt 注入工作流中所有含 text 输入的节点（API 格式工作流常见于 CLIPTextEncode）。 */
    private fun injectPrompt(workflow: JSONObject, prompt: String) {
        var injected = false
        for (key in workflow.keys()) {
            val node = workflow.optJSONObject(key) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            if (inputs.has("text")) {
                inputs.put("text", prompt)
                injected = true
            }
        }
        if (!injected) throw IllegalStateException("工作流中没有任何 text 输入节点")
    }

    private fun headers(): Map<String, String> {
        val key = profile.apiKey
        return if (key.isNotEmpty()) {
            mapOf(profile.authHeader to "${profile.authPrefix} $key".trim())
        } else {
            emptyMap()
        }
    }
}

object RemoteVideoSourceFactory {
    fun create(profile: ProviderProfile): RemoteVideoSource =
        when (profile.adapter) {
            AdapterType.GENERIC_REST -> GenericRestVideoSource(profile)
            AdapterType.COMFY_UI -> ComfyUIVideoSource(profile)
        }
}