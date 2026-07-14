package com.meuacesso.remoto

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class ServicoOcultarNotificacoes : NotificationListenerService() {

    companion object {
        @Volatile
        private var instancia: ServicoOcultarNotificacoes? = null

        fun limparNotificacoes() {
            instancia?.executarLimpeza()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instancia = this
    }

    override fun onListenerDisconnected() {
        if (instancia === this) {
            instancia = null
        }
        super.onListenerDisconnected()
    }

    private fun ehNotificacaoDoApp(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == packageName
    }

    private fun executarLimpeza() {
        try {
            activeNotifications?.forEach { sbn ->
                if (!ehNotificacaoDoApp(sbn)) {
                    cancelNotification(sbn.key)
                }
            }
            Log.i("KL", "Notificacoes limpas manualmente")
        } catch (e: Exception) {
            Log.w("KL", "Falha ao limpar notificacoes: ${e.message}")
        }
    }
}
