package com.example.loramind.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.example.loramind.domain.repository.BluetoothRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothRepositoryImpl(
    private val context: Context
) : BluetoothRepository {

    private val TAG = "BT_REPO"

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val SPP_UUID: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override suspend fun connectToDevice(deviceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "connectToDevice START - deviceName=$deviceName")

            if (socket?.isConnected == true) {
                Log.d(TAG, "JA CONECTADO")
                return@withContext true
            }

            if (bluetoothAdapter == null) {
                Log.e(TAG, "BluetoothAdapter NULL")
                return@withContext false
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth DESLIGADO")
                return@withContext false
            }

            try {
                val pairedDevices = bluetoothAdapter.bondedDevices
                Log.d(TAG, "Paired devices: ${pairedDevices.size}")

                pairedDevices.forEach {
                    Log.d(TAG, "Device: ${it.name} - ${it.address}")
                }

                val device = pairedDevices.find { it.name?.startsWith(deviceName) == true }

                if (device == null) {
                    Log.e(TAG, "DEVICE NAO ENCONTRADO")
                    return@withContext false
                }

                Log.d(TAG, "DEVICE ENCONTRADO: ${device.name}")

                bluetoothAdapter.cancelDiscovery()
                Log.d(TAG, "Discovery cancelado")

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG, "Socket criado")

                Log.d(TAG, "Conectando socket...")
                socket?.connect()
                Log.d(TAG, "Socket conectado")

                inputStream = socket?.inputStream
                outputStream = socket?.outputStream

                Log.d(TAG, "Streams OK -> in=$inputStream out=$outputStream")

                true
            } catch (e: IOException) {
                Log.e(TAG, "ERRO AO CONECTAR", e)
                disconnect()
                false
            }
        }
    }

    override suspend fun sendMessage(message: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "sendMessage START - $message")

            try {
                if (socket?.isConnected != true) {
                    Log.e(TAG, "NAO CONECTADO - tentando conectar...")

                    val connected = connectToDevice("LoraMind")
                    Log.d(TAG, "Resultado auto-connect: $connected")

                    if (!connected) {
                        Log.e(TAG, "Falha ao conectar automaticamente")
                        return@withContext Result.failure(Exception("Nao conectado"))
                    }
                }

                if (outputStream == null) {
                    Log.e(TAG, "outputStream NULL")
                    return@withContext Result.failure(Exception("Stream null"))
                }

                val msg = "$message\n"
                Log.d(TAG, "Enviando: $msg")

                outputStream?.write(msg.toByteArray())
                outputStream?.flush()

                Log.d(TAG, "Enviado com sucesso")

                Result.success(Unit)
            } catch (e: IOException) {
                Log.e(TAG, "ERRO AO ENVIAR", e)
                Result.failure(e)
            }
        }
    }

    override fun observeIncomingMessages(): Flow<String> = flow {
        val buffer = ByteArray(1024)

        Log.d(TAG, "observeIncomingMessages START")

        while (true) {
            try {
                if (socket?.isConnected == true && inputStream != null) {

                    if (inputStream!!.available() > 0) {
                        val bytes = inputStream!!.read(buffer)
                        Log.d(TAG, "Bytes: $bytes")

                        if (bytes > 0) {
                            val msg = String(buffer, 0, bytes).trim()
                            Log.d(TAG, "Recebido: $msg")

                            if (msg.isNotEmpty()) {
                                emit(msg)
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Nao conectado na leitura")
                }

                kotlinx.coroutines.delay(50)

            } catch (e: IOException) {
                Log.e(TAG, "ERRO LEITURA", e)
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun disconnect() {
        Log.d(TAG, "disconnect")

        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "ERRO DISCONNECT", e)
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}