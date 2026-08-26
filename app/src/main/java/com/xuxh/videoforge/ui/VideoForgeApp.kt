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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xuxh.videoforge.JobStatus
import com.xuxh.videoforge.VideoJob
import java.util.Locale

@Composable
fun VideoForgeApp(viewModel: VideoForgeViewModel) {
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("qwen2.5-7b") }
    val jobs by viewModel.jobs.collectAsState()

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Video Forge", style = MaterialTheme.typography.titleLarge)
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
                JobItem(job)
            }
        }
    }
}

@Composable
private fun JobItem(job: VideoJob) {
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = job.prompt, style = MaterialTheme.typography.titleMedium)
            Text("模型: ${job.model}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "${label} | ${job.id.uppercase(Locale.ROOT)}")
            Text(text = subtitle)
            Text(text = compatLine, style = MaterialTheme.typography.bodySmall)
            if (traceLine.isNotBlank()) {
                Text(text = "Trace: $traceLine", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
