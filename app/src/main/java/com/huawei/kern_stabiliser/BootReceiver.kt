package com.huawei.kern_stabiliser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// used for starting, auto-restarting, stopping the Main Service
class MyBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_RESTART_FOREVER_SERVICE = "ACTION_RESTART_FOREVER_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = context.packageName
        val thisActionStartService = "${packageName}.${ACTION_START_SERVICE}"
        val thisActionStopService = "${packageName}.${ACTION_STOP_SERVICE}"
        val thisActionRestartForeverService = "${packageName}.${ACTION_RESTART_FOREVER_SERVICE}"

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, thisActionStartService, thisActionRestartForeverService -> {
                val serviceIntent = Intent(context, SysGuardService::class.java)
                context.startForegroundService(serviceIntent)
            }

            thisActionStopService -> {
                val serviceIntent = Intent(context, SysGuardService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}
