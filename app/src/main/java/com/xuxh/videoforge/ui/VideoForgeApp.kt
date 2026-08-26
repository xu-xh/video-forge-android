package com.xuxh.videoforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xuxh.videoforge.AdapterType
import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.VideoJob
import com.xuxh.videoforge.data.ProviderSettings
import java.util.Locale

@Composable
fun VideoForgeApp(viewModel: VideoForgeViewModel) {
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("qwen2.5-7b") }
    val jobs by viewModel.jobs.collectAsState()

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Video Forge", style = MaterialTheme.typography.titleLarge)

        ProviderSettingsPanel(viewModel)

        Text("状态: ${viewModel.statusText}")

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (prompt.isNotBlank()) viewModel.submit(prompt, model) }) {
                Text("提交任务")
            }
            Button(onClick = { viewModel.refresh() }) {
                Text("刷新")
            }
            Button(onClick = { viewModel.clear() }) {
                Text("清空")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs) { job ->
                JobItem(job, onOpen = { viewModel.openResult(job) })
            }
        }
    }
}

@Composable
private fun ProviderSettingsPanel(viewModel: VideoForgeViewModel) {
    val settings = viewModel.settings
    var adapter by remember { mutableStateOf(settings.adapter) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var authHeader by remember { mutableStateOf(settings.authHeader) }
    var authPrefix by remember { mutableStateOf(settings.authPrefix) }
    var defaultModel by remember { mutableStateOf(settings.defaultModel) }
    var workflowJson by remember { mutableStateOf(settings.workflowJson) }

    val modeText = if (settings.isConfigured) {
        "真实接入: ${settings.adapter.name} @ ${settings.baseUrl}"
    } else {
        "⚠ 模拟模式（未配置真实服务）"
    }

    Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = modeText, style = MaterialTheme.typography.labelLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdapterButton(
                    label = "Generic REST",
                    selected = adapter == AdapterType.GENERIC_REST,
                    onClick = { adapter = AdapterType.GENERIC_REST }
                )
                AdapterButton(
                    label = "ComfyUI",
                    selected = adapter == AdapterType.COMFY_UI,
                    onClick = { adapter = AdapterType.COMFY_UI }
                )
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (如 http://192.168.1.10:8188)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（Keystore 加密存储，可留空）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = authHeader,
                    onValueChange = { authHeader = it },
                    label = { Text("Auth Header") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = authPrefix,
                    onValueChange = { authPrefix = it },
                    label = { Text("Auth Prefix") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = defaultModel,
                onValueChange = { defaultModel = it },
                label = { Text("默认模型") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (adapter == AdapterType.COMFY_UI) {
                OutlinedTextField(
                    value = workflowJson,
                    onValueChange = { workflowJson = it },
                    label = { Text("ComfyUI API 格式 workflow JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8
                )
            }

            Button(onClick = {
                viewModel.saveSettings(
                    ProviderSettings(
                        adapter = adapter,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        authHeader = authHeader,
                        authPrefix = authPrefix,
                        defaultModel = defaultModel,
                        workflowJson = workflowJson
                    )
                )
                baseUrl = baseUrl // 保持输入不丢失
            }) {
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun AdapterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun JobItem(job: VideoJob, onOpen: () -> Unit) {
    val label = when (job.status) {
        JobStatus.SUBMITTED -> "已提交"
        JobStatus.QUEUED -> "排队中"
        JobStatus.PROCESSING -> "生成中"
        JobStatus.DONE -> "完成"
        JobStatus.FAILED -> "失败"
    }
    val subtitle = job.outputUrl?.let { "结果: $it" } ?: (job.error?.let { "错误: $it" } ?: "状态: $label")
    val compatLine = buildString {
        append("Compat: ")
        append(job.appliedCompatProfile ?: "default")
        append(" retries=")
        append(job.retryCount)
        if (job.lowEndMode) append(" low_end=1")
        if (job.appliedWidth != null && job.appliedHeight != null) {
            append(" ${job.appliedWidth}x${job.appliedHeight}")
        }
        if (job.appliedFps != null) append(" @${job.appliedFps}fps")
        if (job.appliedBitrateKbps != null) append(" ${job.appliedBitrateKbps}kbps")
    }
    val traceLine = job.compatTrace?.let { it.take(180) } ?: ""

    Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = job.prompt, style = MaterialTheme.typography.titleMedium)
            Text("模型: ${job.model}")
            Text(text = "${label} | ${job.id.uppercase(Locale.ROOT)}")
            Text(text = subtitle)
            Text(text = compatLine, style = MaterialTheme.typography.bodySmall)
            if (traceLine.isNotBlank()) {
                Text(text = "Trace: $traceLine", style = MaterialTheme.typography.bodySmall)
            }
            if (job.outputUrl != null) {
                Button(onClick = onOpen) {
                    Text("打开结果")
                }
            }
        }
    }
}