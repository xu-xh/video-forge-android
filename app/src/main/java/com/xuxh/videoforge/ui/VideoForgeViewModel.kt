package com.xuxh.videoforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.VideoForgeDatabase
import com.xuxh.videoforge.VideoJob
import com.xuxh.videoforge.VideoJobDao
import com.xuxh.videoforge.data.ProviderSettings
import com.xuxh.videoforge.data.ProviderSettingsStore
import com.xuxh.videoforge.isRealProvider
import com.xuxh.videoforge.net.RemoteStatus
import com.xuxh.videoforge.net.RemoteVideoSourceFactory
import com.xuxh.videoforge.toDomain
import com.xuxh.videoforge.toEntity
import com.xuxh.videoforge.toProfile
import com.xuxh.videoforge.withDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class VideoForgeViewModel : ViewModel() {
    private lateinit var dao: VideoJobDao
    private lateinit var settingsStore: ProviderSettingsStore
    private lateinit var appContext: Context
    private val activePolls = mutableSetOf<String>()

    /** 提供商配置（Compose 状态，UI 直接绑定）。 */
    var settings by mutableStateOf(ProviderSettings())
        private set

    var statusText by mutableStateOf("就绪")
        private set

    private val _jobs = MutableStateFlow<List<VideoJob>>(emptyList())
    val jobs: StateFlow<List<VideoJob>> = _jobs

    fun init(context: Context) {
        if (::dao.isInitialized) return
        appContext = context.applicationContext
        dao = VideoForgeDatabase.get(context)
        settingsStore = ProviderSettingsStore(context)
        settings = settingsStore.load()

        viewModelScope.launch(Dispatchers.IO) {
            refresh()
            resumePendingJobs()
        }
    }

    fun saveSettings(newSettings: ProviderSettings) {
        settings = newSettings
        settingsStore.save(newSettings)
        statusText = if (newSettings.isConfigured) "配置已保存：真实接入 ${newSettings.adapter.name}" else "配置已保存：未配置服务，将使用模拟模式"
    }

    fun submit(prompt: String, model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = settings.toProfile()
                val compat = resolveCompatProfile(prompt)
                val baseTrace = appendTrace(
                    previous = null,
                    stage = "build",
                    profile = compat,
                    reason = "initial profile selection",
                    attempt = 0
                )
                val job = VideoJob(
                    id = UUID.randomUUID().toString(),
                    prompt = prompt,
                    model = model,
                    status = JobStatus.SUBMITTED,
                    compatTrace = baseTrace,
                    retryCount = 0,
                    lowEndMode = compat.lowEndMode,
                    appliedCompatProfile = compat.name,
                    appliedWidth = compat.width,
                    appliedHeight = compat.height,
                    appliedFps = compat.fps,
                    appliedBitrateKbps = compat.bitrateKbps,
                ).toEntity(profile)

                dao.upsert(job)
                statusText = if (settings.isConfigured) "已提交到 ${profile.baseUrl}" else "已提交（模拟模式）"
                refresh()
                poll(job.id)
            } catch (e: Exception) {
                statusText = "提交失败: ${e.message}"
            }
        }
    }

    private fun resumePendingJobs() {
        // 兼容旧版本遗留：曾被置为 FAILED 但尚未重试过的作业也可恢复并降级重试
        val pending = _jobs.value.filter {
            it.status == JobStatus.SUBMITTED ||
                it.status == JobStatus.QUEUED ||
                it.status == JobStatus.PROCESSING ||
                (it.status == JobStatus.FAILED && shouldFallback(it))
        }

        if (pending.isNotEmpty()) {
            statusText = "检测到 ${pending.size} 个未完成任务，恢复轮询中"
        }

        pending.forEach { job ->
            poll(job.id)
        }
    }

    private fun poll(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(activePolls) {
                if (activePolls.contains(id)) return@launch
                activePolls.add(id)
            }
            try {
                val entity = dao.getAll().find { it.id == id }
                if (entity == null) {
                    statusText = "轮询中断: 任务不存在 ${id.take(8)}"
                    return@launch
                }
                // 真实接入的作业走真实轮询；未配置服务时走本地模拟（显式标注模拟模式）
                if (entity.isRealProvider()) {
                    pollRemote(id)
                } else {
                    statusText = if (settings.isConfigured) {
                        "任务未配置真实服务，跳过模拟（${id.take(8)}）"
                    } else {
                        "模拟模式: ${id.take(8)}"
                    }
                    simulate(id)
                }
            } finally {
                synchronized(activePolls) {
                    activePolls.remove(id)
                }
            }
        }
    }

    /** 真实提供商轮询：提交 -> 轮询状态 -> 终态（DONE/FAILED）。 */
    private suspend fun pollRemote(id: String) {
        var attempts = 0
        val maxAttempts = 90 // 最长约 3 分钟

        while (attempts < maxAttempts) {
            attempts++

            val entity = dao.getAll().find { it.id == id } ?: return
            val domain = entity.toDomain()
            if (domain.status == JobStatus.DONE || domain.status == JobStatus.FAILED) return

            val profile = entity.toProfile(apiKeyOverride = settings.apiKey)
            val source = RemoteVideoSourceFactory.create(profile)

            if (domain.remoteId.isNullOrEmpty()) {
                statusText = "提交中... ${id.take(8)}"
                val result = source.submit(domain.prompt, domain.model)
                if (!result.submitted) {
                    val failed = domain.copy(
                        status = JobStatus.FAILED,
                        error = result.error ?: "提交失败"
                    )
                    dao.upsert(entity.withDomain(failed))
                    statusText = "提交失败: ${id.take(8)}"
                    refresh()
                    return
                }
                dao.upsert(entity.withDomain(domain.copy(remoteId = result.remoteId)))
                refresh()
                delay(2000)
                continue
            }

            delay(1500)
            val current = dao.getAll().find { it.id == id } ?: return
            val cur = current.toDomain()
            if (cur.status == JobStatus.DONE || cur.status == JobStatus.FAILED) return

            val pollResult = source.poll(cur.remoteId!!)
            val compat = resolveCompatProfile(cur.prompt)
            val next = when (pollResult.status) {
                RemoteStatus.QUEUED -> cur.copy(
                    status = JobStatus.QUEUED,
                    compatTrace = appendTrace(cur.compatTrace, "poll", compat, "provider queued", cur.retryCount)
                )
                RemoteStatus.PROCESSING -> cur.copy(
                    status = JobStatus.PROCESSING,
                    compatTrace = appendTrace(cur.compatTrace, "poll", compat, "provider processing", cur.retryCount)
                )
                RemoteStatus.DONE -> cur.copy(
                    status = JobStatus.DONE,
                    outputUrl = pollResult.outputUrl,
                    error = null,
                    compatTrace = appendTrace(cur.compatTrace, "finish", compat, "provider done", cur.retryCount)
                )
                RemoteStatus.FAILED -> cur.copy(
                    status = JobStatus.FAILED,
                    error = pollResult.error ?: "提供商报告失败",
                    compatTrace = appendTrace(cur.compatTrace, "provider-failed", compat, "terminal provider failure", cur.retryCount)
                )
            }
            dao.upsert(current.withDomain(next))
            refresh()

            when (next.status) {
                JobStatus.DONE -> {
                    statusText = "已完成: ${id.take(8)}"
                    return
                }
                JobStatus.FAILED -> {
                    statusText = "任务失败: ${id.take(8)}"
                    return
                }
                else -> Unit
            }
        }

        // 超时：标记失败
        dao.getAll().find { it.id == id }?.let { entity ->
            val failed = entity.toDomain().copy(
                status = JobStatus.FAILED,
                error = "轮询超时（约 ${maxAttempts * 2} 秒）"
            )
            dao.upsert(entity.withDomain(failed))
            refresh()
            statusText = "任务超时: ${id.take(8)}"
        }
    }

    /** 本地模拟轮询（未配置真实服务时的演示模式）：10 步心跳推进状态机。 */
    private suspend fun simulate(id: String) {
        for (step in 1..10) {
            delay(if (step == 1) 600 else 1200)

            val entity = dao.getAll().find { it.id == id } ?: return
            val current = entity.toDomain()

            when (current.status) {
                JobStatus.DONE -> return
                JobStatus.FAILED -> {
                    if (shouldFallback(current)) {
                        val retried = fallbackFor(current)
                        dao.upsert(entity.withDomain(retried))
                        refresh()
                        statusText = "降级重试: ${id.take(8)}"
                    } else {
                        return
                    }
                }
                else -> {
                    val next = transitionForStep(current, step, resolveCompatProfile(current.prompt))
                    dao.upsert(entity.withDomain(next))
                    refresh()

                    when (next.status) {
                        JobStatus.DONE -> {
                            statusText = "已完成: ${id.take(8)}"
                            return
                        }
                        JobStatus.FAILED -> {
                            if (!shouldFallback(next)) {
                                statusText = "任务失败: ${id.take(8)}"
                                return
                            }
                            statusText = "首次失败，将降级重试: ${id.take(8)}"
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    /** 用系统浏览器打开/下载生成结果（显式用户操作）。 */
    fun openResult(job: VideoJob) {
        val url = job.outputUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            statusText = "无法打开结果: ${e.message}"
        }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clear()
            refresh()
            statusText = "已清空"
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getAll().map { it.toDomain() }
            _jobs.value = list
        }
    }
}