package com.friends.privatevpn

import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Thin adapter around the generated libbox Java API shipped in libbox.aar. */
internal class LibboxRuntime(private val service: AndroidVpnService) {
    private var commandServer: Any? = null
    private var tun: ParcelFileDescriptor? = null

    fun start(config: String) {
        check(commandServer == null) { "sing-box is already running" }
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
        // libbox.setup(String) prepares its private working directory before the command server starts.
        runCatching { libbox.getMethod("setup", String::class.java).invoke(null, service.filesDir.absolutePath) }
            .recoverCatching {
                // Some generated builds expose setup with an options object; the command server will
                // return the authoritative error if this build does not require setup.
            }

        val platformClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val handlerClass = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        val platform = Proxy.newProxyInstance(platformClass.classLoader, arrayOf(platformClass), PlatformHandler())
        val handler = Proxy.newProxyInstance(handlerClass.classLoader, arrayOf(handlerClass), Handler())
        val serverClass = Class.forName("io.nekohasekai.libbox.CommandServer")
        val constructor = serverClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: error("libbox CommandServer constructor not found")
        val server = constructor.newInstance(handler, platform)
        serverClass.getMethod("start").invoke(server)
        val overrideClass = Class.forName("io.nekohasekai.libbox.OverrideOptions")
        val overrides = overrideClass.getDeclaredConstructor().newInstance()
        try {
            serverClass.methods.first { it.name == "startOrReloadService" && it.parameterTypes.size == 2 }
                .invoke(server, config, overrides)
        } catch (e: Throwable) {
            runCatching { serverClass.getMethod("close").invoke(server) }
            throw (e.cause ?: e)
        }
        commandServer = server
    }

    fun stop() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.javaClass.getMethod("closeService").invoke(server) }
            runCatching { server.javaClass.getMethod("close").invoke(server) }
        }
        runCatching { tun?.close() }
        tun = null
    }

    private inner class Handler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "serviceStop" -> { stop(); null }
                "serviceReload" -> null
                "triggerNativeCrash" -> null
                "writeDebugMessage" -> { Log.d("VPN_RO", args?.firstOrNull()?.toString().orEmpty()); null }
                "connectSSHAgent" -> -1
                "getSystemProxyStatus" -> null
                "setSystemProxyEnabled" -> null
                "toString" -> "VPN_RO_CommandServerHandler"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inner class PlatformHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt()
                    if (fd != null) service.protect(fd)
                    null
                }
                "openTun" -> openTun(args?.firstOrNull() ?: error("TunOptions missing"))
                "useProcFS", "underNetworkExtension", "includeAllNetworks", "usePlatformShell", "usePlatformBridge" -> false
                "usePlatformDefaultInterfaceMonitor" -> false
                "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor", "clearDNSCache", "registerMyInterface", "startNeighborMonitor", "closeNeighborMonitor" -> null
                "getInterfaces" -> emptyIteratorFor(method.returnType)
                "readWIFIState", "localDNSTransport", "findConnectionOwner", "lookupUser", "openShellSession", "createBridge" -> null
                "toString" -> "VPN_RO_PlatformInterface"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun openTun(options: Any): Int {
        val builder = service.Builder().setSession("VPN RO")
        val mtu = readNumber(options, "getMtu", 1500)
        builder.setMtu(mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        // sing-box supplies address and route iterators in TunOptions. The generated prefix objects
        // expose address()/prefix() in the official 1.14.x libbox bindings.
        addPrefixes(builder, options, "getInet4Address", false)
        addPrefixes(builder, options, "getInet6Address", false)
        addStrings(builder, options, "getDnsServerAddress")
        addPrefixes(builder, options, "getInet4RouteRange", true)
        addPrefixes(builder, options, "getInet6RouteRange", true)
        addPrefixes(builder, options, "getInet4RouteAddress", true)
        addPrefixes(builder, options, "getInet6RouteAddress", true)
        val pfd = builder.establish() ?: error("Android refused to establish VPN interface")
        tun?.close()
        tun = pfd
        return pfd.fd
    }

    private fun readNumber(target: Any, method: String, fallback: Int): Int =
        runCatching { (target.javaClass.getMethod(method).invoke(target) as Number).toInt() }.getOrDefault(fallback)

    private fun iterator(target: Any, getter: String): List<Any> {
        val value = runCatching { target.javaClass.getMethod(getter).invoke(target) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Any>()
        val hasNext = value.javaClass.getMethod("hasNext")
        val next = value.javaClass.getMethod("next")
        while (hasNext.invoke(value) as Boolean) result += next.invoke(value)
        return result
    }

    private fun addPrefixes(builder: AndroidVpnService.Builder, options: Any, getter: String, route: Boolean) {
        for (prefix in iterator(options, getter)) {
            val address = prefix.javaClass.getMethod("address").invoke(prefix) as String
            val bits = (prefix.javaClass.getMethod("prefix").invoke(prefix) as Number).toInt()
            if (route) builder.addRoute(address, bits) else builder.addAddress(address, bits)
        }
    }

    private fun addStrings(builder: AndroidVpnService.Builder, options: Any, getter: String) {
        for (entry in iterator(options, getter)) {
            val dns = entry.toString()
            runCatching { builder.addDnsServer(dns) }
        }
    }

    private fun emptyIteratorFor(returnType: Class<*>): Any? = null

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Void.TYPE -> null
        else -> null
    }
}
