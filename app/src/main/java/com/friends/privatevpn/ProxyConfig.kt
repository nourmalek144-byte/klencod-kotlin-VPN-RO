package com.friends.privatevpn

import org.json.JSONArray
import org.json.JSONObject

/** Builds a sing-box configuration for an authenticated HTTP proxy. */
internal object ProxyConfig {
    fun build(host: String, port: Int, username: String, password: String): String {
        val outbound = JSONObject().apply {
            put("type", "http")
            put("tag", "proxy-out")
            put("server", host)
            put("server_port", port)
            put("username", username)
            put("password", password)
        }
        val tun = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            put("mtu", 1500)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "system")
        }
        val direct = JSONObject().put("type", "direct").put("tag", "direct")
        val route = JSONObject().apply {
            put("auto_detect_interface", true)
            put("final", "proxy-out")
        }
        return JSONObject().apply {
            put("log", JSONObject().put("level", "info"))
            put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("tag", "dns-remote").put("address", "1.1.1.1").put("detour", "proxy-out"))).put("final", "dns-remote"))
            put("inbounds", JSONArray().put(tun))
            put("outbounds", JSONArray().put(outbound).put(direct))
            put("route", route)
        }.toString()
    }
}
