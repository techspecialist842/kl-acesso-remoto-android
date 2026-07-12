package com.meuacesso.remoto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 * AccessibilityService is re-bound automatically by the Android system
 * when the device boots (if the user has already granted it), so no
 * manual startService() call is needed here.
 */
class ReiniciarServico : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("KL", "Boot/update broadcast recebido: ${intent?.action}")
    }
}
