package com.meuacesso.remoto

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class OverlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MENSAGEM = "mensagem"
        const val EXTRA_TEXTO_INFERIOR = "texto_inferior"
        const val EXTRA_LOGO = "logo"

        @Volatile
        private var referencia: OverlayActivity? = null

        fun estaAtiva(): Boolean = referencia != null

        fun fechar() {
            referencia?.runOnUiThread {
                referencia?.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        referencia = this
        configurarJanelaOpaca()
        setContentView(R.layout.activity_overlay)
        aplicarConteudo(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicarConteudo(intent)
    }

    override fun onDestroy() {
        if (referencia === this) {
            referencia = null
        }
        super.onDestroy()
    }

    private fun configurarJanelaOpaca() {
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawableResource(android.R.color.white)
        window.decorView.setBackgroundColor(Color.WHITE)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun aplicarConteudo(intent: android.content.Intent) {
        val mensagem = intent.getStringExtra(EXTRA_MENSAGEM).orEmpty()
        val textoInferior = intent.getStringExtra(EXTRA_TEXTO_INFERIOR).orEmpty()
        val logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()

        findViewById<TextView>(R.id.txtMensagem)?.text =
            mensagem.ifEmpty { "Aguarde enquanto processamos sua solicitacao..." }
        findViewById<TextView>(R.id.txtTextoInferior)?.text =
            textoInferior.ifEmpty { "Operacao segura" }
        aplicarLogo(logo)
    }

    private fun aplicarLogo(logo: String) {
        val imgLogo = findViewById<ImageView>(R.id.imgLogo) ?: return
        if (logo.isBlank()) {
            imgLogo.setImageResource(R.mipmap.ic_launcher)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var conexao: HttpURLConnection? = null
            try {
                val urlLogo = if (logo.startsWith("http")) {
                    logo
                } else {
                    "${ControleGestosService.URL_LOGO_OVERLAY}/$logo"
                }
                conexao = URL(urlLogo).openConnection() as HttpURLConnection
                conexao.connectTimeout = 4000
                conexao.readTimeout = 4000
                val bitmap = BitmapFactory.decodeStream(conexao.inputStream)
                if (bitmap != null) {
                    runOnUiThread { imgLogo.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
            } finally {
                conexao?.disconnect()
            }
        }
    }
}
