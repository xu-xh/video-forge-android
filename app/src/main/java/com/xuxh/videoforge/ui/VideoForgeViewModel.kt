package com.xuxh.videoforge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuxh.videoforge.AdapterType
import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.ProviderProfile
import com.xuxh.videoforge.VideoForgeDatabase
import com.xuxh.videoforge.VideoJob
import com.xuxh.videoforge.VideoJobDao
import com.xuxh.videoforge.toDomain
import com.xuxh.videoforge.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class VideoForgeViewModel : ViewModel() {
    private lateinit var dao: VideoJobDao
    private val activePolls = mutableSetOf<String>()

    var statusText by mutableStateOf("就绪")
        private set

    private val _jobs = MutableStateFlow<List<VideoJob>>(emptyList())
    val jobs: StateFlow<List<VideoJob>> = _jobs

    fun init(context: android.content.Context) {
        if (::dao.isInitialized) return
        dao = VideoForgeDatabase.get(context)

        viewModelScope.launch(Dispatchers.IO) {
            refresh()
            resumePendingJobs()
        }
    }

    fun submit(prompt: String, model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = resolveCompatProfile(prompt)
                val baseTrace = appendTrace(
                    previous = null,
                    stage = "build",
                    profile = profile,
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
                    lowEndMode = profile.lowEndMode,
                    appliedCompatProfile = profile.name,
                    appliedWidth = profile.width,
                    appliedHeight = profile.height,
                    appliedFps = profile.fps,
                    appliedBitrateKbps = profile.bitrateKbps,
                ).toEntity(defaultProfile(model))

                dao.upsert(job)
                statusText = "已提交"
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
                for (step in 1..10) {
                    delay(if (step == 1) 600 else 1200)

                    // 直接读 DAO 获取最新状态，避免与 refresh() 的 _jobs 缓存产生竞态；
                    // 也修复了"作业尚未进入 _jobs 时轮询静默放弃"的问题。
                    val current = dao.getAll().find { it.id == id }?.toDomain() ?: run {
                        statusText = "轮询中断: 任务不存在 ${id.take(8)}"
                        return@launch
                    }

                    when (current.status) {
                        JobStatus.DONE -> return@launch

                        JobStatus.FAILED -> {
                            if (shouldFallback(current)) {
                                // 首次失败：降级到低清档重试一次，继续轮询
                                val retried = fallbackFor(current)
                                dao.upsert(retried.toEntity(defaultProfile(retried.model)))
                                refresh()
                                statusText = "降级重试: ${id.take(8)}"
                            } else {
                                return@launch
                            }
                        }

                        else -> {
                            val next = transitionForStep(current, step, resolveCompatProfile(current.prompt))
                            dao.upsert(next.toEntity(defaultProfile(next.model)))
                            refresh()

                            when (next.status) {
                                JobStatus.DONE -> {
                                    statusText = "已完成: ${id.take(8)}"
                                    return@launch
                                }
                                JobStatus.FAILED -> {
                                    // 可降级的失败不在此退出：下一轮由 FAILED 分支执行降级重试
                                    if (!shouldFallback(next)) {
                                        statusText = "任务失败: ${id.take(8)}"
                                        return@launch
                                    }
                                    statusText = "首次失败，将降级重试: ${id.take(8)}"
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            } finally {
                synchronized(activePolls) {
                    activePolls.remove(id)
                }
            }
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

    private fun defaultProfile(model: String): ProviderProfile = ProviderProfile(
        baseUrl = "https://api.example.com",
        adapter = AdapterType.OPENAI,
        authHeader = "Authorization",
        authPrefix = "Bearer",
        workflowJson = "{\"model\":\"$model\"}"
    )
}