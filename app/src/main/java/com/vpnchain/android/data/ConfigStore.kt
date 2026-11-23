package com.vpnchain.android.data

import android.content.Context
import com.vpnchain.android.model.*
import org.json.JSONArray
import org.json.JSONObject

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_chain_prefs", Context.MODE_PRIVATE)

    fun load(): ChainConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return ChainConfig()
        return try {
            val o = JSONObject(raw)
            val rulesJson = o.optJSONArray("excludeRules")
            val rules = mutableListOf<ExcludeRule>()
            if (rulesJson != null) {
                for (i in 0 until rulesJson.length()) {
                    val r = rulesJson.getJSONObject(i)
                    rules.add(
                        ExcludeRule(
                            id = r.optString("id", java.util.UUID.randomUUID().toString()),
                            enabled = r.optBoolean("enabled", true),
                            type = ExcludeType.fromId(r.optInt("type", 0)),
                            target = r.optString("target", ""),
                            displayName = r.optString("displayName", ""),
                            route = ExcludeRoute.fromId(r.optInt("route", 1))
                        )
                    )
                }
            }
            ChainConfig(
                pptpServer = o.optString("pptpServer", ""),
                pptpUsername = o.optString("pptpUsername", ""),
                pptpPassword = o.optString("pptpPassword", ""),
                authMode = AuthMode.fromId(o.optString("authMode", "mschapv2")),
                requireMppe = o.optBoolean("requireMppe", true),
                statelessMppe = o.optBoolean("statelessMppe", true),
                pptpMtu = o.optInt("pptpMtu", 1380),
                secondVpnPackage = o.optString("secondVpnPackage", ""),
                secondVpnLabel = o.optString("secondVpnLabel", ""),
                proxyHost = o.optString("proxyHost", "127.0.0.1"),
                proxyPort = o.optInt("proxyPort", 10810),
                proxyProtocol = ProxyProtocol.fromId(o.optString("proxyProtocol", "auto")),
                waitForProxySeconds = o.optInt("waitForProxySeconds", 90),
                tunMtu = o.optInt("tunMtu", 1280),
                dnsServer = o.optString("dnsServer", "1.1.1.1"),
                routePublicDnsViaPptp = o.optBoolean("routePublicDnsViaPptp", true),
                excludeRules = rules
            )
        } catch (_: Exception) {
            ChainConfig()
        }
    }

    fun save(cfg: ChainConfig) {
        val o = JSONObject().apply {
            put("pptpServer", cfg.pptpServer)
            put("pptpUsername", cfg.pptpUsername)
            put("pptpPassword", cfg.pptpPassword)
            put("authMode", cfg.authMode.id)
            put("requireMppe", cfg.requireMppe)
            put("statelessMppe", cfg.statelessMppe)
            put("pptpMtu", cfg.pptpMtu)
            put("secondVpnPackage", cfg.secondVpnPackage)
            put("secondVpnLabel", cfg.secondVpnLabel)
            put("proxyHost", cfg.proxyHost)
            put("proxyPort", cfg.proxyPort)
            put("proxyProtocol", cfg.proxyProtocol.id)
            put("waitForProxySeconds", cfg.waitForProxySeconds)
            put("tunMtu", cfg.tunMtu)
            put("dnsServer", cfg.dnsServer)
            put("routePublicDnsViaPptp", cfg.routePublicDnsViaPptp)

            val arr = JSONArray()
            cfg.excludeRules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("enabled", r.enabled)
                    put("type", r.type.id)
                    put("target", r.target)
                    put("displayName", r.displayName)
                    put("route", r.route.id)
                })
            }
            put("excludeRules", arr)
        }
        prefs.edit().putString(KEY_CONFIG, o.toString()).apply()
    }

    companion object {
        private const val KEY_CONFIG = "chain_config_v1"
    }
}
