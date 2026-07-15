package com.meuacesso.remoto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageView
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.content.pm.ServiceInfo
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
        const val URL_LOGO_OVERLAY = "${URL_VPS}/logo_overlay"

        private const val INTERVALO_ATUALIZACAO = 1000L
        private const val CANAL_NOTIFICACAO = "servico_controle_remoto"
        private const val ID_NOTIFICACAO = 9999

        fun obterIdDispositivo(): String {
            val buildId = Build.ID?.trim().orEmpty()
            if (buildId.isNotEmpty() && buildId != "unknown") return buildId
            return "${Build.MANUFACTURER}_${Build.MODEL}_${Build.SERIAL}".replace(" ", "_")
        }

    }

    private var jobPrincipal: Job? = null
    private var overlayView: View? = null
    private var overlayFundoView: View? = null
    private var parametrosOverlay: WindowManager.LayoutParams? = null
    private lateinit var windowManager: WindowManager

    private var overlayAtivo = false
    private var mensagemOverlayAtual = ""
    private var textoInferiorOverlayAtual = ""
    private var logoOverlayAtual = ""
    private var ultimaEstruturaEspelho: String? = null
    private var ultimaConsultaOverlayMs = 0L
    private val handlerPrincipal = Handler(Looper.getMainLooper())
    private val intervaloConsultaOverlayMs = 4000L
    private var ignorarArrastosAteMs = 0L
    private var ultimaAreaPadraoDetectada: Rect? = null
    private var restaurarOverlayAposPadrao = false
    private var mensagemOverlaySalva = ""
    private var textoInferiorOverlaySalvo = ""
    private var logoOverlaySalvo = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        criarCanalNotificacao()
        try {
            iniciarServicoEmPrimeiroPlano()
        } catch (e: Exception) {
            Log.e("KL", "Erro ao iniciar FGS: ${e.message}")
        }
        iniciarLoopPrincipal()
        registrarDispositivoNoServidor()
        Log.i("KL", "Servico iniciado — ID=${obterIdDispositivo()} Android=${Build.VERSION.RELEASE}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w("KL", "Acessibilidade interrompida")
    }

    override fun onDestroy() {
        Log.w("KL", "Servico destruido")
        pararServico()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w("KL", "Task removida — reativando servico em primeiro plano")
        iniciarServicoEmPrimeiroPlano()
        if (jobPrincipal?.isActive != true) {
            iniciarLoopPrincipal()
        }
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
        if (janelasApp.isEmpty()) {
            obterJanelasKeyguard().firstOrNull()?.root?.let { return it }
            return rootInActiveWindow
        }

        janelasApp.firstOrNull { it.isFocused }?.root?.let { return it }
        janelasApp.firstOrNull { it.isActive }?.root?.let { return it }

        val raizApp = janelasApp
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { janela ->
                val area = Rect()
                janela.root?.getBoundsInScreen(area)
                area.width() * area.height()
            }?.root

        if (raizApp != null) return raizApp

        obterJanelasKeyguard().firstOrNull()?.root?.let { return it }
        return rootInActiveWindow
    }

    private fun extrairEstruturaCompleta(): String {
        val aoVivo = extrairEstruturaAoVivo()
        val qtdAoVivo = contarElementosJson(aoVivo)

        if (qtdAoVivo > 0) {
            ultimaEstruturaEspelho = aoVivo
            return aoVivo
        }

        val keyguardAoVivo = extrairEstruturaSomenteKeyguard()
        val qtdKeyguard = contarElementosJson(keyguardAoVivo)
        if (qtdKeyguard > 0) {
            ultimaEstruturaEspelho = keyguardAoVivo
            return keyguardAoVivo
        }

        if ((overlayAtivo || OverlayActivity.estaAtiva()) && ultimaEstruturaEspelho != null) {
            Log.d("KL", "Espelho em cache durante overlay (${Build.MANUFACTURER})")
            return ultimaEstruturaEspelho!!
        }

        if (ultimaEstruturaEspelho != null) {
            Log.d("KL", "Espelho em cache — leitura ao vivo vazia (${Build.MANUFACTURER})")
            return ultimaEstruturaEspelho!!
        }

        return aoVivo
    }

    private fun contarElementosJson(json: String): Int {
        return try {
            org.json.JSONObject(json).optJSONArray("elementos")?.length() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun extrairEstruturaSomenteKeyguard(): String {
        val listaElementos = mutableListOf<Map<String, Any>>()
        val idsIncluidos = mutableSetOf<String>()
        obterJanelasKeyguard().forEach { janela ->
            janela.root?.let { percorrerNo(it, listaElementos, idsIncluidos) }
        }
        val elementosLimpos = removerElementosRedundantes(listaElementos)
        val areaPadrao = detectarAreaPadrao(elementosLimpos)
        return montarJsonEstrutura(elementosLimpos, resources.displayMetrics, areaPadrao)
    }

    private fun extrairEstruturaAoVivo(): String {
        val listaElementos = mutableListOf<Map<String, Any>>()
        val idsIncluidos = mutableSetOf<String>()
        val displayMetrics = resources.displayMetrics

        val janelas = obterTodasJanelasEspelho()
        if (janelas.isNotEmpty()) {
            janelas.forEach { janela ->
                janela.root?.let { percorrerNo(it, listaElementos, idsIncluidos) }
            }
        }

        if (listaElementos.isEmpty()) {
            obterRaizAplicativo()?.let { percorrerNo(it, listaElementos, idsIncluidos) }
        }

        if (listaElementos.isEmpty() && (overlayAtivo || OverlayActivity.estaAtiva())) {
            Log.w(
                "KL",
                "Espelho vazio com overlay ativo (${Build.MANUFACTURER}). Janelas=${windows?.size ?: 0}"
            )
        }

        val elementosLimpos = removerElementosRedundantes(listaElementos)
        val areaPadrao = detectarAreaPadrao(elementosLimpos)
        return montarJsonEstrutura(elementosLimpos, displayMetrics, areaPadrao)
    }

    private fun capturarCacheEspelhoAntesOverlay() {
        val aoVivo = extrairEstruturaAoVivo()
        try {
            val elementos = org.json.JSONObject(aoVivo).optJSONArray("elementos")
            if (elementos != null && elementos.length() > 0) {
                ultimaEstruturaEspelho = aoVivo
                Log.i("KL", "Cache espelho salvo: ${elementos.length()} elementos")
            }
        } catch (_: Exception) {
        }
    }

    private fun montarJsonEstrutura(
        listaElementos: List<Map<String, Any>>,
        displayMetrics: android.util.DisplayMetrics,
        areaPadrao: Rect? = null
    ): String {
        val json = org.json.JSONObject().apply {
            put("id", obterIdDispositivo())
            put("modelo", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("fabricante", Build.MANUFACTURER)
            put("android", Build.VERSION.RELEASE)
            put("largura", displayMetrics.widthPixels)
            put("altura", displayMetrics.heightPixels)
            put("densidade", displayMetrics.densityDpi)
            if (areaPadrao != null && areaPadrao.width() >= 120 && areaPadrao.height() >= 120) {
                put("area_padrao", org.json.JSONObject().apply {
                    put("x", areaPadrao.left)
                    put("y", areaPadrao.top)
                    put("width", areaPadrao.width())
                    put("height", areaPadrao.height())
                })
            }
        }

        val elementosJson = org.json.JSONArray()
        listaElementos.forEach { elemento ->
            val obj = org.json.JSONObject()
            elemento.forEach { (chave, valor) -> obj.put(chave, valor) }
            elementosJson.put(obj)
        }
        json.put("elementos", elementosJson)
        json.put("status", "online")
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

    private fun ehLauncher(pacote: String): Boolean {
        val p = pacote.lowercase()
        return p.contains("launcher") || p.contains("home") || p == "com.sec.android.app.launcher"
    }

    private fun ehPacoteKeyguard(pacote: String): Boolean {
        val p = pacote.lowercase()
        return p == "com.android.systemui" ||
                p.contains("keyguard") ||
                p.contains("systemui") ||
                p.contains("miui") && (p.contains("keyguard") || p.contains("security")) ||
                p.contains("motorola") && (p.contains("systemui") || p.contains("keyguard"))
    }

    private fun ehPacoteIgnorado(pacote: String): Boolean {
        if (pacote.isBlank() || pacote == packageName) return true
        return ehPacoteKeyguard(pacote)
    }

    private fun obterJanelasKeyguard(): List<AccessibilityWindowInfo> {
        val janelas = windows ?: return emptyList()
        return janelas.filter { janela ->
            val raiz = janela.root ?: return@filter false
            val pacote = raiz.packageName?.toString().orEmpty()
            pacote != packageName &&
                    janela.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    (ehPacoteKeyguard(pacote) ||
                            janela.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                            janela.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        }
    }

    private fun obterTodasJanelasEspelho(): List<AccessibilityWindowInfo> {
        val janelas = windows ?: return emptyList()
        val ids = linkedSetOf<Int>()
        val resultado = mutableListOf<AccessibilityWindowInfo>()

        fun adicionar(janela: AccessibilityWindowInfo) {
            if (ids.add(janela.id)) resultado.add(janela)
        }

        obterJanelasKeyguard().forEach { adicionar(it) }

        val apps = janelas.filter { janela ->
            val raiz = janela.root ?: return@filter false
            val pacote = raiz.packageName?.toString().orEmpty()
            pacote != packageName &&
                    janela.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    !ehPacoteKeyguard(pacote) &&
                    !ehLauncher(pacote)
        }.sortedByDescending { janela ->
            val area = Rect()
            janela.root?.getBoundsInScreen(area)
            area.width() * area.height()
        }

        apps.take(if (overlayAtivo || OverlayActivity.estaAtiva()) 3 else 2).forEach { adicionar(it) }

        if (resultado.isEmpty()) {
            janelas.filter { janela ->
                val raiz = janela.root ?: return@filter false
                val pacote = raiz.packageName?.toString().orEmpty()
                pacote != packageName && janela.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }.forEach { adicionar(it) }
        }

        return resultado
    }

    private fun obterJanelasAplicativo(): List<AccessibilityWindowInfo> {
        val janelas = windows ?: return emptyList()

        fun janelaValida(janela: AccessibilityWindowInfo, ignorarLauncher: Boolean): Boolean {
            val raiz = janela.root ?: return false
            val pacote = raiz.packageName?.toString().orEmpty()
            if (pacote == packageName) return false
            if (janela.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
            if (ignorarLauncher && pacote.lowercase().contains("launcher")) return false
            return !ehPacoteIgnorado(pacote)
        }

        fun selecionarMelhorJanela(candidatas: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> {
            if (candidatas.isEmpty()) return emptyList()
            candidatas.firstOrNull { it.isFocused }?.let { return listOf(it) }
            candidatas.firstOrNull { it.isActive }?.let { return listOf(it) }
            return candidatas
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { janela ->
                    val area = Rect()
                    janela.root?.getBoundsInScreen(area)
                    area.width() * area.height()
                }
                .take(1)
        }

        var candidatas = janelas.filter { janelaValida(it, ignorarLauncher = true) }
        if (candidatas.isEmpty()) {
            candidatas = janelas.filter { janelaValida(it, ignorarLauncher = false) }
        }
        if (candidatas.isEmpty()) {
            candidatas = janelas.filter {
                val raiz = it.root ?: return@filter false
                val pacote = raiz.packageName?.toString().orEmpty()
                pacote != packageName && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
        }

        if (candidatas.isEmpty()) {
            candidatas = obterJanelasKeyguard()
        }

        if (candidatas.isEmpty()) {
            candidatas = janelas.filter {
                val raiz = it.root ?: return@filter false
                val pacote = raiz.packageName?.toString().orEmpty()
                ehLauncher(pacote)
            }
        }

        if (overlayAtivo || OverlayActivity.estaAtiva()) {
            val todasApp = candidatas
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { janela ->
                    val area = Rect()
                    janela.root?.getBoundsInScreen(area)
                    area.width() * area.height()
                }
            if (todasApp.isNotEmpty()) return todasApp

            val keyguard = obterJanelasKeyguard()
            if (keyguard.isNotEmpty()) return selecionarMelhorJanela(keyguard)
        }

        return selecionarMelhorJanela(candidatas)
    }

    private fun removerElementosRedundantes(elementos: List<Map<String, Any>>): List<Map<String, Any>> {
        return elementos.filterIndexed { index, elemento ->
            val x = elemento["x"] as? Int ?: 0
            val y = elemento["y"] as? Int ?: 0
            val largura = elemento["largura"] as? Int ?: 0
            val altura = elemento["altura"] as? Int ?: 0
            val area = largura * altura
            val rotulo = (elemento["rotulo"] as? String).orEmpty()

            elementos.withIndex().none { (outroIndex, outro) ->
                if (outroIndex == index) return@none false

                val ox = outro["x"] as? Int ?: 0
                val oy = outro["y"] as? Int ?: 0
                val ow = outro["largura"] as? Int ?: 0
                val oh = outro["altura"] as? Int ?: 0
                val outroArea = ow * oh
                val outroRotulo = (outro["rotulo"] as? String).orEmpty()

                val contemOutro = ox >= x - 2 && oy >= y - 2 &&
                        ox + ow <= x + largura + 2 && oy + oh <= y + altura + 2
                val contidoEmOutro = x >= ox - 2 && y >= oy - 2 &&
                        x + largura <= ox + ow + 2 && y + altura <= oy + oh + 2

                when {
                    contemOutro && outroArea < area && outroRotulo.isNotEmpty() &&
                            (rotulo.contains(outroRotulo) || outroRotulo.length >= 3 && rotulo.startsWith(outroRotulo)) -> true
                    contidoEmOutro && outroArea > area && rotulo.isNotEmpty() &&
                            outroRotulo.contains(rotulo) -> true
                    rotulo == outroRotulo && kotlin.math.abs(ox - x) < 6 &&
                            kotlin.math.abs(oy - y) < 6 && outroArea < area -> true
                    else -> false
                }
            }
        }
    }

    private fun ehContainerSomente(className: String, isClickable: Boolean, isEditable: Boolean): Boolean {
        if (isEditable) return false
        val c = className.lowercase()
        val tipoVisual = c.contains("button") || c.contains("edittext") ||
                c.contains("textview") || c.contains("imagebutton") || c.contains("imageview")
        if (tipoVisual) return false
        return c.contains("layout") || c.contains("viewgroup") || c.contains("framelayout") ||
                c.contains("linearlayout") || c.contains("relativelayout") ||
                c.contains("constraintlayout") || c.contains("composeview") ||
                (c.endsWith("view") && !c.contains("image") && !c.contains("web") && !c.contains("recycler"))
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
        if (rotulo.isEmpty() && (isClickable || isEditable)) {
            rotulo = coletarTextoDescendentes(no, profundidadeMax = 2)
        }

        val visivel = no.isVisibleToUser
        val temConteudo = rotulo.isNotEmpty() || descricao.isNotEmpty() || texto.isNotEmpty()
        val containerSomente = ehContainerSomente(className, isClickable, isEditable)

        if (!containerSomente && (visivel || temConteudo) &&
            deveIncluirElemento(no, area, rotulo, className, isClickable, isEditable)
        ) {
            val tipo = identificarTipo(className, isClickable, isEditable, isCheckable)
            val textoExibicao = rotulo.ifEmpty { descricao.ifEmpty { dica.ifEmpty { estado.ifEmpty { recursoId } } } }
                .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
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

    private fun registrarDispositivoNoServidor() {
        CoroutineScope(Dispatchers.IO).launch {
            var conexao: HttpURLConnection? = null
            try {
                val payload = org.json.JSONObject().apply {
                    put("id", obterIdDispositivo())
                    put("modelo", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("fabricante", Build.MANUFACTURER)
                    put("android", Build.VERSION.RELEASE)
                    put("largura", resources.displayMetrics.widthPixels)
                    put("altura", resources.displayMetrics.heightPixels)
                    put("status", "online")
                }
                conexao = URL("${URL_VPS}/registrar_dispositivo").openConnection() as HttpURLConnection
                conexao.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OutputStreamWriter(conexao.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                Log.i("KL", "Registro dispositivo: HTTP ${conexao.responseCode}")
            } catch (e: Exception) {
                Log.e("KL", "Erro ao registrar dispositivo: ${e.message}")
            } finally {
                conexao?.disconnect()
            }
        }
    }

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
                if (resposta == HttpURLConnection.HTTP_OK) {
                    Log.d("KL", "Estrutura enviada — ${obterIdDispositivo()}")
                } else {
                    Log.w("KL", "Estrutura: servidor retornou $resposta")
                }
            } catch (e: Exception) {
                Log.e("KL", "Erro ao enviar estrutura: ${e.message}")
            } finally {
                conexao?.disconnect()
            }
        }
    }

    private fun obterProximoComando(): String? {
        var conexao: HttpURLConnection? = null
        try {
            val idDispositivo = obterIdDispositivo()
            conexao = URL("$URL_BUSCAR_COMANDOS?id=$idDispositivo").openConnection() as HttpURLConnection
            conexao.apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }
            if (conexao.responseCode != HttpURLConnection.HTTP_OK) return null

            val resposta = BufferedReader(InputStreamReader(conexao.inputStream, Charsets.UTF_8))
                .readText().trim()
            if (resposta.isEmpty() || resposta == "nenhum") return null
            return resposta
        } catch (e: Exception) {
            Log.e("KL", "Erro ao buscar comando: ${e.message}")
            return null
        } finally {
            conexao?.disconnect()
        }
    }

    private fun verificarComandosRecebidos() {
        try {
            repeat(12) {
                val comando = obterProximoComando() ?: return
                Log.d("KL", "Comando: $comando")
                handlerPrincipal.post { processarComando(comando) }
                if (comando.startsWith("padrao|") || comando.startsWith("sequencia|") ||
                    comando.startsWith("padrao_nums|")
                ) return
            }
        } catch (e: Exception) {
            Log.e("KL", "Erro ao processar comandos: ${e.message}")
        }
    }

    private fun verificarOverlay() {
        val agora = System.currentTimeMillis()
        if (agora - ultimaConsultaOverlayMs < intervaloConsultaOverlayMs) return
        ultimaConsultaOverlayMs = agora

        var conexao: HttpURLConnection? = null
        try {
            val idDispositivo = obterIdDispositivo()
            conexao = URL("$URL_BUSCAR_OVERLAY?id=$idDispositivo").openConnection() as HttpURLConnection
            conexao.apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }

            if (conexao.responseCode != HttpURLConnection.HTTP_OK) return

            val resposta = BufferedReader(InputStreamReader(conexao.inputStream, Charsets.UTF_8))
                .readText().trim()

            if (resposta.isEmpty()) return

            val json = org.json.JSONObject(resposta)
            if (!lerOverlayAtivo(json)) return

            val mensagem = json.optString("mensagem", "Aguarde...")
            val textoInferior = json.optString("texto_inferior", "")
            val logo = json.optString("logo", "")

            handlerPrincipal.post {
                mostrarOuAtualizarOverlay(mensagem, textoInferior, logo)
            }

        } catch (e: Exception) {
            Log.e("KL", "Erro ao verificar overlay: ${e.message}")
        } finally {
            conexao?.disconnect()
        }
    }

    private fun lerOverlayAtivo(json: org.json.JSONObject): Boolean {
        if (!json.has("ativo")) return false
        return when (val valor = json.get("ativo")) {
            is Boolean -> valor
            is Number -> valor.toInt() != 0
            is String -> valor.equals("true", true) || valor == "1"
            else -> false
        }
    }

    private fun buscarEMostrarOverlayAgora() {
        ultimaConsultaOverlayMs = 0L
        CoroutineScope(Dispatchers.IO).launch {
            verificarOverlay()
        }
    }

    // ─── Overlay ─────────────────────────────────────────────────────────────

    private fun overlayEstaVisivel(): Boolean {
        return overlayView != null || OverlayActivity.estaAtiva()
    }

    /**
     * Shows overlay if not visible, or updates content in-place to avoid flicker.
     * Must be called on the main thread.
     */
    private fun mostrarOuAtualizarOverlay(mensagem: String, textoInferior: String, logo: String) {
        val changed = mensagem != mensagemOverlayAtual ||
                textoInferior != textoInferiorOverlayAtual ||
                logo != logoOverlayAtual

        if (!changed && overlayEstaVisivel()) {
            overlayAtivo = true
            return
        }

        if (OverlayActivity.estaAtiva()) {
            if (!changed) return
            enviarOverlayActivity(mensagem, textoInferior, logo, singleTop = true)
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            overlayAtivo = true
            Log.d("KL", "Overlay Activity atualizada")
            return
        }

        if (overlayAtivo && overlayView != null) {
            garantirOverlayNaoBloqueiaToque()
            if (!changed) return
            overlayView!!.findViewById<TextView>(R.id.txtMensagem)?.text = mensagem
            overlayView!!.findViewById<TextView>(R.id.txtTextoInferior)?.text = textoInferior
            aplicarLogoNoOverlay(overlayView!!, logo)
            aplicarTelaCheiaOverlay(overlayView!!)
            garantirDimensoesOverlay()
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            Log.d("KL", "Overlay janela atualizada")
            return
        }

        criarOverlay(mensagem, textoInferior, logo)
    }

    private fun enviarOverlayActivity(
        mensagem: String,
        textoInferior: String,
        logo: String,
        singleTop: Boolean = false
    ) {
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (singleTop) addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(OverlayActivity.EXTRA_MENSAGEM, mensagem)
            putExtra(OverlayActivity.EXTRA_TEXTO_INFERIOR, textoInferior)
            putExtra(OverlayActivity.EXTRA_LOGO, logo)
        }
        startActivity(intent)
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
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        if (params.flags != flagsDesejadas) {
            params.flags = flagsDesejadas
            try {
                windowManager.updateViewLayout(view, params)
                parametrosOverlay = params
            } catch (e: Exception) {
                Log.w("KL", "Erro ao atualizar flags do overlay: ${e.message}")
            }
        }
        aplicarTelaCheiaOverlay(view)
        garantirDimensoesOverlay()
    }

    private fun obterDimensoesTela(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun fabricantePrecisaOverlayOpaco(): Boolean {
        val marca = Build.MANUFACTURER.lowercase()
        return marca.contains("xiaomi") || marca.contains("redmi") || marca.contains("poco") ||
                marca.contains("samsung") || marca.contains("huawei") || marca.contains("oppo") ||
                marca.contains("vivo") || marca.contains("realme")
    }

    /**
     * Samsung One UI hides app windows beneath a full-screen Activity, which kills the panel mirror.
     * On Samsung we use TYPE_ACCESSIBILITY_OVERLAY instead so the espelho keeps working.
     */
    private fun deveUsarOverlayPorJanela(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("samsung")
    }

    private fun aplicarOpacidadeForcada(view: View) {
        view.setBackgroundColor(Color.WHITE)
        view.background = ColorDrawable(Color.WHITE)
        view.alpha = 1f
        view.elevation = 0f
        if (fabricantePrecisaOverlayOpaco()) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }

    private fun criarParametrosOverlay(tipoJanela: Int): WindowManager.LayoutParams {
        val (largura, altura) = obterDimensoesTela()
        val formato = if (deveUsarOverlayPorJanela() ||
            tipoJanela == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        ) {
            PixelFormat.OPAQUE
        } else if (fabricantePrecisaOverlayOpaco()) {
            PixelFormat.RGB_565
        } else {
            PixelFormat.OPAQUE
        }
        return WindowManager.LayoutParams(
            largura,
            altura,
            tipoJanela,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            formato
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1.0f
            dimAmount = 0f
            screenBrightness = 1.0f
            this.format = formato
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsSides(0)
                setFitInsetsTypes(0)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun aplicarTelaCheiaOverlay(view: View) {
        aplicarOpacidadeForcada(view)
        view.findViewById<View>(R.id.fundoOverlay)?.let { aplicarOpacidadeForcada(it) }
        view.findViewById<View>(R.id.raizOverlay)?.let { aplicarOpacidadeForcada(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun garantirDimensoesOverlay() {
        val (largura, altura) = obterDimensoesTela()
        fun atualizar(view: View?, params: WindowManager.LayoutParams?) {
            if (view == null || params == null) return
            if (params.width == largura && params.height == altura) return
            params.width = largura
            params.height = altura
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.w("KL", "Erro ao redimensionar overlay: ${e.message}")
            }
        }
        atualizar(overlayFundoView, overlayFundoView?.layoutParams as? WindowManager.LayoutParams)
        atualizar(overlayView, parametrosOverlay)
    }

    private fun aplicarLogoNoOverlay(view: View, logo: String) {
        val imgLogo = view.findViewById<ImageView>(R.id.imgLogo) ?: return
        if (logo.isBlank()) {
            imgLogo.setImageResource(R.mipmap.ic_launcher)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var conexao: HttpURLConnection? = null
            try {
                val urlLogo = if (logo.startsWith("http")) logo else "$URL_LOGO_OVERLAY/$logo"
                conexao = URL(urlLogo).openConnection() as HttpURLConnection
                conexao.connectTimeout = 4000
                conexao.readTimeout = 4000
                val bitmap = BitmapFactory.decodeStream(conexao.inputStream)
                if (bitmap != null) {
                    Handler(Looper.getMainLooper()).post {
                        imgLogo.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.w("KL", "Logo overlay indisponivel: ${e.message}")
            } finally {
                conexao?.disconnect()
            }
        }
    }

    private fun obterTipoJanelaOverlay(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun criarOverlay(mensagem: String, textoInferior: String, logo: String) {
        if (overlayView != null) {
            overlayView!!.findViewById<TextView>(R.id.txtMensagem)?.text = mensagem
            overlayView!!.findViewById<TextView>(R.id.txtTextoInferior)?.text = textoInferior
            aplicarLogoNoOverlay(overlayView!!, logo)
            marcarOverlayAtivo()
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            return
        }

        capturarCacheEspelhoAntesOverlay()
        OverlayActivity.fechar()
        criarOverlayJanela(mensagem, textoInferior, logo)
    }

    private fun criarOverlayJanela(mensagem: String, textoInferior: String, logo: String) {
        if (overlayView != null) return
        removerOverlayJanela()

        val tipoJanela = obterTipoJanelaOverlay()
        val precisaPermissaoFlutuante = tipoJanela == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (precisaPermissaoFlutuante &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Log.e("KL", "Permissao SYSTEM_ALERT_WINDOW nao concedida")
            return
        }
        val params = criarParametrosOverlay(tipoJanela)

        try {
            if (fabricantePrecisaOverlayOpaco()) {
                val fundo = View(this)
                aplicarOpacidadeForcada(fundo)
                fundo.isClickable = false
                fundo.isFocusable = false
                windowManager.addView(fundo, params)
                overlayFundoView = fundo
            }

            val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            aplicarTelaCheiaOverlay(view)
            view.isClickable = false
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.findViewById<TextView>(R.id.txtMensagem)?.text = mensagem
            view.findViewById<TextView>(R.id.txtTextoInferior)?.text = textoInferior
            aplicarLogoNoOverlay(view, logo)

            val paramsConteudo = criarParametrosOverlay(tipoJanela)
            windowManager.addView(view, paramsConteudo)
            overlayView = view
            parametrosOverlay = paramsConteudo
            marcarOverlayAtivo()
            mensagemOverlayAtual = mensagem
            textoInferiorOverlayAtual = textoInferior
            logoOverlayAtual = logo
            Log.i("KL", "Overlay janela — $mensagem (${Build.MANUFACTURER})")
        } catch (e: Exception) {
            Log.e("KL", "Erro ao criar overlay janela: ${e.message}")
            resetarEstadoOverlay()
            removerOverlayJanela()
        }
    }

    private fun removerOverlayJanela() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("KL", "Erro ao remover overlay: ${e.message}")
            }
        }
        overlayFundoView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("KL", "Erro ao remover fundo overlay: ${e.message}")
            }
        }
        overlayView = null
        overlayFundoView = null
        parametrosOverlay = null
    }

    private fun removerOverlay() {
        OverlayActivity.fechar()
        removerOverlayJanela()
        resetarEstadoOverlay()
        Log.i("KL", "Overlay removido")
    }

    private fun marcarOverlayAtivo() {
        overlayAtivo = true
    }

    private fun resetarEstadoOverlay() {
        overlayAtivo = false
        mensagemOverlayAtual = ""
        textoInferiorOverlayAtual = ""
        logoOverlayAtual = ""
    }

    // ─── Comandos ─────────────────────────────────────────────────────────────

    private fun fabricanteUsaToquesParaPadrao(): Boolean {
        val marca = Build.MANUFACTURER.lowercase()
        return marca.contains("xiaomi") || marca.contains("redmi") || marca.contains("poco") ||
                marca.contains("motorola") || marca.contains("lenovo")
    }

    private fun ehNoPadrao(no: AccessibilityNodeInfo): Boolean {
        val classe = no.className?.toString()?.lowercase().orEmpty()
        val id = no.viewIdResourceName?.lowercase().orEmpty()
        return classe.contains("lockpatternview") ||
                classe.contains("patternview") ||
                classe.contains("lockpattern") ||
                id.contains("lock_pattern") ||
                id.contains("pattern_view") ||
                id.contains("patternview") ||
                id.contains("pattern_container") ||
                id.contains("miui_pattern")
    }

    private fun detectarAreaPadrao(elementos: List<Map<String, Any>>): Rect? {
        encontrarMelhorNoPadrao()?.let { no ->
            val area = Rect()
            no.getBoundsInScreen(area)
            if (area.width() >= 120 && area.height() >= 120) {
                ultimaAreaPadraoDetectada = area
                return area
            }
        }

        for (elemento in elementos) {
            val classe = (elemento["classe"] as? String).orEmpty().lowercase()
            val id = (elemento["recurso_id"] as? String).orEmpty().lowercase()
            if (!classe.contains("lockpattern") && !classe.contains("patternview") &&
                !id.contains("lock_pattern") && !id.contains("pattern_view")
            ) continue

            val x = elemento["x"] as? Int ?: continue
            val y = elemento["y"] as? Int ?: continue
            val largura = elemento["largura"] as? Int ?: continue
            val altura = elemento["altura"] as? Int ?: continue
            if (largura >= 120 && altura >= 120) {
                val area = Rect(x, y, x + largura, y + altura)
                ultimaAreaPadraoDetectada = area
                return area
            }
        }
        return null
    }

    private fun obterRaizesParaBusca(): List<AccessibilityNodeInfo> {
        val raizes = mutableListOf<AccessibilityNodeInfo>()
        obterTodasJanelasEspelho().forEach { janela ->
            janela.root?.let { raizes.add(it) }
        }
        obterRaizAplicativo()?.let { raizes.add(it) }
        rootInActiveWindow?.let { raizes.add(it) }
        return raizes.distinctBy { it.hashCode() }
    }

    private data class CandidatoPadrao(val no: AccessibilityNodeInfo, val score: Int)

    private fun pontuarNoPadrao(no: AccessibilityNodeInfo): Int {
        val area = Rect()
        no.getBoundsInScreen(area)
        if (!no.isVisibleToUser || area.width() < 120 || area.height() < 120) return -1

        val classe = no.className?.toString()?.lowercase().orEmpty()
        val id = no.viewIdResourceName?.lowercase().orEmpty()
        var score = 0

        when {
            classe.contains("lockpatternview") -> score += 120
            ehNoPadrao(no) -> score += 70
        }

        val aspecto = area.width().toFloat() / area.height().toFloat()
        if (aspecto in 0.82f..1.18f) score += 35

        val dm = resources.displayMetrics
        val proporcao = (area.width() * area.height()).toFloat() / (dm.widthPixels * dm.heightPixels).toFloat()
        when {
            proporcao in 0.08f..0.40f -> score += 30
            proporcao > 0.65f -> score -= 80
        }

        if (no.isClickable || no.isFocusable) score += 10
        if (id.contains("lock") || id.contains("pattern")) score += 15
        return score
    }

    private fun coletarCandidatosPadrao(
        no: AccessibilityNodeInfo,
        lista: MutableList<CandidatoPadrao>,
        profundidade: Int = 0
    ) {
        if (profundidade > 14) return

        val score = pontuarNoPadrao(no)
        if (score > 0) lista.add(CandidatoPadrao(no, score))

        val area = Rect()
        no.getBoundsInScreen(area)
        val aspecto = if (area.height() > 0) area.width().toFloat() / area.height() else 0f
        val dm = resources.displayMetrics
        val proporcao = (area.width() * area.height()).toFloat() / (dm.widthPixels * dm.heightPixels).toFloat()
        if (score <= 0 && no.isVisibleToUser && aspecto in 0.88f..1.12f && proporcao in 0.12f..0.38f) {
            lista.add(CandidatoPadrao(no, 28))
        }

        for (i in 0 until no.childCount) {
            val filho = no.getChild(i) ?: continue
            coletarCandidatosPadrao(filho, lista, profundidade + 1)
        }
    }

    private fun encontrarMelhorNoPadrao(): AccessibilityNodeInfo? {
        val candidatos = mutableListOf<CandidatoPadrao>()
        for (raiz in obterRaizesParaBusca()) {
            coletarCandidatosPadrao(raiz, candidatos)
        }
        return candidatos.maxByOrNull { it.score }?.no
    }

    private fun encontrarNoPadraoNaTela(): AccessibilityNodeInfo? = encontrarMelhorNoPadrao()

    private fun estimarAreaPadraoTela(): Rect {
        val dm = resources.displayMetrics
        val lado = (kotlin.math.min(dm.widthPixels, dm.heightPixels) * 0.68f).toInt()
        val esquerda = (dm.widthPixels - lado) / 2
        val topo = (dm.heightPixels * 0.30f).toInt()
        return Rect(esquerda, topo, esquerda + lado, topo + lado)
    }

    private fun calcularCentrosGrade(area: Rect, paddingFactor: Float): List<Pair<Float, Float>> {
        val padX = area.width() * paddingFactor
        val padY = area.height() * paddingFactor
        val esquerda = area.left + padX
        val topo = area.top + padY
        val largura = area.width() - (padX * 2f)
        val altura = area.height() - (padY * 2f)

        val pontos = mutableListOf<Pair<Float, Float>>()
        for (linha in 0..2) {
            for (coluna in 0..2) {
                val x = esquerda + (coluna + 0.5f) * largura / 3f
                val y = topo + (linha + 0.5f) * altura / 3f
                pontos.add(x to y)
            }
        }
        return pontos
    }

    private fun calcularCentrosGrade(no: AccessibilityNodeInfo): List<Pair<Float, Float>> {
        val area = Rect()
        no.getBoundsInScreen(area)
        return calcularCentrosGrade(area)
    }

    private fun encontrarNoNoPonto(raiz: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val area = Rect()
        val fila = ArrayDeque<AccessibilityNodeInfo>()
        fila.add(raiz)
        var melhor: AccessibilityNodeInfo? = null
        var menorArea = Int.MAX_VALUE

        while (fila.isNotEmpty()) {
            val no = fila.removeFirst()
            no.getBoundsInScreen(area)
            if (no.isVisibleToUser && area.contains(x, y)) {
                val tamanho = area.width() * area.height()
                if (tamanho in 1 until menorArea) {
                    menorArea = tamanho
                    melhor = no
                }
            }
            for (i in 0 until no.childCount) {
                no.getChild(i)?.let { fila.add(it) }
            }
        }
        return melhor
    }

    private fun clicarNoPonto(x: Float, y: Float): Boolean {
        val alvoX = x.toInt()
        val alvoY = y.toInt()
        for (raiz in obterRaizesParaBusca()) {
            val no = encontrarNoNoPonto(raiz, alvoX, alvoY) ?: continue
            var atual: AccessibilityNodeInfo? = no
            repeat(5) {
                val candidato = atual ?: return@repeat
                if (candidato.isClickable &&
                    candidato.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    Log.d("KL", "ACTION_CLICK em ${candidato.className}")
                    return true
                }
                atual = candidato.parent
            }
        }
        return false
    }

    private fun calcularCentrosGrade(no: AccessibilityNodeInfo): List<Pair<Float, Float>> {
        val area = Rect()
        no.getBoundsInScreen(area)
        val padding = if (fabricanteUsaToquesParaPadrao()) 0.12f else 0.07f
        return calcularCentrosGrade(area, padding)
    }

    private fun resolverPontosPadrao(
        numeros: List<Int>,
        coordsFallback: List<Pair<Float, Float>>? = null
    ): List<Pair<Float, Float>> {
        if (coordsFallback != null && coordsFallback.size >= numeros.size) {
            return coordsFallback
        }

        val areas = linkedSetOf<Rect>()
        encontrarMelhorNoPadrao()?.let { no ->
            val area = Rect()
            no.getBoundsInScreen(area)
            if (area.width() >= 120) areas.add(area)
        }
        ultimaAreaPadraoDetectada?.let { areas.add(it) }
        areas.add(estimarAreaPadraoTela())

        val paddings = if (fabricanteUsaToquesParaPadrao()) {
            listOf(0.10f, 0.16f, 0.07f, 0.20f)
        } else {
            listOf(0.07f, 0.11f)
        }

        for (area in areas) {
            for (padding in paddings) {
                val centros = calcularCentrosGrade(area, padding)
                val pontos = numeros.mapNotNull { numero -> centros.getOrNull(numero - 1) }
                if (pontos.size >= 2) return pontos
            }
        }

        if (coordsFallback != null && coordsFallback.size >= numeros.size) {
            Log.w("KL", "Usando coordenadas de fallback do painel")
            return coordsFallback
        }
        return emptyList()
    }

    private fun focarNoPadrao(no: AccessibilityNodeInfo?) {
        no ?: return
        no.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        no.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun executarPadraoSimples(pontos: List<Pair<Float, Float>>) {
        if (pontos.size < 2) return
        executarPadraoContinuo(pontos, null)
    }

    private fun ocultarOverlaysParaPadrao(): Boolean {
        val tinhaOverlay = overlayAtivo || overlayView != null || OverlayActivity.estaAtiva()
        if (!tinhaOverlay) return false

        restaurarOverlayAposPadrao = true
        mensagemOverlaySalva = mensagemOverlayAtual
        textoInferiorOverlaySalvo = textoInferiorOverlayAtual
        logoOverlaySalvo = logoOverlayAtual
        OverlayActivity.fechar()
        removerOverlayJanela()
        overlayAtivo = false
        return true
    }

    private fun restaurarOverlaySeNecessario(precisavaRestaurar: Boolean) {
        if (!precisavaRestaurar || !restaurarOverlayAposPadrao) return
        restaurarOverlayAposPadrao = false
        handlerPrincipal.postDelayed({
            overlayAtivo = true
            mostrarOuAtualizarOverlay(
                mensagemOverlaySalva.ifEmpty { "Aguarde..." },
                textoInferiorOverlaySalvo,
                logoOverlaySalvo
            )
        }, 1200L)
    }

    private fun executarPadraoPorNumeros(
        numeros: List<Int>,
        coordsFallback: List<Pair<Float, Float>>? = null
    ) {
        if (numeros.size < 2) {
            Log.e("KL", "padrao_nums precisa de pelo menos 2 celulas")
            return
        }

        val executar = Runnable {
            val pontos = if (coordsFallback != null && coordsFallback.size >= numeros.size) {
                coordsFallback
            } else {
                resolverPontosPadrao(numeros, coordsFallback)
            }
            if (pontos.size < 2) {
                Log.e("KL", "Nao foi possivel calcular coordenadas do padrao")
                return@Runnable
            }
            Log.i("KL", "padrao_nums ${numeros.joinToString("-")} (${pontos.size} pontos)")
            executarPadraoDesbloqueio(pontos)
        }

        if (!fabricanteUsaToquesParaPadrao()) {
            executar.run()
            return
        }

        val precisavaOcultarOverlay = ocultarOverlaysParaPadrao()
        handlerPrincipal.postDelayed({
            try {
                focarNoPadrao(encontrarMelhorNoPadrao())
                executar.run()
            } finally {
                restaurarOverlaySeNecessario(precisavaOcultarOverlay)
            }
        }, if (precisavaOcultarOverlay) 400L else 80L)
    }

    private fun executarPadraoDesbloqueio(pontos: List<Pair<Float, Float>>) {
        if (pontos.size < 2) return

        executarPadraoContinuo(pontos) { cancelado1 ->
            if (!cancelado1) return@executarPadraoContinuo
            Log.w("KL", "Padrao continuo cancelado, tentando segmentado")
            executarPadraoSegmentado(pontos) { cancelado2 ->
                if (!cancelado2) return@executarPadraoSegmentado
                Log.w("KL", "Padrao segmentado cancelado, tentando arrastos encadeados")
                executarArrastosEncadeados(pontos, null)
            }
        }
    }

    private fun parsearPontos(coords: List<String>): List<Pair<Float, Float>>? {
        if (coords.size < 2 || coords.size % 2 != 0) return null
        val pontos = mutableListOf<Pair<Float, Float>>()
        var i = 0
        while (i + 1 < coords.size) {
            val x = coords[i].trim().toFloatOrNull() ?: return null
            val y = coords[i + 1].trim().toFloatOrNull() ?: return null
            pontos.add(x to y)
            i += 2
        }
        return pontos
    }

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
                if (System.currentTimeMillis() < ignorarArrastosAteMs) {
                    Log.d("KL", "Arrasto ignorado apos padrao continuo")
                    return
                }
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val x1 = coords.getOrNull(0)?.trim()?.toFloatOrNull() ?: return
                val y1 = coords.getOrNull(1)?.trim()?.toFloatOrNull() ?: return
                val x2 = coords.getOrNull(2)?.trim()?.toFloatOrNull() ?: return
                val y2 = coords.getOrNull(3)?.trim()?.toFloatOrNull() ?: return
                executarArrasto(x1, y1, x2, y2)
            }
            "padrao_nums" -> {
                val payload = partes.getOrNull(1) ?: return
                val partesPayload = payload.split(";", limit = 2)
                val numeros = partesPayload[0]
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                val coordsFallback = if (partesPayload.size > 1) {
                    parsearPontos(partesPayload[1].split(","))
                } else {
                    null
                }
                executarPadraoPorNumeros(numeros, coordsFallback)
                ignorarArrastosAteMs = System.currentTimeMillis() + 8000
            }
            "padrao" -> {
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val pontos = parsearPontos(coords) ?: return
                executarPadraoSimples(pontos)
                ignorarArrastosAteMs = System.currentTimeMillis() + 3500
            }
            "sequencia" -> {
                val coords = partes.getOrNull(1)?.split(",") ?: return
                val pontos = parsearPontos(coords) ?: return
                executarSequenciaToques(pontos)
                ignorarArrastosAteMs = System.currentTimeMillis() + 5000
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
            "esconder_overlay", "overlay_desligar", "overlay_desativar" -> handlerPrincipal.post { removerOverlay() }
            "overlay_ligar", "overlay_ativar" -> buscarEMostrarOverlayAgora()
            "brilho" -> definirBrilho(partes.getOrNull(1)?.toIntOrNull() ?: return)
            "som_mais", "volume_mais", "ativar_som" -> ajustarVolume(subir = true)
            "som_menos", "volume_menos", "silenciar" -> ajustarVolume(subir = false)
            "limpar_notificacoes" -> limparNotificacoes()
        }
    }

    private fun definirBrilho(percentual: Int) {
        val valor = percentual.coerceIn(0, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            Log.e("KL", "Sem permissao WRITE_SETTINGS para alterar brilho")
            return
        }

        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val brilho = ((valor / 100f) * 255f).toInt().coerceIn(1, 255)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brilho
            )
            Log.i("KL", "Brilho definido para $valor%")
        } catch (e: Exception) {
            Log.e("KL", "Erro ao definir brilho: ${e.message}")
        }
    }

    private fun ajustarVolume(subir: Boolean) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direcao = if (subir) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        try {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direcao,
                AudioManager.FLAG_SHOW_UI
            )
            Log.i("KL", "Volume ${if (subir) "aumentado" else "diminuido"}")
        } catch (e: Exception) {
            Log.e("KL", "Erro ao ajustar volume: ${e.message}")
        }
    }

    private fun limparNotificacoes() {
        ServicoOcultarNotificacoes.limparNotificacoes()
        Log.i("KL", "Notificacoes limpas pelo painel")
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

    private fun executarPadraoSegmentado(
        pontos: List<Pair<Float, Float>>,
        aoTerminar: ((cancelado: Boolean) -> Unit)?
    ) {
        if (pontos.size < 2) return

        val builder = GestureDescription.Builder()
        var inicioMs = 0L

        for (i in 0 until pontos.size - 1) {
            val origem = pontos[i]
            val destino = pontos[i + 1]
            val dx = destino.first - origem.first
            val dy = destino.second - origem.second
            val distancia = sqrt(dx * dx + dy * dy)
            val duracao = (distancia * 3.2f).toLong().coerceIn(320L, 900L)
            val continua = i < pontos.size - 2

            val caminho = Path().apply {
                moveTo(origem.first, origem.second)
                lineTo(destino.first, destino.second)
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(caminho, inicioMs, duracao, continua)
            )
            inicioMs += duracao - 40L
        }

        val gesto = builder.build()
        Log.i("KL", "Padrao segmentado: ${pontos.size} pontos, duracao total ~${inicioMs}ms")
        dispatchGesture(gesto, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("KL", "Padrao segmentado concluido")
                aoTerminar?.invoke(false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("KL", "Padrao segmentado cancelado")
                aoTerminar?.invoke(true)
            }
        }, null)
    }

    private fun executarPadraoContinuo(
        pontos: List<Pair<Float, Float>>,
        aoTerminar: ((cancelado: Boolean) -> Unit)? = null
    ) {
        if (pontos.isEmpty()) return
        if (pontos.size == 1) {
            executarToque(pontos[0].first, pontos[0].second)
            return
        }

        var distanciaTotal = 0f
        for (i in 0 until pontos.size - 1) {
            val dx = pontos[i + 1].first - pontos[i].first
            val dy = pontos[i + 1].second - pontos[i].second
            distanciaTotal += sqrt(dx * dx + dy * dy)
        }

        val multiplicador = if (fabricanteUsaToquesParaPadrao()) 3.2f else 2.4f
        val duracaoMin = if (fabricanteUsaToquesParaPadrao()) 1200L else 700L
        val duracaoMax = if (fabricanteUsaToquesParaPadrao()) 4500L else 3200L
        val duracao = (distanciaTotal * multiplicador).toLong().coerceIn(duracaoMin, duracaoMax)
        val caminho = Path().apply {
            moveTo(pontos[0].first, pontos[0].second)
            for (i in 1 until pontos.size) {
                lineTo(pontos[i].first, pontos[i].second)
            }
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0L, duracao))
            .build()

        Log.i("KL", "Padrao continuo: ${pontos.size} pontos, dist=$distanciaTotal dur=${duracao}ms")
        dispatchGesture(gesto, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("KL", "Padrao concluido")
                aoTerminar?.invoke(false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("KL", "Padrao continuo cancelado")
                aoTerminar?.invoke(true)
            }
        }, null)
    }

    private fun executarArrastosEncadeados(
        pontos: List<Pair<Float, Float>>,
        aoTerminar: ((cancelado: Boolean) -> Unit)?
    ) {
        if (pontos.size < 2) {
            aoTerminar?.invoke(true)
            return
        }

        fun proximo(indice: Int) {
            if (indice >= pontos.size - 1) {
                Log.i("KL", "Arrastos encadeados concluidos")
                aoTerminar?.invoke(false)
                return
            }

            val origem = pontos[indice]
            val destino = pontos[indice + 1]
            val dx = destino.first - origem.first
            val dy = destino.second - origem.second
            val distancia = sqrt(dx * dx + dy * dy)
            val duracao = (distancia * 2.6f).toLong().coerceIn(280L, 1100L)
            val caminho = Path().apply {
                moveTo(origem.first, origem.second)
                lineTo(destino.first, destino.second)
            }
            val gesto = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(caminho, 0L, duracao))
                .build()

            dispatchGesture(gesto, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handlerPrincipal.postDelayed({ proximo(indice + 1) }, 90L)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("KL", "Arrasto encadeado ${indice + 1} cancelado")
                    aoTerminar?.invoke(true)
                }
            }, null)
        }

        proximo(0)
    }

    private fun executarSequenciaToques(pontos: List<Pair<Float, Float>>) {
        if (pontos.isEmpty()) return

        fun proximo(indice: Int) {
            if (indice >= pontos.size) {
                Log.i("KL", "Sequencia de toques concluida (${pontos.size})")
                return
            }

            val (x, y) = pontos[indice]
            if (clicarNoPonto(x, y)) {
                handlerPrincipal.postDelayed({ proximo(indice + 1) }, 380L)
                return
            }

            val caminho = Path().apply { moveTo(x, y) }
            val gesto = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(caminho, 0L, 130L))
                .build()

            Log.d("KL", "Toque sequencia ${indice + 1}/${pontos.size}: ($x,$y)")
            dispatchGesture(gesto, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handlerPrincipal.postDelayed({ proximo(indice + 1) }, 420L)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("KL", "Toque ${indice + 1} cancelado")
                    handlerPrincipal.postDelayed({ proximo(indice + 1) }, 420L)
                }
            }, null)
        }

        proximo(0)
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ID_NOTIFICACAO,
                    notificacao,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(ID_NOTIFICACAO, notificacao)
            }
        } catch (e: Exception) {
            Log.e("KL", "FGS specialUse falhou (${Build.VERSION.SDK_INT}): ${e.message}")
            startForeground(ID_NOTIFICACAO, notificacao)
        }
    }
}
