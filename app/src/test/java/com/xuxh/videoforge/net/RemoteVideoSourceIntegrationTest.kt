package com.xuxh.videoforge.net

import com.xuxh.videoforge.AdapterType
import com.xuxh.videoforge.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 真机级集成验证（JVM）：用基于 ServerSocket 的迷你 HTTP 服务器模拟
 * Generic REST 与 ComfyUI 契约，跑通 提交 -> 轮询 -> 完成/失败 全链路。
 */
class RemoteVideoSourceIntegrationTest {

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    /** 极简单请求 HTTP/1.1 服务器（仅测试用）。 */
    private class MiniHttpServer(private val handler: (Request) -> Pair<Int, String>) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        @Volatile private var running = true

        fun start() {
            Thread {
                while (running) {
                    val socket = try { server.accept() } catch (_: Exception) { return@Thread }
                    Thread {
                        try {
                            handle(socket)
                        } catch (_: Exception) {
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }.start()
                }
            }.start()
        }

        private fun handle(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts[0]
            val path = parts.getOrElse(1) { "/" }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) readExact(reader, contentLength) else ""

            val (status, resp) = try {
                handler(Request(method, path, headers, body))
            } catch (e: Exception) {
                500 to "{\"error\":\"server error: ${e.message}\"}"
            }
            val bytes = resp.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status OK\r\n".toByteArray())
            out.write("Content-Type: application/json\r\n".toByteArray())
            out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        private fun readExact(reader: BufferedReader, n: Int): String {
            val buf = CharArray(n)
            var read = 0
            while (read < n) {
                val got = reader.read(buf, read, n - read)
                if (got < 0) break
                read += got
            }
            return String(buf, 0, read)
        }

        fun stop() {
            running = false
            try { server.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun `generic rest source submits and polls to completion against local server`() {
        val expectedId = "job-123"
        var pollCount = 0

        val server = MiniHttpServer { req ->
            when {
                req.method == "POST" && req.path == "/videos" -> {
                    assertTrue(req.body.contains("\"prompt\""))
                    assertTrue(req.body.contains("\"model\""))
                    assertEquals("Bearer test-key", req.headers["authorization"])
                    200 to """{"id":"$expectedId"}"""
                }
                req.method == "GET" && req.path == "/videos/$expectedId" -> {
                    pollCount++
                    if (pollCount <= 2) 200 to """{"status":"processing"}"""
                    else 200 to """{"status":"completed","output_url":"https://cdn.test/v.mp4"}"""
                }
                else -> 404 to """{"error":"not found"}"""
            }
        }
        server.start()
        try {
            val profile = ProviderProfile(
                baseUrl = "http://127.0.0.1:${server.port}",
                adapter = AdapterType.GENERIC_REST,
                authHeader = "Authorization",
                authPrefix = "Bearer",
                workflowJson = "",
                apiKey = "test-key"
            )
            val source = RemoteVideoSourceFactory.create(profile)

            val submit = source.submit("a kite over the sea", "m1")
            assertTrue(submit.submitted)
            assertEquals(expectedId, submit.remoteId)

            assertEquals(RemoteStatus.PROCESSING, source.poll(submit.remoteId).status)
            assertEquals(RemoteStatus.PROCESSING, source.poll(submit.remoteId).status)

            val done = source.poll(submit.remoteId)
            assertEquals(RemoteStatus.DONE, done.status)
            assertEquals("https://cdn.test/v.mp4", done.outputUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `comfy source submits with prompt injection and maps history to view url`() {
        val promptId = "p-abc-123"
        val workflow = """{
            "3": {"class_type": "CLIPTextEncode", "inputs": {"text": "placeholder", "clip": ["4", 0]}},
            "17": {"class_type": "VHS_VideoCombine", "inputs": {"fps": 8, "images": ["9", 0]}}
        }"""

        val server = MiniHttpServer { req ->
            when {
                req.method == "POST" && req.path == "/prompt" -> {
                    assertTrue(req.body.contains("\"text\":\"my movie prompt\""))
                    assertTrue(req.body.contains("\"client_id\""))
                    200 to """{"prompt_id":"$promptId"}"""
                }
                req.method == "GET" && req.path == "/history/$promptId" -> {
                    200 to """{
                        "$promptId": {
                          "status": {"completed": true, "status_str": "success"},
                          "outputs": {
                            "17": {"gifs": [{"filename": "clip_00001_.mp4", "subfolder": "", "type": "output"}]}
                          }
                        }
                    }"""
                }
                else -> 404 to """{"error":"not found"}"""
            }
        }
        server.start()
        try {
            val profile = ProviderProfile(
                baseUrl = "http://127.0.0.1:${server.port}",
                adapter = AdapterType.COMFY_UI,
                authHeader = "Authorization",
                authPrefix = "Bearer",
                workflowJson = workflow,
                apiKey = ""
            )
            val source = RemoteVideoSourceFactory.create(profile)

            val submit = source.submit("my movie prompt", "m1")
            assertTrue(submit.submitted)
            assertEquals(promptId, submit.remoteId)

            val done = source.poll(submit.remoteId)
            assertEquals(RemoteStatus.DONE, done.status)
            assertEquals(
                "http://127.0.0.1:${server.port}/view?filename=clip_00001_.mp4&subfolder=&type=output",
                done.outputUrl
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `comfy source reports execution error as FAILED`() {
        val promptId = "p-err"
        val server = MiniHttpServer { req ->
            when {
                req.method == "POST" && req.path == "/prompt" -> 200 to """{"prompt_id":"$promptId"}"""
                req.method == "GET" && req.path == "/history/$promptId" -> {
                    200 to """{
                        "$promptId": {
                          "status": {"completed": false, "status_str": "error",
                            "messages": [["execution_error", "CUDA out of memory"]]},
                          "outputs": {}
                        }
                    }"""
                }
                else -> 404 to """{"error":"not found"}"""
            }
        }
        server.start()
        try {
            val profile = ProviderProfile(
                baseUrl = "http://127.0.0.1:${server.port}",
                adapter = AdapterType.COMFY_UI,
                authHeader = "Authorization",
                authPrefix = "Bearer",
                workflowJson = """{"3":{"class_type":"CLIPTextEncode","inputs":{"text":"x"}}}""",
                apiKey = ""
            )
            val source = RemoteVideoSourceFactory.create(profile)
            val submit = source.submit("boom", "m1")
            assertTrue(submit.submitted)

            val failed = source.poll(submit.remoteId)
            assertEquals(RemoteStatus.FAILED, failed.status)
            assertTrue(failed.error!!.contains("CUDA out of memory"))
        } finally {
            server.stop()
        }
    }
}