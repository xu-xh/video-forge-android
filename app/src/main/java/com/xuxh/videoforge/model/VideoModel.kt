package com.xuxh.videoforge

enum class JobStatus {
    SUBMITTED,
    QUEUED,
    PROCESSING,
    DONE,
    FAILED
}

enum class AdapterType { GENERIC_REST, COMFY_UI }

data class ProviderProfile(
    val baseUrl: String,
    val adapter: AdapterType,
    val authHeader: String,
    val authPrefix: String,
    val workflowJson: String,
    /** 仅存在于内存，不随作业持久化；由配置中心在请求时注入。 */
    val apiKey: String = "",
)

data class VideoJob(
    val id: String,
    val remoteId: String? = null,
    val prompt: String,
    val model: String,
    val status: JobStatus = JobStatus.SUBMITTED,
    val outputUrl: String? = null,
    val error: String? = null,
    val compatTrace: String? = null,
    val retryCount: Int = 0,
    val lowEndMode: Boolean = false,
    val appliedCompatProfile: String? = null,
    val appliedWidth: Int? = null,
    val appliedHeight: Int? = null,
    val appliedFps: Int? = null,
    val appliedBitrateKbps: Int? = null,
)
