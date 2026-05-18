package com.example.loramind

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.loramind.data.local.AppDatabase
import com.example.loramind.data.repository.BluetoothRepositoryImpl
import com.example.loramind.data.repository.MessageRepositoryImpl
import com.example.loramind.domain.usecase.SendMessageUseCase
import com.example.loramind.presentation.view.ChatScreen
import com.example.loramind.presentation.vm.ChatViewModel
import com.example.loramind.ui.theme.LoraMindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoraMindTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = com.example.loramind.ui.theme.DeepBlack
                ) { innerPadding ->

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val allGranted = permissions.entries.all { it.value }
                        if (!allGranted) {
                            Toast.makeText(
                                this,
                                getString(R.string.permission_required),
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }

                    }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                                )
                            )
                        } else {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            )
                        }
                    }
                    
                    val database = AppDatabase.getDatabase(applicationContext)
                    val messageDao = database.messageDao()
                    val localRepository = MessageRepositoryImpl(messageDao)

                    val bluetoothRepository = BluetoothRepositoryImpl(applicationContext)

                    val useCase = SendMessageUseCase(bluetoothRepository)

                    val factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ChatViewModel(useCase, localRepository, bluetoothRepository) as T
                        }
                    }

                    val chatViewModel: ChatViewModel = viewModel(factory = factory)

                    ChatScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}