package com.example.loramind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.loramind.presentation.vm.ChatViewModel
import com.example.loramind.ui.theme.LoraMindTheme
import com.example.loramind.presentation.view.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoraMindTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val chatViewModel: ChatViewModel = viewModel()

                    ChatScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}