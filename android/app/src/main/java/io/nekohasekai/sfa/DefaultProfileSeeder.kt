package io.nekohasekai.sfa

import android.content.Context
import android.util.Base64
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

sealed class SeedState {
    object Idle : SeedState()
    object Loading : SeedState()
    object Ready : SeedState()
    data class Failed(val reason: String) : SeedState()
}

object DefaultProfileSeeder {
    const val PROFILE_NAME = "hjply"

    private val _seedState = MutableStateFlow<SeedState>(SeedState.Idle)
    val seedState: StateFlow<SeedState> = _seedState.asStateFlow()

    suspend fun seedIfNeeded(context: Context) {
        _seedState.value = SeedState.Loading
        try {
            val subscriptionURL = BuildConfig.HJPLY_SUBSCRIPTION_URL.trim()
            require(subscriptionURL.isNotBlank()) { "hjply subscription URL is missing" }
            val uri = URI(subscriptionURL)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "hjply subscription URL must use HTTPS"
            }

            val content = downloadConfig(subscriptionURL)

            val existing = ProfileManager.list().firstOrNull { it.name == PROFILE_NAME }
            if (existing != null) {
                File(existing.typed.path).writeText(content, Charsets.UTF_8)
                existing.typed.type = TypedProfile.Type.Remote
                existing.typed.remoteURL = subscriptionURL
                existing.typed.autoUpdate = true
                existing.typed.autoUpdateInterval = 15
                existing.typed.lastUpdated = Date()
                ProfileManager.update(existing)
                Settings.selectedProfile = existing.id
                _seedState.value = SeedState.Ready
                return
            }

            val typedProfile =
                TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = subscriptionURL
                    autoUpdate = true
                    autoUpdateInterval = 15
                    lastUpdated = Date()
                }
            val profile =
                Profile(name = PROFILE_NAME, typed = typedProfile).apply {
                    userOrder = ProfileManager.nextOrder()
                }
            val fileID = ProfileManager.nextFileID()
            val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
            val configFile = File(configDirectory, "$fileID.json")
            configFile.writeText(content, Charsets.UTF_8)
            typedProfile.path = configFile.path
            ProfileManager.create(profile, andSelect = true)
            _seedState.value = SeedState.Ready
        } catch (e: Exception) {
            _seedState.value = SeedState.Failed(e.message ?: e::class.java.simpleName)
            throw e
        }
    }

    suspend fun downloadConfig(subscriptionURL: String): String {
        val encoded = HTTPClient().use { it.getString(subscriptionURL) }
        val links = decodeLinks(encoded)
        val config = buildConfig(links)
        Libbox.checkConfig(config)
        return config
    }

    private fun decodeLinks(encoded: String): List<VlessNode> {
        val decoded = runCatching {
            String(Base64.decode(encoded.trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Subscription is not Base64 VLESS content", it) }
        val nodes = decoded.lineSequence().mapNotNull { parseVless(it.trim()) }.toList()
        require(nodes.isNotEmpty()) { "Subscription contains no supported VLESS nodes" }
        return nodes
    }

    private fun parseVless(link: String): VlessNode? {
        if (!link.startsWith("vless://", ignoreCase = true)) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val query = uri.rawQuery.orEmpty().split("&").mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 1) null else {
                URLDecoder.decode(pair.substring(0, separator), "UTF-8").lowercase() to
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }
        }.toMap()
        val uuid = uri.userInfo.orEmpty().lowercase()
        val host = uri.host.orEmpty()
        val port = uri.port
        if (!UUID_RE.matches(uuid) || host.isBlank() || port !in 1..65535) return null
        if (!query["security"].equals("tls", ignoreCase = true) || !query["type"].equals("ws", ignoreCase = true)) return null
        val sni = query["sni"].orEmpty().ifBlank { host }
        val wsHost = query["host"].orEmpty().ifBlank { sni }
        val path = query["path"].orEmpty().ifBlank { "/" }
        return VlessNode(host, port, uuid, sni, wsHost, path)
    }

    private fun buildConfig(nodes: List<VlessNode>): String {
        val outbounds = JSONArray()
        nodes.forEachIndexed { index, node ->
            outbounds.put(JSONObject().apply {
                put("type", "vless")
                put("tag", "node-${index + 1}")
                put("server", node.server)
                put("server_port", node.port)
                put("uuid", node.uuid)
                // Resolve the VPN hostname outside the tunnel. Otherwise the
                // initial outbound connection can depend on its own DNS route.
                put("domain_resolver", JSONObject().apply {
                    put("server", "bootstrap")
                    put("strategy", "ipv4_only")
                })
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", node.sni)
                    put("utls", JSONObject().apply { put("enabled", true); put("fingerprint", "chrome") })
                })
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", node.path)
                    put("headers", JSONObject().apply { put("Host", node.wsHost) })
                })
            })
        }
        val firstTag = "node-1"
        outbounds.put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
        return JSONObject().apply {
            put("log", JSONObject().apply { put("level", "debug"); put("timestamp", true) })
            put("dns", JSONObject().apply {
                put("servers", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "local")
                        put("tag", "bootstrap")
                    })
                    .put(JSONObject().apply {
                        put("type", "https")
                        put("tag", "remote-dns")
                        put("server", "8.8.8.8")
                        put("server_port", 443)
                        put("path", "/dns-query")
                        put("tls", JSONObject().apply {
                            put("enabled", true)
                            put("server_name", "dns.google")
                        })
                        put("detour", firstTag)
                    }))
                put("final", "remote-dns")
                put("strategy", "prefer_ipv4")
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "tun"); put("tag", "tun-in"); put("address", JSONArray().put("172.19.0.1/30"))
                put("auto_route", true); put("strict_route", true); put("stack", "mixed"); put("mtu", 1400)
            }))
            put("outbounds", outbounds)
            put("route", JSONObject().apply {
                put("rules", JSONArray().put(JSONObject().apply { put("action", "sniff") }).put(
                    JSONObject().apply { put("protocol", "dns"); put("action", "hijack-dns") },
                ))
                put("final", firstTag)
                put("auto_detect_interface", true)
            })
            put("experimental", JSONObject().apply {
                put("cache_file", JSONObject().apply { put("enabled", true) })
            })
        }.toString()
    }

    private data class VlessNode(
        val server: String,
        val port: Int,
        val uuid: String,
        val sni: String,
        val wsHost: String,
        val path: String,
    )

    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
}
