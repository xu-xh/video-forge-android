package com.xuxh.videoforge.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RemoteParsers 纯解析函数的 JVM 单元测试（覆盖 Generic REST 与 ComfyUI 两类契约）。 */
class RemoteParsersTest {

    @Test
    fun `resolveId picks first known key`() {
        assertEquals("job_42", RemoteParsers.resolveId("""{"job_id":"job_42"}"""))
        assertEquals("pred_7", RemoteParsers.resolveId("""{"prediction_id":"pred_7"}"""))
        assertEquals("req_1", RemoteParsers.resolveId("""{"request_id":"req_1"}"""))
        assertEquals("abc", RemoteParsers.resolveId("""{"id":"abc","job_id":"ignored"}"""))
        assertEquals("p-9", RemoteParsers.resolveId("""{"prompt_id":"p-9"}"""))
        assertNull(RemoteParsers.resolveId("""{"status":"ok"}"""))
        assertNull(RemoteParsers.resolveId("not-json"))
    }

    @Test
    fun `mapStatus vocabulary per provider contract`() {
        assertEquals(RemoteStatus.QUEUED, RemoteParsers.mapStatus("queued"))
        assertEquals(RemoteStatus.QUEUED, RemoteParsers.mapStatus("PENDING"))
        assertEquals(RemoteStatus.QUEUED, RemoteParsers.mapStatus("submitted"))
        assertEquals(RemoteStatus.PROCESSING, RemoteParsers.mapStatus("processing"))
        assertEquals(RemoteStatus.PROCESSING, RemoteParsers.mapStatus("running"))
        assertEquals(RemoteStatus.PROCESSING, RemoteParsers.mapStatus("in_progress"))
        assertEquals(RemoteStatus.DONE, RemoteParsers.mapStatus("completed"))
        assertEquals(RemoteStatus.DONE, RemoteParsers.mapStatus("succeeded"))
        assertEquals(RemoteStatus.FAILED, RemoteParsers.mapStatus("failed"))
        assertEquals(RemoteStatus.FAILED, RemoteParsers.mapStatus("cancelled"))
        // 未知状态默认按处理中处理（轮询继续）
        assertEquals(RemoteStatus.PROCESSING, RemoteParsers.mapStatus("mystery_state"))
    }

    @Test
    fun `generic poll maps status and extracts output url variants`() {
        val queued = RemoteParsers.parseGenericPoll("""{"status":"queued"}""")
        assertEquals(RemoteStatus.QUEUED, queued.status)
        assertNull(queued.outputUrl)

        val processing = RemoteParsers.parseGenericPoll("""{"state":"in_progress"}""")
        assertEquals(RemoteStatus.PROCESSING, processing.status)

        val doneOutputUrl = RemoteParsers.parseGenericPoll(
            """{"status":"completed","output_url":"https://cdn.example.com/v.mp4"}"""
        )
        assertEquals(RemoteStatus.DONE, doneOutputUrl.status)
        assertEquals("https://cdn.example.com/v.mp4", doneOutputUrl.outputUrl)

        val doneVideoUrl = RemoteParsers.parseGenericPoll(
            """{"status":"succeeded","video_url":"https://cdn.example.com/v2.mp4"}"""
        )
        assertEquals("https://cdn.example.com/v2.mp4", doneVideoUrl.outputUrl)

        val failed = RemoteParsers.parseGenericPoll("""{"status":"failed"}""")
        assertEquals(RemoteStatus.FAILED, failed.status)

        // 完成但无输出 URL：DONE 但 outputUrl 为 null（由上层处理）
        val doneNoUrl = RemoteParsers.parseGenericPoll("""{"status":"completed"}""")
        assertEquals(RemoteStatus.DONE, doneNoUrl.status)
        assertNull(doneNoUrl.outputUrl)
    }

    @Test
    fun `comfy_history_not_yet_queued_maps_to_QUEUED`() {
        val result = RemoteParsers.parseComfyHistory("{}", "p1", "http://u:8188")
        assertEquals(RemoteStatus.QUEUED, result.status)
    }

    @Test
    fun `comfy_history_execution_error_maps_to_FAILED`() {
        val json = """{
            "p1": {
              "status": {
                "completed": false,
                "status_str": "error",
                "messages": [["execution_error", "node crashed: OOM"]]
              },
              "outputs": {}
            }
          }"""
        val result = RemoteParsers.parseComfyHistory(json, "p1", "http://u:8188")
        assertEquals(RemoteStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("OOM"))
    }

    @Test
    fun `comfy_history_completed_with_image_output_builds_view_url`() {
        val json = """{
            "p1": {
              "status": {"completed": true, "status_str": "success"},
              "outputs": {
                "9": {
                  "images": [{"filename": "a_00001_.png", "subfolder": "", "type": "output"}]
                }
              }
            }
          }"""
        val result = RemoteParsers.parseComfyHistory(json, "p1", "http://10.0.0.20:8188")
        assertEquals(RemoteStatus.DONE, result.status)
        assertEquals(
            "http://10.0.0.20:8188/view?filename=a_00001_.png&subfolder=&type=output",
            result.outputUrl
        )
    }

    @Test
    fun `comfy_history_completed_with_gif_video_output_builds_view_url`() {
        val json = """{
            "p1": {
              "status": {"completed": true},
              "outputs": {
                "17": {
                  "gifs": [{"filename": "clip_00001_.mp4", "subfolder": "out", "type": "output"}]
                }
              }
            }
          }"""
        val result = RemoteParsers.parseComfyHistory(json, "p1", "http://10.0.0.20:8188")
        assertEquals(RemoteStatus.DONE, result.status)
        assertEquals(
            "http://10.0.0.20:8188/view?filename=clip_00001_.mp4&subfolder=out&type=output",
            result.outputUrl
        )
    }

    @Test
    fun `comfy_history_completed_but_no_media_stays_PROCESSING`() {
        val json = """{"p1":{"status":{"completed":true},"outputs":{"9":{"images":[]}}}}"""
        val result = RemoteParsers.parseComfyHistory(json, "p1", "http://u:8188")
        assertEquals(RemoteStatus.PROCESSING, result.status)
    }

    @Test
    fun `buildViewUrl url-encodes segments`() {
        val url = RemoteParsers.buildViewUrl(
            "http://u:8188",
            "my file.mp4",
            "sub/folder",
            "output"
        )
        assertEquals(
            "http://u:8188/view?filename=my%20file.mp4&subfolder=sub%2Ffolder&type=output",
            url
        )
    }

    @Test
    fun `errorFrom extracts message or falls back to raw text`() {
        assertEquals("bad key", RemoteParsers.errorFrom("""{"error":"bad key"}"""))
        assertEquals(
            "rate limited",
            RemoteParsers.errorFrom("""{"error":{"message":"rate limited"}}""")
        )
        assertNotNull(RemoteParsers.errorFrom("plain text error body"))
        // 结构化错误为空时回退到原始响应体
        assertEquals("""{"error":""}""", RemoteParsers.errorFrom("""{"error":""}"""))
    }

    @Test
    fun `firstOutputUrl scans images gifs videos in order`() {
        val outputs = JSONObject(
            """{"A":{"images":[],"gifs":[{"filename":"v.mp4"}]},"B":{"videos":[{"filename":"w.mp4","subfolder":"s"}]}}"""
        )
        val url = RemoteParsers.firstOutputUrl(outputs, "http://u:8188")
        assertEquals("http://u:8188/view?filename=v.mp4&subfolder=&type=output", url)
    }
}