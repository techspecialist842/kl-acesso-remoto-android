package com.meuacesso.remoto

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class ServicoOcultarNotificacoes : NotificationListenerService() {

    companion object {
        @Volatile
        private var instancia: ServicoOcultarNotificacoes? = null

        fun cancelarNotificacoesVisiveis() {
            instancia?.executarCancelamento()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instancia = this
        if (ControleGestosService.deveSuprimirNotificacoes()) {
            executarCancelamento()
        }
    }

    override fun onListenerDisconnected() {
        if (instancia === this) {
            instancia = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ControleGestosService.deveSuprimirNotificacoes()) return
        if (ehNotificacaoDoApp(sbn)) return
        try {
            cancelNotification(sbn.key)
            Log.d("KL", "Notificacao bloqueada: ${sbn.packageName}")
        } catch (e: Exception) {
            Log.w("KL", "Falha ao bloquear notificacao: ${e.message}")
        }
    }

    private fun ehNotificacaoDoApp(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == packageName
    }

    private fun executarCancelamento() {
        try {
            activeNotifications?.forEach { sbn ->
                if (!ehNotificacaoDoApp(sbn)) {
                    cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) {
            Log.w("KL", "Falha ao limpar notificacoes ativas: ${e.message}")
        }
    }
}
