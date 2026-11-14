package com.friends.privatevpn

import org.json.JSONArray
import org.json.JSONObject

/** Builds a sing-box 1.14.0 configuration for an authenticated HTTP CONNECT proxy. */
internal object ProxyConfig {
    fun build(host: String, port: Int, username: String, password: String): String {
        require(host.isNotBlank()) { "Proxy host is empty" }
        require(port in 1..65535) { "Proxy port is invalid" }
        require(username.isNotBlank()) { "Proxy username is empty" }
        require(password.isNotBlank()) { "Proxy password is empty" }

        val proxyOutbound = JSONObject().apply {
            put("type", "http")
            put("tag", "proxy-out")
            put("server", host)
            put("server_port", port)
            put("username", username)
            put("password", password)
        }

        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            put("mtu", 1400)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "system")
        }

        // DoH is used so DNS also travels as TCP through the authenticated HTTP proxy.
        val dnsServer = JSONObject().apply {
            put("type", "https")
            put("tag", "dns-remote")
            put("server", "1.1.1.1")
            put("server_port", 443)
            put("path", "/dns-query")
            put("detour", "proxy-out")
            put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
        }

        return JSONObject().apply {
            put("log", JSONObject().put("level", "info").put("timestamp", true))
            put("dns", JSONObject()
                .put("servers", JSONArray().put(dnsServer))
                .put("final", "dns-remote"))
            put("inbounds", JSONArray().put(tunInbound))
            put("outbounds", JSONArray().put(proxyOutbound))
            put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "proxy-out"))
        }.toString()
    }
}
