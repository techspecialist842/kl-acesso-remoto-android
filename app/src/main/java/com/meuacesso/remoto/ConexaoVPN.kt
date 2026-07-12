package com.meuacesso.remoto

import android.net.VpnService
import android.content.Intent
import android.os.IBinder

class ConexaoVPN : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}