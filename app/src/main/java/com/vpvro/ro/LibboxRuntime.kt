package com.friends.privatevpn

import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions

/** Starts and owns the real sing-box libbox command server. */
internal class LibboxRuntime(private val service: VpnService) {
    private var commandServer: CommandServer? = null

    @Synchronized
    fun start(config: String) {
        check(commandServer == null) { "sing-box is already running" }
        setupLibbox()

        Libbox.promoteOOMDraft()
        Libbox.promotePowerReportDraft()

        val server = CommandServer(service, service)
        try {
            server.start()
            commandServer = server
            // This is the actual core startup call. It parses the config, creates the TUN
            // through VpnService.openTun(), and starts the selected outbound.
            server.startOrReloadService(config, OverrideOptions())
            Log.i(TAG, "sing-box service started")
        } catch (error: Throwable) {
            commandServer = null
            runCatching { server.closeService() }
            runCatching { server.close() }
            service.closeTun()
            throw (error.cause ?: error)
        }
    }

    @Synchronized
    fun reload(config: String) {
        val server = commandServer ?: error("sing-box is not running")
        server.startOrReloadService(config, OverrideOptions())
    }

    @Synchronized
    fun closeServiceFromEngine() {
        commandServer?.closeService()
    }

    @Synchronized
    fun stop() {
        val server = commandServer
        commandServer = null
        service.closeTun()
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed", it) }
        }
    }

    private fun setupLibbox() {
        synchronized(SETUP_LOCK) {
            if (isSetup) return
            val base = service.filesDir.apply { mkdirs() }
            val work = (service.getExternalFilesDir(null) ?: service.filesDir).apply { mkdirs() }
            val temp = service.cacheDir.apply { mkdirs() }
            val options = SetupOptions().apply {
                basePath = base.absolutePath
                workingPath = work.absolutePath
                tempPath = temp.absolutePath
                logMaxLines = 1500
                debug = false
                appVersion = "1"
                appMarketingVersion = "1.0"
            }
            Libbox.setup(options)
            isSetup = true
        }
    }

    companion object {
        private const val TAG = "VPN_RO_LIBBOX"
        private val SETUP_LOCK = Any()
        @Volatile private var isSetup = false
    }
}
