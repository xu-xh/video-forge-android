package com.xuxh.videoforge.net

import java.net.HttpURLConnection
import java.net.URL

/** 极简 HTTP 封装（HttpURLConnection，无第三方依赖）。 */
internal object Http {
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        request(url, "POST", body, headers)

    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        request(url, "GET", null, headers)

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            for ((k, v) in headers) {
                if (v.isNotBlank()) conn.setRequestProperty(k, v)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}