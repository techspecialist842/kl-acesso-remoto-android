package com.meuacesso.remoto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReiniciarServico : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        // Reinicia o serviço se for fechado ou o celular ligar
        val servico = Intent(context, ControleGestosService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(servico)
        } else {
            context.startService(servico)
        }
    }
}