package com.xuxh.videoforge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VideoJobEntity(
    val id: String,
    val remoteId: String? = null,
    val prompt: String,
    val model: String,
    val status: String,
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
    val profileBaseUrl: String,
    val adapter: String,
    val authHeader: String,
    val authPrefix: String,
    val workflowJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

interface VideoJobDao {
    suspend fun getAll(): List<VideoJobEntity>
    suspend fun upsert(job: VideoJobEntity)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

class FileVideoJobDao(private val context: Context) : VideoJobDao {
    private val storeFile = File(context.filesDir, "videoforge_jobs.json")

    override suspend fun getAll(): List<VideoJobEntity> {
        return readAll().sortedByDescending { it.updatedAt }
    }

    override suspend fun upsert(job: VideoJobEntity) {
        val list = readAll()
        val fixed = job.copy(updatedAt = System.currentTimeMillis())
        val index = list.indexOfFirst { it.id == fixed.id }
        if (index >= 0) {
            list[index] = fixed
        } else {
            list.add(fixed)
        }
        writeAll(list)
    }

    override suspend fun deleteById(id: String) {
        val list = readAll().filterNot { it.id == id }
        writeAll(list)
    }

    override suspend fun clear() {
        writeAll(emptyList())
    }

    private fun readAll(): MutableList<VideoJobEntity> {
        if (!storeFile.exists()) return mutableListOf()
        return try {
            val raw = storeFile.readText()
            if (raw.isBlank()) return mutableListOf()
            val arr = JSONArray(raw)
            val out = ArrayList<VideoJobEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    VideoJobEntity(
                        id = obj.optString("id"),
                        remoteId = obj.optString("remoteId").takeIf { it.isNotEmpty() },
                        prompt = obj.optString("prompt"),
                        model = obj.optString("model"),
                        status = obj.optString("status"),
                        outputUrl = obj.optString("outputUrl").takeIf { it.isNotEmpty() },
                        error = obj.optString("error").takeIf { it.isNotEmpty() },
                        compatTrace = obj.optString("compatTrace").takeIf { it.isNotBlank() },
                        retryCount = obj.optInt("retryCount", 0),
                        lowEndMode = obj.optBoolean("lowEndMode", false),
                        appliedCompatProfile = obj.optString("appliedCompatProfile").takeIf { it.isNotEmpty() },
                        appliedWidth = if (obj.has("appliedWidth") && !obj.isNull("appliedWidth")) obj.optInt("appliedWidth") else null,
                        appliedHeight = if (obj.has("appliedHeight") && !obj.isNull("appliedHeight")) obj.optInt("appliedHeight") else null,
                        appliedFps = if (obj.has("appliedFps") && !obj.isNull("appliedFps")) obj.optInt("appliedFps") else null,
                        appliedBitrateKbps = if (obj.has("appliedBitrateKbps") && !obj.isNull("appliedBitrateKbps")) obj.optInt("appliedBitrateKbps") else null,
                        profileBaseUrl = obj.optString("profileBaseUrl"),
                        adapter = obj.optString("adapter"),
                        authHeader = obj.optString("authHeader"),
                        authPrefix = obj.optString("authPrefix"),
                        workflowJson = obj.optString("workflowJson"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeAll(items: List<VideoJobEntity>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("remoteId", item.remoteId)
                put("prompt", item.prompt)
                put("model", item.model)
                put("status", item.status)
                put("outputUrl", item.outputUrl)
                put("error", item.error)
                put("compatTrace", item.compatTrace)
                put("retryCount", item.retryCount)
                put("lowEndMode", item.lowEndMode)
                put("appliedCompatProfile", item.appliedCompatProfile)
                put("appliedWidth", item.appliedWidth)
                put("appliedHeight", item.appliedHeight)
                put("appliedFps", item.appliedFps)
                put("appliedBitrateKbps", item.appliedBitrateKbps)
                put("profileBaseUrl", item.profileBaseUrl)
                put("adapter", item.adapter)
                put("authHeader", item.authHeader)
                put("authPrefix", item.authPrefix)
                put("workflowJson", item.workflowJson)
                put("updatedAt", item.updatedAt)
            }
            arr.put(obj)
        }
        storeFile.writeText(arr.toString())
    }
}

object VideoForgeDatabase {
    private var dao: VideoJobDao? = null

    fun get(context: Context): VideoJobDao {
        if (dao == null) {
            dao = FileVideoJobDao(context)
        }
        return dao!!
    }
}

fun JobStatus.toPersistence(): String = name

fun String.toJobStatus(): JobStatus = runCatching { JobStatus.valueOf(this) }.getOrDefault(JobStatus.SUBMITTED)

fun VideoJobEntity.toDomain(): VideoJob = VideoJob(
    id = id,
    remoteId = remoteId,
    prompt = prompt,
    model = model,
    status = status.toJobStatus(),
    outputUrl = outputUrl,
    error = error,
    compatTrace = compatTrace,
    retryCount = retryCount,
    lowEndMode = lowEndMode,
    appliedCompatProfile = appliedCompatProfile,
    appliedWidth = appliedWidth,
    appliedHeight = appliedHeight,
    appliedFps = appliedFps,
    appliedBitrateKbps = appliedBitrateKbps,
)

fun VideoJob.toEntity(profile: ProviderProfile): VideoJobEntity = VideoJobEntity(
    id = id,
    remoteId = remoteId,
    prompt = prompt,
    model = model,
    status = status.toPersistence(),
    outputUrl = outputUrl,
    error = error,
    compatTrace = compatTrace,
    retryCount = retryCount,
    lowEndMode = lowEndMode,
    appliedCompatProfile = appliedCompatProfile,
    appliedWidth = appliedWidth,
    appliedHeight = appliedHeight,
    appliedFps = appliedFps,
    appliedBitrateKbps = appliedBitrateKbps,
    profileBaseUrl = profile.baseUrl,
    adapter = profile.adapter.name,
    authHeader = profile.authHeader,
    authPrefix = profile.authPrefix,
    workflowJson = profile.workflowJson,
)

/** 把领域对象的可变字段盖回既有实体，保留提交时快照的提供商配置。 */
fun VideoJobEntity.withDomain(domain: VideoJob): VideoJobEntity = VideoJobEntity(
    id = domain.id,
    remoteId = domain.remoteId,
    prompt = domain.prompt,
    model = domain.model,
    status = domain.status.toPersistence(),
    outputUrl = domain.outputUrl,
    error = domain.error,
    compatTrace = domain.compatTrace,
    retryCount = domain.retryCount,
    lowEndMode = domain.lowEndMode,
    appliedCompatProfile = domain.appliedCompatProfile,
    appliedWidth = domain.appliedWidth,
    appliedHeight = domain.appliedHeight,
    appliedFps = domain.appliedFps,
    appliedBitrateKbps = domain.appliedBitrateKbps,
    profileBaseUrl = profileBaseUrl,
    adapter = adapter,
    authHeader = authHeader,
    authPrefix = authPrefix,
    workflowJson = workflowJson,
)

/** 从实体恢复提交时的提供商配置（旧数据 adapter 字段无效时回退 GENERIC_REST）。
 *  API Key 不落盘，由调用方以当前内存配置注入。 */
fun VideoJobEntity.toProfile(apiKeyOverride: String = ""): ProviderProfile = ProviderProfile(
    baseUrl = profileBaseUrl,
    adapter = runCatching { AdapterType.valueOf(adapter) }.getOrDefault(AdapterType.GENERIC_REST),
    authHeader = authHeader,
    authPrefix = authPrefix,
    workflowJson = workflowJson,
    apiKey = apiKeyOverride,
)

/** 是否真实接入：提交时快照的 base URL 非空且非默认占位符。 */
fun VideoJobEntity.isRealProvider(): Boolean =
    profileBaseUrl.isNotBlank() &&
        !profileBaseUrl.startsWith("https://api.example.com") &&
        !profileBaseUrl.startsWith("http://api.example.com")
