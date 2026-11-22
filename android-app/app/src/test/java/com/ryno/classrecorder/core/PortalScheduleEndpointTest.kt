package com.ryno.classrecorder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalScheduleEndpointTest {
    @Test
    fun normalizesPortalAddressForSignedInSync() {
        val endpoint = PortalScheduleEndpoint.parse(" portal.example.com ")!!
        assertEquals("https://portal.example.com/api/class-schedule", endpoint.url)
        assertEquals(endpoint, PortalScheduleEndpoint.parse("https://portal.example.com:443/api/class-schedule"))
        assertNull(PortalScheduleEndpoint.parse("http://portal.example.com"))
        assertNull(PortalScheduleEndpoint.parse("https://name@portal.example.com"))
        assertNull(PortalScheduleEndpoint.parse("https://uni-campus-app.pages.dev/s/example"))
    }

    @Test
    fun onlyAcceptsTimetableDataFromTheEnteredOrigin() {
        val endpoint = PortalScheduleEndpoint.parse("https://portal.example.com:8443")!!
        assertTrue(endpoint.isTimetablePage("https://portal.example.com:8443/api/class-schedule"))
        assertFalse(endpoint.isTimetablePage("https://portal.example.com/api/class-schedule"))
        assertFalse(endpoint.isTimetablePage("https://portal.example.com.evil.example:8443/api/class-schedule"))
        assertFalse(endpoint.isTimetablePage("https://portal.example.com:8443/login"))
    }

    @Test
    fun onlyRecognizesHttpsCloudflareAccessRedirects() {
        assertEquals("team.cloudflareaccess.com", PortalScheduleEndpoint.accessHost(
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/portal.example.com"
        ))
        assertNull(PortalScheduleEndpoint.accessHost("https://team.cloudflareaccess.com.evil.example/cdn-cgi/access/login"))
        assertNull(PortalScheduleEndpoint.accessHost("http://team.cloudflareaccess.com/cdn-cgi/access/login"))
        assertNull(PortalScheduleEndpoint.accessHost("https://team.cloudflareaccess.com/somewhere-else"))
    }

    @Test
    fun recognizesConfiguredDiscordAndOsuSignInHosts() {
        val endpoint = PortalScheduleEndpoint.parse("ep.rynowastaken.me")!!
        assertEquals("discord-access-oidc.rynowastaken.workers.dev", endpoint.identityHost(
            "https://discord-access-oidc.rynowastaken.workers.dev/authorize"
        ))
        assertEquals("discord.com", endpoint.identityHost("https://discord.com/oauth2/authorize"))
        assertEquals("osu.ppy.sh", endpoint.identityHost("https://osu.ppy.sh/oauth/authorize"))
        assertNull(endpoint.identityHost("https://discord.com.evil.example/login"))
        assertNull(endpoint.identityHost("https://accounts.google.com/o/oauth2/auth"))
        assertNull(PortalScheduleEndpoint.parse("another.example.com")!!.identityHost("https://discord.com/login"))
    }
}
