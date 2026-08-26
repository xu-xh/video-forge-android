package com.xuxh.videoforge.net

import org.json.JSONObject
import java.net.URLEncoder

/** 远端状态（与 UI 展示映射的中间态）。 */
enum class RemoteStatus { QUEUED, PROCESSING, DONE, FAILED }

/**
 * 纯解析函数：从各提供商响应中提取提交 ID / 状态 / 输出 URL。
 * 与 HTTP 无关，便于 JVM 单元测试。
 */
internal object RemoteParsers {

    fun resolveId(text: String): String? = try {
        val obj = JSONObject(text)
        listOf("id", "request_id", "job_id", "prediction_id", "prompt_id")
            .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotEmpty() } }
    } catch (_: Exception) {
        null
    }

    /** Generic REST：status/state 文本映射（docs/provider-contract.md 词汇表）。 */
    fun mapStatus(raw: String): RemoteStatus = when (raw.trim().lowercase()) {
        "queued", "pending", "submitted", "in_queue", "waiting" -> RemoteStatus.QUEUED
        "processing", "running", "in_progress", "started", "generating" -> RemoteStatus.PROCESSING
        "completed", "succeeded", "success", "done", "finished" -> RemoteStatus.DONE
        "failed", "error", "cancelled", "canceled", "cancelled_by_user" -> RemoteStatus.FAILED
        else -> RemoteStatus.PROCESSING
    }

    /** Generic REST：从轮询响应中取状态；DONE 时取输出 URL。 */
    fun parseGenericPoll(text: String): PollResult = try {
        val obj = JSONObject(text)
        val statusRaw = obj.optString("status").ifEmpty { obj.optString("state") }
        val status = mapStatus(statusRaw)
        if (status == RemoteStatus.DONE) {
            val out = listOf("output_url", "video_url", "url", "result_url")
                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotEmpty() } }
            PollResult(RemoteStatus.DONE, out, null, text)
        } else {
            PollResult(status, null, null, text)
        }
    } catch (e: Exception) {
        PollResult(RemoteStatus.PROCESSING, null, "状态解析失败", text)
    }

    /** Generic REST：从错误响应中提取错误文案（兼容字符串/对象/null 三种形态）。 */
    fun errorFrom(text: String): String = try {
        val obj = JSONObject(text)
        val raw = obj.opt("error")
        when {
            raw == null || raw === JSONObject.NULL -> ""
            raw is JSONObject -> raw.optString("message").ifEmpty { raw.toString() }
            raw is String -> raw
            else -> raw.toString()
        }
    } catch (_: Exception) {
        ""
    }.takeIf { it.isNotBlank() } ?: text.take(200)

    /**
     * ComfyUI：解析 /history/{prompt_id} 响应。
     * 未出现、已完成但无输出、执行出错分别映射到 QUEUED / PROCESSING / FAILED。
     */
    fun parseComfyHistory(text: String, promptId: String, baseUrl: String): PollResult = try {
        val root = JSONObject(text)
        val entry = root.optJSONObject(promptId)
        if (entry == null) return PollResult(RemoteStatus.QUEUED, null, null, text)

        val status = entry.optJSONObject("status") ?: JSONObject()
        val messages = status.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONArray(i)
                if (msg != null && msg.length() >= 2 && msg.optString(0) == "execution_error") {
                    return PollResult(RemoteStatus.FAILED, null, msg.optString(1).take(200), text)
                }
            }
        }
        if (status.optBoolean("completed", false)) {
            val outputs = entry.optJSONObject("outputs") ?: JSONObject()
            val url = firstOutputUrl(outputs, baseUrl)
            return if (url != null) {
                PollResult(RemoteStatus.DONE, url, null, text)
            } else {
                PollResult(RemoteStatus.PROCESSING, null, "已完成但未发现输出媒体", text)
            }
        }
        val statusStr = status.optString("status_str")
        if (statusStr.isNotBlank() && statusStr in listOf("error", "failed")) {
            return PollResult(RemoteStatus.FAILED, null, statusStr, text)
        }
        PollResult(RemoteStatus.PROCESSING, null, statusStr.takeIf { it.isNotBlank() }, text)
    } catch (e: Exception) {
        PollResult(RemoteStatus.PROCESSING, null, "历史解析失败: ${e.message}", text)
    }

    /** 在工作流输出中查找第一个媒体文件，构造 ComfyUI /view 链接（兼容 images/gifs/videos）。 */
    fun firstOutputUrl(outputs: JSONObject, baseUrl: String): String? {
        for (node in outputs.keys()) {
            val nodeOut = outputs.optJSONObject(node) ?: continue
            for (arrKey in listOf("images", "gifs", "videos")) {
                val arr = nodeOut.optJSONArray(arrKey) ?: continue
                for (i in 0 until arr.length()) {
                    val media = arr.optJSONObject(i) ?: continue
                    val filename = media.optString("filename")
                    if (filename.isEmpty()) continue
                    val subfolder = media.optString("subfolder")
                    val type = media.optString("type", "output")
                    return buildViewUrl(baseUrl, filename, subfolder, type)
                }
            }
        }
        return null
    }

    fun buildViewUrl(baseUrl: String, filename: String, subfolder: String, type: String): String =
        "$baseUrl/view?filename=${enc(filename)}&subfolder=${enc(subfolder)}&type=${enc(type)}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}