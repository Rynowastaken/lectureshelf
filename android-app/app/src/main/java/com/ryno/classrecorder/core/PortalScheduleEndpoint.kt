package com.ryno.classrecorder.core

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

@ConsistentCopyVisibility
internal data class PortalScheduleEndpoint private constructor(
    val host: String,
    val port: Int
) {
    val origin: String = URI("https", null, host, port, null, null, null).toString()
    val url: String = "$origin$TIMETABLE_PATH"

    fun isPortalPage(url: String): Boolean = httpsUri(url)?.let {
        it.host.equals(host, ignoreCase = true) && canonicalPort(it.port) == port
    } == true

    fun isTimetablePage(url: String): Boolean =
        isPortalPage(url) && httpsUri(url)?.rawPath == TIMETABLE_PATH

    private val identityHosts: Set<String>
        get() = if (host == "ep.rynowastaken.me") PERSONAL_PORTAL_IDENTITY_HOSTS else emptySet()

    fun identityHost(url: String): String? {
        val uri = httpsUri(url) ?: return null
        return uri.host.lowercase(Locale.ROOT).takeIf {
            canonicalPort(uri.port) == -1 && it in identityHosts
        }
    }

    val signInMethods: String
        get() = if (identityHosts.isEmpty()) "Use the email code on Cloudflare Access to sign in."
        else "Choose Discord, osu!, or an email code to sign in here."

    companion object {
        const val TIMETABLE_PATH = "/api/class-schedule"
        private val PERSONAL_PORTAL_IDENTITY_HOSTS = setOf(
            "discord-access-oidc.rynowastaken.workers.dev",
            "osu-access-oidc.rynowastaken.workers.dev",
            "discord.com",
            "osu.ppy.sh"
        )

        fun parse(input: String): PortalScheduleEndpoint? {
            val trimmed = input.trim()
            val candidate = if ("://" in trimmed) trimmed else {
                if ('.' !in trimmed.substringBefore('/')) return null
                "https://$trimmed"
            }
            val uri = httpsUri(candidate) ?: return null
            if (uri.rawPath !in listOf("", "/", TIMETABLE_PATH)) return null
            return PortalScheduleEndpoint(uri.host.lowercase(Locale.ROOT), canonicalPort(uri.port))
        }

        fun accessHost(url: String): String? {
            val uri = httpsUri(url) ?: return null
            val host = uri.host.lowercase(Locale.ROOT)
            return host.takeIf {
                canonicalPort(uri.port) == -1 && it.endsWith(".cloudflareaccess.com") &&
                    uri.rawPath.startsWith("/cdn-cgi/access/")
            }
        }

        private fun canonicalPort(port: Int): Int = if (port == 443) -1 else port

        private fun httpsUri(url: String): URI? {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
            return uri.takeIf {
                it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null &&
                    (it.port == -1 || it.port in 1..65535)
            }
        }
    }
}
