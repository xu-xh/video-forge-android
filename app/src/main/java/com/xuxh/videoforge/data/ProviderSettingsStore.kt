package com.xuxh.videoforge.data

import android.content.Context
import com.xuxh.videoforge.AdapterType
import com.xuxh.videoforge.ProviderProfile
import com.xuxh.videoforge.security.ApiKeyCipher

/** 提供商接入配置（持久化到 SharedPreferences，API Key 走 Keystore 加密）。 */
data class ProviderSettings(
    val adapter: AdapterType = AdapterType.GENERIC_REST,
    val baseUrl: String = "",
    val apiKey: String = "",
    val authHeader: String = "Authorization",
    val authPrefix: String = "Bearer",
    val defaultModel: String = "qwen2.5-7b",
    val workflowJson: String = ""
) {
    /** 是否已配置可用的真实服务（base URL 非空且非占位符）。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            !baseUrl.startsWith("https://api.example.com") &&
            !baseUrl.startsWith("http://api.example.com")

    fun toProfile(): ProviderProfile = ProviderProfile(
        baseUrl = baseUrl.trimEnd('/'),
        adapter = adapter,
        authHeader = authHeader.ifBlank { "Authorization" },
        authPrefix = authPrefix,
        workflowJson = workflowJson
    )
}

class ProviderSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("video_forge_prefs", Context.MODE_PRIVATE)

    fun load(): ProviderSettings = ProviderSettings(
        adapter = runCatching { AdapterType.valueOf(prefs.getString(KEY_ADAPTER, AdapterType.GENERIC_REST.name)!!) }
            .getOrDefault(AdapterType.GENERIC_REST),
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
        apiKey = ApiKeyCipher.decrypt(prefs.getString(KEY_API_KEY, "") ?: "") ?: "",
        authHeader = prefs.getString(KEY_AUTH_HEADER, "Authorization") ?: "Authorization",
        authPrefix = prefs.getString(KEY_AUTH_PREFIX, "Bearer") ?: "Bearer",
        defaultModel = prefs.getString(KEY_MODEL, "qwen2.5-7b") ?: "qwen2.5-7b",
        workflowJson = prefs.getString(KEY_WORKFLOW, "") ?: ""
    )

    fun save(settings: ProviderSettings) {
        prefs.edit()
            .putString(KEY_ADAPTER, settings.adapter.name)
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_API_KEY, ApiKeyCipher.encrypt(settings.apiKey))
            .putString(KEY_AUTH_HEADER, settings.authHeader.trim())
            .putString(KEY_AUTH_PREFIX, settings.authPrefix.trim())
            .putString(KEY_MODEL, settings.defaultModel.trim())
            .putString(KEY_WORKFLOW, settings.workflowJson)
            .apply()
    }

    private companion object {
        const val KEY_ADAPTER = "adapter"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key_enc"
        const val KEY_AUTH_HEADER = "auth_header"
        const val KEY_AUTH_PREFIX = "auth_prefix"
        const val KEY_MODEL = "default_model"
        const val KEY_WORKFLOW = "workflow_json"
    }
}