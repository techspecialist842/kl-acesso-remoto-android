package com.meuacesso.remoto

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class ConexaoVPS {
    private var socket: Socket? = null
    private var saida: PrintWriter? = null
    private var entrada: BufferedReader? = null
    private val TAG = "ConexaoKL"
    private var pingJob: Job? = null
    private var reconexaoJob: Job? = null
    private val TEMPO_ENTRE_PINGS = 12_000L       // 12s entre sinais
    private val TEMPO_ESPERA_RECONEXAO = 5_000L   // Tenta reconectar rápido

    // Dados do aparelho no formato que o painel espera
    private val fabricante = Build.MANUFACTURER
    private val modelo = Build.MODEL
    private val versaoAndroid = Build.VERSION.RELEASE
    private val tipoRede = "Wi-Fi/Dados"

    fun conectar(): Boolean {
        pararTudo()
        return try {
            Log.i(TAG, "🔄 Conectando em ${Constantes.SERVIDOR_HOST}:${Constantes.SERVIDOR_PORTA}")

            socket = Socket().apply {
                soTimeout = 30000
                sendBufferSize = 8192
                receiveBufferSize = 8192
                keepAlive = true
                tcpNoDelay = true
            }

            socket!!.connect(InetSocketAddress(Constantes.SERVIDOR_HOST, Constantes.SERVIDOR_PORTA), 8000)

            saida = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8), true)
            entrada = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))

            // Primeira identificação
            enviar("CELULAR_KL")
            // Envia dados completos logo em seguida
            enviar("INFO|$fabricante|$modelo|$versaoAndroid|$tipoRede")

            iniciarPing()
            iniciarReconexao()
            Log.i(TAG, "✅ Conectado e identificado corretamente")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Falha: ${e.message}")
            desconectar()
            false
        }
    }

    private fun iniciarPing() {
        pingJob?.cancel()
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (estaConectado()) {
                delay(TEMPO_ENTRE_PINGS)
                enviar("PING")
                Log.d(TAG, "📡 Sinal de vida enviado")
            }
        }
    }

    private fun iniciarReconexao() {
        reconexaoJob?.cancel()
        reconexaoJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                if (!estaConectado()) {
                    Log.w(TAG, "🔌 Sem conexão — tentando novamente...")
                    conectar()
                }
                delay(TEMPO_ESPERA_RECONEXAO)
            }
        }
    }

    fun enviar(mensagem: String) {
        if (!estaConectado()) return
        try {
            saida?.println(mensagem.trim())
            saida?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao enviar: ${e.message}")
            desconectar()
        }
    }

    fun receber(): String? {
        if (!estaConectado()) return null
        return try {
            val linha = entrada?.readLine() ?: return null
            if (linha.isBlank()) { desconectar(); return null }
            linha.trim()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao receber: ${e.message}")
            desconectar()
            null
        }
    }

    fun desconectar() {
        pararTudo()
        try {
            saida?.flush(); saida?.close()
            entrada?.close()
            socket?.close()
        } catch (_: Exception) {}
        saida = null; entrada = null; socket = null
    }

    private fun pararTudo() {
        pingJob?.cancel()
        reconexaoJob?.cancel()
    }

    fun estaConectado(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
    }
}