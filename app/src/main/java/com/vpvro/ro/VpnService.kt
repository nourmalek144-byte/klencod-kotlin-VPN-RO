package com.friends.privatevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.IBinder
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections

/** Android VPN host for the official sing-box libbox 1.14.x API. */
class VpnService : AndroidVpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val ACTION_START = "com.friends.privatevpn.START"
        const val ACTION_STOP = "com.friends.privatevpn.STOP"
        const val ACTION_STATE = "com.friends.privatevpn.STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "vpn_ro_connection"
        private const val NOTIFICATION_ID = 7301
        private const val TAG = "VPN_RO"
    }

    @Volatile private var runtime: LibboxRuntime? = null
    @Volatile private var starting = false
    private var tunInterface: android.os.ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var currentConfig: String? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java)!! }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    publishState(false, "VPN configuration is empty")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (runtime != null || starting) return START_NOT_STICKY
                currentConfig = config
                startForeground(NOTIFICATION_ID, buildNotification("Starting VPN RO…"))
                starting = true
                Thread({
                    try {
                        val engine = LibboxRuntime(this)
                        engine.start(config)
                        runtime = engine
                        publishState(true, "Connected")
                        getSystemService(NotificationManager::class.java)!!
                            .notify(NOTIFICATION_ID, buildNotification("VPN RO connected"))
                    } catch (error: Throwable) {
                        Log.e(TAG, "sing-box startup failed", error)
                        stopVpn(error.message ?: error.javaClass.simpleName)
                    } finally {
                        starting = false
                    }
                }, "VPN-RO-libbox-start").start()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN RO connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("VPN RO")
                .setContentText(message)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("VPN RO")
                .setContentText(message)
                .setOngoing(true)
                .build()
        }
    }

    private fun stopVpn(reason: String = "Disconnected") {
        val engine = runtime
        runtime = null
        try {
            engine?.stop()
        } catch (error: Throwable) {
            Log.e(TAG, "Error while stopping sing-box", error)
        }
        closeTun()
        closeDefaultInterfaceMonitor(defaultInterfaceListener)
        starting = false
        publishState(false, reason)
        stopForeground(true)
        stopSelf()
    }

    internal fun closeTun() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun publishState(isConnected: Boolean, message: String) {
        getSharedPreferences("vpn_ro", MODE_PRIVATE).edit()
            .putBoolean("connected", isConnected)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, isConnected)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        val engine = runtime
        runtime = null
        engine?.stop()
        closeTun()
        closeDefaultInterfaceMonitor(defaultInterfaceListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    // CommandServerHandler callbacks.
    override fun serviceStop() {
        val engine = runtime
        runtime = null
        closeTun()
        runCatching { engine?.closeServiceFromEngine() }
        publishState(false, "sing-box stopped")
        stopForeground(true)
        stopSelf()
    }

    override fun serviceReload() {
        val config = currentConfig ?: return
        runCatching { runtime?.reload(config) }
            .onFailure {
                Log.e(TAG, "sing-box reload failed", it)
                publishState(false, it.message ?: "Reload failed")
            }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = SystemProxyStatus()
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }
    override fun connectSSHAgent(): Int = -1

    // PlatformInterface callbacks. The protect(fd) call prevents the proxy socket from
    // being routed back into the VPN's own TUN interface.
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("Android could not protect the proxy socket from the VPN")
    }
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission has not been granted")
        val builder = Builder().setSession("VPN RO").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val ipv4 = options.inet4Address
        while (ipv4.hasNext()) {
            val address = ipv4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val ipv6 = options.inet6Address
        while (ipv6.hasNext()) {
            val address = ipv6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            // VPN RO is a full-tunnel client. Add both default routes; protect(fd) keeps
            // the sing-box proxy transport on the underlying network, outside this TUN.
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            val include = options.includePackage
            while (include.hasNext()) builder.addAllowedApplication(include.next())
            val exclude = options.excludePackage
            while (exclude.hasNext()) builder.addDisallowedApplication(exclude.next())
        }

        val established = builder.establish() ?: error("Android refused to establish the VPN TUN")
        closeTun()
        tunInterface = established
        return established.fd
    }

    @Suppress("NewApi")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Connection owner lookup requires Android 10")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(defaultInterfaceListener)
        defaultInterfaceListener = listener
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultInterface(listener, network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateDefaultInterface(listener, network)
            }
            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                if (active != null) updateDefaultInterface(listener, active)
                else listener.updateDefaultInterface("", -1, false, false)
            }
        }
        defaultNetworkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Could not register default network callback", it) }
        connectivity.activeNetwork?.let { updateDefaultInterface(listener, it) }
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val name = connectivity.getLinkProperties(network)?.interfaceName ?: return
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        if (index >= 0) listener.updateDefaultInterface(name, index, false, false)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val callback = defaultNetworkCallback ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        defaultNetworkCallback = null
        defaultInterfaceListener = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<LibboxNetworkInterface>()
        val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        val javaInterfaces = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        for (network in networks) {
            val links = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = links.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue
            val item = LibboxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                addresses = StringArray(javaInterface.interfaceAddresses.map {
                    val ip = it.address.hostAddress.substringBefore('%')
                    "$ip/${it.networkPrefixLength}"
                }.iterator())
                dnsServer = StringArray(links.dnsServers.mapNotNull { it.hostAddress }.iterator())
                gateway = StringArray(links.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.hostAddress }.iterator())
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                flags = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                } else 0
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            result.add(item)
        }
        return NetworkArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = Unit
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = error("Platform shell is not supported")
    override fun readSystemSSHHostKey(): String = ""
    override fun lookupSFTPServer(): String = ""
    override fun tailscaleHostname(): String = "VPN RO"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("Platform bridge is not supported")
    override fun lookupUser(username: String?): PlatformUser = PlatformUser().apply {
        this.username = username ?: ""
    }
    override fun registerMyInterface(name: String?) = Unit

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class NetworkArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }
}
