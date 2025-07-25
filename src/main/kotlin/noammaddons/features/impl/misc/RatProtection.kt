package noammaddons.features.impl.misc

import noammaddons.features.Feature
import noammaddons.ui.config.core.annotations.Dev
import noammaddons.ui.config.core.impl.ToggleSetting
import noammaddons.utils.ChatUtils.modMessage
import noammaddons.utils.ThreadUtils.loop
import noammaddons.utils.Utils.remove
import noammaddons.utils.WebUtils
import java.io.IOException
import java.net.*
import java.util.*


@Dev
object RatProtection: Feature() {
    private val blockEndPoint by ToggleSetting("Block Mojang Endpoint")
    private val blockSusConnections by ToggleSetting("Block Suspicious Connections")
    private lateinit var proxySelector: ProxySelector

    private val suspiciousEndpoints = setOf(
        "api.github.com/gists",
        "api.sadcolors.gay",
        "postman-echo.com",
        "pastebin.com/api",
        "requestbin.com",
        "discord.com/api/webhooks",
        "hastebin.com",
        "paste.ee/api",
        "gooning.shop",
        "heroku",
        "onrender",
        "vercel",
        "guilded",
        "cloud-xip.com",
        "pythonanywhere",
        "heroku",
        "onrender",
        "vercel",
        "minecraft-api",
        "ip-api",
        "api-minecraft",
        "checkip.amazonaws.com",
        "api.ipify",
        "ipapi",
        "discordapp.com",
        "link.storjshare.io",
        "jojodiealtekah",
        "drive.usercontent.google",
        "drive.google"
    )

    override fun init() = loop(25) {
        if (! enabled) return@loop
        if (! blockEndPoint) return@loop
        if (mc.theWorld == null) return@loop
        if (mc.session == null) return@loop

        WebUtils.sendPostRequest(
            "https://sessionserver.mojang.com/session/minecraft/join",
            mapOf(
                "accessToken" to mc.session.token,
                "selectedProfile" to mc.session.playerID.remove("-"),
                "serverId" to UUID.randomUUID().toString().remove("-")
            )
        )
    }

    fun install() {
        val default = ProxySelector.getDefault()

        proxySelector = object: ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val url = uri.toString()
                if (enabled && blockSusConnections && isSuspicious(url)) {
                    modMessage("Rat Protection >> &c&lSuspicious request: &r&c$url")
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", 0)))
                }
                return default?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                default?.connectFailed(uri, sa, ioe)
            }

            private fun isSuspicious(url: String): Boolean {
                return suspiciousEndpoints.any { url.contains(it, ignoreCase = true) }
            }
        }

        ProxySelector.setDefault(proxySelector)
        lockSelector()
    }

    private fun lockSelector() = loop(5000) {
        if (ProxySelector.getDefault() == proxySelector) return@loop
        ProxySelector.setDefault(proxySelector)
    }
}