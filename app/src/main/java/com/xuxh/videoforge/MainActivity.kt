package com.xuxh.videoforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.xuxh.videoforge.ui.VideoForgeViewModel
import com.xuxh.videoforge.ui.theme.VideoForgeTheme
import androidx.compose.runtime.LaunchedEffect
import com.xuxh.videoforge.ui.VideoForgeApp

class MainActivity : ComponentActivity() {
    private val viewModel: VideoForgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init(this)

        setContent {
            VideoForgeTheme {
                VideoForgeApp(viewModel)
            }
        }
    }
}
