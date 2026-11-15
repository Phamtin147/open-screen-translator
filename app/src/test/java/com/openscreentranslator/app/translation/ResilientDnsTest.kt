package com.openscreentranslator.app.translation

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {

    private fun fakeClient(responder: (url: String) -> Pair<Int, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val (code, json) = responder(req.url.toString())
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(json.toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun returnsNumericIpLiteralsWithoutCallingSystemOrDoh() {
        var systemCalls = 0
        var dohCalls = 0
        val resolver = DohDnsResolver(
            systemDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    systemCalls++
                    throw AssertionError("systemDns should not be called for numeric IPs")
                }
            },
            dohEndpoints = listOf(DohEndpoint("https://1.1.1.1/dns-query")),
            dohClient = fakeClient {
                dohCalls++
                200 to "{}"
            }
        )

        val v4 = resolver.lookup("54.169.134.11")
        assertEquals(1, v4.size)
        assertTrue(v4[0] is Inet4Address)
        assertEquals("54.169.134.11", v4[0].hostAddress)

        val v6 = resolver.lookup("2606:4700:4700::1111")
        assertEquals(1, v6.size)
        assertTrue(v6[0] is Inet6Address)
        assertEquals(0, systemCalls)
        assertEquals(0, dohCalls)
    }

    @Test
    fun usesSystemDnsWhenAvailableWithoutCallingDoh() {
        var dohCalls = 0
        val expected = listOf(InetAddress.getByAddress("huggingface.co", byteArrayOf(13, 35, 202.toByte(), 97)))
        val resolver = DohDnsResolver(
            systemDns = object : Dns {
                override fun lookup(hostname: String) = expected
            },
            dohEndpoints = listOf(DohEndpoint("https://1.1.1.1/dns-query")),
            dohClient = fakeClient {
                dohCalls++
                200 to "{}"
            }
        )

        val result = resolver.lookup("huggingface.co")
        assertEquals(expected, result)
        assertEquals(0, dohCalls)
    }

    @Test
    fun fallsBackToDohAndSkipsCnameWhenSystemDnsThrowsNoAddress() {
        var clockMs = 1_000_000L
        var systemCalls = 0
        val dohUrlsCalled = mutableListOf<String>()
        val client = fakeClient { url ->
            dohUrlsCalled += url
            200 to """
                {
                  "Status": 0,
                  "Answer": [
                    {"name": "huggingface.co", "type": 5, "TTL": 300, "data": "d3.cloudfront.net."},
                    {"name": "d3.cloudfront.net", "type": 1, "TTL": 45, "data": "54.169.134.11"},
                    {"name": "d3.cloudfront.net", "type": 1, "TTL": 90, "data": "18.136.219.244"}
                  ]
                }
            """.trimIndent()
        }

        val resolver = DohDnsResolver(
            systemDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    systemCalls++
                    throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
                }
            },
            dohEndpoints = listOf(
                DohEndpoint("https://1.1.1.1/dns-query"),
                DohEndpoint("https://8.8.8.8/resolve")
            ),
            dohClient = client,
            nowMs = { clockMs }
        )

        val addrs = resolver.lookup("us.aws.cdn.hf.co")
        assertEquals(listOf("54.169.134.11", "18.136.219.244"), addrs.map { it.hostAddress })
        assertEquals("us.aws.cdn.hf.co", addrs.first().hostName)
        assertEquals(1, systemCalls)
        assertEquals(1, dohUrlsCalled.size)
        assertTrue(dohUrlsCalled[0].startsWith("https://1.1.1.1/dns-query?name=us.aws.cdn.hf.co&type=A"))

        // Second lookup within TTL (45s) hits DoH cache immediately without repeating failed system lookup.
        clockMs += 10_000L
        val cached = resolver.lookup("us.aws.cdn.hf.co")
        assertEquals(addrs, cached)
        assertEquals(1, systemCalls)
        assertEquals(1, dohUrlsCalled.size)

        // After TTL expires (or cache cleared), it queries again cleanly.
        clockMs += 50_000L
        resolver.lookup("us.aws.cdn.hf.co")
        assertEquals(2, systemCalls)
        assertEquals(2, dohUrlsCalled.size)
    }

    @Test
    fun failsOverAcrossDohProvidersAndSupportsIpv6AaaaFallback() {
        val queriedUrls = mutableListOf<String>()
        val client = fakeClient { url ->
            queriedUrls += url
            when {
                url.contains("1.1.1.1") -> 503 to "Service Unavailable"
                url.contains("type=A") && !url.contains("type=AAAA") -> 200 to """{"Status": 0, "Answer": []}"""
                url.contains("8.8.8.8") && url.contains("type=AAAA") -> 200 to """
                    {
                      "Status": 0,
                      "Answer": [
                        {"name": "v6only.example", "type": 28, "TTL": 60, "data": "2606:4700:4700::1111"}
                      ]
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        val resolver = DohDnsResolver(
            systemDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    throw UnknownHostException("No address associated with hostname")
                }
            },
            dohEndpoints = listOf(
                DohEndpoint("https://1.1.1.1/dns-query"),
                DohEndpoint("https://8.8.8.8/resolve")
            ),
            dohClient = client
        )

        val addrs = resolver.lookup("v6only.example")
        assertEquals(1, addrs.size)
        assertTrue(addrs[0] is Inet6Address)
        assertEquals("2606:4700:4700:0:0:0:0:1111", addrs[0].hostAddress)
        assertEquals(4, queriedUrls.size) // A on 1.1.1.1, A on 8.8.8.8, AAAA on 1.1.1.1, AAAA on 8.8.8.8
    }

    @Test
    fun throwsUnknownHostExceptionWhenBothSystemAndDohFail() {
        val resolver = DohDnsResolver(
            systemDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    throw UnknownHostException("No address associated with hostname")
                }
            },
            dohEndpoints = listOf(DohEndpoint("https://1.1.1.1/dns-query")),
            dohClient = fakeClient { 200 to """{"Status": 3}""" }
        )

        try {
            resolver.lookup("nonexistent.invalid")
            fail("Expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertTrue(expected.message.orEmpty().contains("nonexistent.invalid"))
        }
    }
}
