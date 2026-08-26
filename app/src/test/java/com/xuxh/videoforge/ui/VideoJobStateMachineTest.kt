package com.xuxh.videoforge.ui

import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.VideoJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VideoJobStateMachine 的 JVM 单元测试。
 * drive() 复刻 VideoForgeViewModel.poll() 的推进方式（去掉延迟与持久化）：
 * 逐 step 应用 transitionForStep；遇到 FAILED 且可降级时先 fallbackFor 再继续。
 */
class VideoJobStateMachineTest {

    private fun job(
        prompt: String,
        retryCount: Int = 0,
        lowEndMode: Boolean = false,
        status: JobStatus = JobStatus.SUBMITTED
    ): VideoJob = VideoJob(
        id = "job-1",
        prompt = prompt,
        model = "test-model",
        status = status,
        retryCount = retryCount,
        lowEndMode = lowEndMode,
    )

    private fun drive(initial: VideoJob): VideoJob {
        var current = initial
        for (step in 1..10) {
            when (current.status) {
                JobStatus.DONE, JobStatus.FAILED -> {
                    if (current.status == JobStatus.FAILED && shouldFallback(current)) {
                        current = fallbackFor(current)
                        continue
                    }
                    return current
                }
                else -> current = transitionForStep(current, step, resolveCompatProfile(current.prompt))
            }
        }
        return current
    }

    @Test
    fun `normal prompt reaches DONE without retry`() {
        val result = drive(job("verify resume test"))
        assertEquals(JobStatus.DONE, result.status)
        assertEquals(0, result.retryCount)
        assertFalse(result.lowEndMode)
        assertNull(result.error)
        assertNotNull(result.outputUrl)
        assertTrue(result.outputUrl!!.startsWith("https://example.com/out-"))
    }

    @Test
    fun `error prompt falls back once and then reaches DONE`() {
        val result = drive(job("this is an error test"))
        assertEquals(JobStatus.DONE, result.status)
        assertEquals(1, result.retryCount)
        assertTrue(result.lowEndMode)
        assertEquals("low_end", result.appliedCompatProfile)
        assertEquals(640, result.appliedWidth)
        assertEquals(360, result.appliedHeight)
        assertEquals(24, result.appliedFps)
        assertEquals(1500, result.appliedBitrateKbps)
        assertNull(result.error)
        assertNotNull(result.outputUrl)
    }

    @Test
    fun `already-retried failed job is terminal and not retried again`() {
        val failed = job(prompt = "error again", retryCount = 1, lowEndMode = true, status = JobStatus.FAILED)
        assertFalse(shouldFallback(failed))
        val result = drive(failed)
        assertEquals(failed, result) // 状态未被改动，保持终态
    }

    @Test
    fun `low or legacy prompt selects low-end profile from the start`() {
        assertEquals(LOW_END_PROFILE, resolveCompatProfile("legacy device"))
        assertEquals(LOW_END_PROFILE, resolveCompatProfile("use LOW settings"))
        assertEquals(LOW_END_PROFILE, resolveCompatProfile("a LOW-RES clip"))
        assertEquals(DEFAULT_PROFILE, resolveCompatProfile("a kite over the sea"))
    }

    @Test
    fun `transition steps map to expected statuses`() {
        var current = job("a kite over the sea")
        current = transitionForStep(current, 1, DEFAULT_PROFILE)
        assertEquals(JobStatus.QUEUED, current.status)

        current = transitionForStep(current, 2, DEFAULT_PROFILE)
        assertEquals(JobStatus.PROCESSING, current.status)
        assertTrue(current.remoteId!!.startsWith("r-"))

        current = transitionForStep(current, 3, DEFAULT_PROFILE)
        assertEquals(JobStatus.PROCESSING, current.status)

        current = transitionForStep(current, 4, DEFAULT_PROFILE)
        assertEquals(JobStatus.PROCESSING, current.status)
        assertNull(current.error)

        current = transitionForStep(current, 6, DEFAULT_PROFILE)
        assertEquals(JobStatus.DONE, current.status)
        assertNotNull(current.outputUrl)
    }

    @Test
    fun `failure transition records error only on first attempt`() {
        val first = job("error case")
        val failed = transitionForStep(first, 4, DEFAULT_PROFILE)
        assertEquals(JobStatus.FAILED, failed.status)
        assertEquals("模拟生成失败: 码率不足", failed.error)

        // 已重试过的作业在第 4 步不再失败（模拟重试成功后继续）
        val retried = job("error case", retryCount = 1)
        val next = transitionForStep(retried, 4, DEFAULT_PROFILE)
        assertEquals(JobStatus.PROCESSING, next.status)
        assertNull(next.error)
    }

    @Test
    fun `compat trace records fallback and finish stages`() {
        val result = drive(job("error"))
        val trace = requireNotNull(result.compatTrace)
        assertTrue(trace.contains("\"stage\":\"fallback\""))
        assertTrue(trace.contains("\"profile\":\"low_end\""))
        assertTrue(trace.contains("\"stage\":\"finish\""))
    }
}