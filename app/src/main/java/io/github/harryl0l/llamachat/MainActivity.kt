package io.github.harryl0l.llamachat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.harryl0l.llamachat.ui.chat.ChatScreen
import io.github.harryl0l.llamachat.ui.models.ModelsScreen
import io.github.harryl0l.llamachat.ui.theme.LlamaChatTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LlamaChatTheme {
                var showModels by rememberSaveable { mutableStateOf(false) }
                val spec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                AnimatedContent(
                    targetState = showModels,
                    transitionSpec = {
                        if (targetState) slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 }
                        else slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it }
                    },
                    label = "screens",
                ) { models ->
                    if (models) ModelsScreen(vm, onBack = { showModels = false })
                    else ChatScreen(vm, onOpenModels = { showModels = true })
                }
            }
        }
    }
}
