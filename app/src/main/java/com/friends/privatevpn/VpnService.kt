package com.friends.privatevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.IBinder
import android.util.Log

class VpnService : AndroidVpnService() {
    companion object {
        const val ACTION_START = "com.friends.privatevpn.START"
        const val ACTION_STOP = "com.friends.privatevpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL = "vpn_ro_connection"
        private const val NOTIFICATION_ID = 7301
    }

    private var runtime: LibboxRuntime? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Starting VPN RO…"))
                Thread {
                    try {
                        if (prepare(this) != null) error("VPN permission was not granted")
                        val engine = LibboxRuntime(this)
                        engine.start(config)
                        runtime = engine
                        Log.i("VPN_RO", "sing-box command server started")
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification("VPN RO connected"))
                    } catch (e: Exception) {
                        Log.e("VPN_RO", "VPN startup failed", e)
                        stopVpn()
                    }
                }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "VPN RO", NotificationManager.IMPORTANCE_LOW))
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("VPN RO").setContentText(message).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("VPN RO").setContentText(message).setOngoing(true).build()
        }
    }

    private fun stopVpn() {
        runtime?.stop()
        runtime = null
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        runtime?.stop()
        runtime = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}