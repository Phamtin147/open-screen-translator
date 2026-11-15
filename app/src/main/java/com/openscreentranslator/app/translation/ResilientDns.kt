package com.openscreentranslator.app.translation

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class DohEndpoint(
    val baseUrl: String
)

/**
 * Resolves hostnames using the OS DNS first and transparently falls back to direct-IP DNS-over-HTTPS
 * (Cloudflare 1.1.1.1 / Google 8.8.8.8, IPv4 & IPv6) when carrier/router DNS or Private DNS fails
 * with UnknownHostException ("No address associated with hostname").
 */
internal class DohDnsResolver(
    private val systemDns: Dns = Dns.SYSTEM,
    private val dohEndpoints: List<DohEndpoint> = DEFAULT_DOH_ENDPOINTS,
    private val dohClient: OkHttpClient = DEFAULT_DOH_CLIENT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = { msg ->
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Ignored inside JVM unit tests where android.util.Log is stubbed.
        }
    }
) : Dns {

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val fallbackCache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var preferredEndpoint: DohEndpoint? = null

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().trimEnd('.')
        if (cleanHost.isEmpty()) {
            throw UnknownHostException("Empty hostname")
        }

        // Fast-path numeric IP literals without touching DNS or DoH cache.
        parseNumericIpAddress(cleanHost, cleanHost)?.let { numericAddr ->
            return listOf(numericAddr)
        }

        val normalizedKey = cleanHost.lowercase()
        val now = nowMs()
        val cached = fallbackCache[normalizedKey]
        if (cached != null && now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
            return cached.addresses
        }
        if (cached != null && now >= cached.expiresAtMs) {
            fallbackCache.remove(normalizedKey, cached)
        }

        var systemError: UnknownHostException? = null
        try {
            val osAddrs = systemDns.lookup(cleanHost)
            if (osAddrs.isNotEmpty()) {
                return osAddrs
            }
        } catch (e: UnknownHostException) {
            systemError = e
        } catch (e: Exception) {
            systemError = UnknownHostException("System DNS error for $cleanHost: ${e.message}")
                .apply { initCause(e) }
        }

        logger("System DNS failed for '$cleanHost' (${systemError?.message}); falling back to DoH...")
        val dohResult = resolveViaDoh(cleanHost)
        if (dohResult != null && dohResult.addresses.isNotEmpty()) {
            val ttlMs = dohResult.minTtlSeconds.coerceIn(MIN_CACHE_TTL_SEC, MAX_CACHE_TTL_SEC) * 1000L
            fallbackCache[normalizedKey] = CacheEntry(dohResult.addresses, nowMs() + ttlMs)
            logger("DoH resolved '$cleanHost' -> ${dohResult.addresses.joinToString { it.hostAddress ?: "?" }}")
            return dohResult.addresses
        }

        val err = UnknownHostException(
            "Unable to resolve host \"$cleanHost\" via system DNS or DoH: " +
                (systemError?.message ?: "No address associated with hostname")
        )
        if (systemError != null) {
            err.initCause(systemError)
        }
        throw err
    }

    fun clearCache(hostname: String? = null) {
        if (hostname == null) {
            fallbackCache.clear()
            preferredEndpoint = null
        } else {
            fallbackCache.remove(hostname.trim().trimEnd('.').lowercase())
        }
    }

    internal data class DohResolution(
        val addresses: List<InetAddress>,
        val minTtlSeconds: Long
    )

    private fun resolveViaDoh(hostname: String): DohResolution? {
        // Prefer IPv4 (A) first since CDNs route reliably on dual-stack & 464xlat; fall back to IPv6 (AAAA).
        val desiredTypes = intArrayOf(DNS_TYPE_A, DNS_TYPE_AAAA)
        val sticky = preferredEndpoint
        val orderedEndpoints = if (sticky != null && sticky in dohEndpoints) {
            listOf(sticky) + dohEndpoints.filter { it != sticky }
        } else {
            dohEndpoints
        }
        for (recordType in desiredTypes) {
            for (endpoint in orderedEndpoints) {
                val parsed = querySingleEndpoint(endpoint, hostname, recordType)
                if (parsed != null && parsed.addresses.isNotEmpty()) {
                    preferredEndpoint = endpoint
                    return parsed
                }
            }
        }
        return null
    }

    private fun querySingleEndpoint(
        endpoint: DohEndpoint,
        hostname: String,
        recordType: Int
    ): DohResolution? {
        val typeName = if (recordType == DNS_TYPE_AAAA) "AAAA" else "A"
        val url = "${endpoint.baseUrl}?name=$hostname&type=$typeName"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        return try {
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger("DoH ${endpoint.baseUrl} returned HTTP ${response.code} for $hostname ($typeName)")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                parseDohJsonAnswers(body, hostname, recordType)
            }
        } catch (e: Exception) {
            logger("DoH query failed via ${endpoint.baseUrl} for $hostname ($typeName): ${e.message}")
            null
        }
    }

    internal fun parseDohJsonAnswers(
        json: String,
        hostname: String,
        desiredType: Int
    ): DohResolution? {
        val statusMatch = STATUS_REGEX.find(json)
        if (statusMatch != null && statusMatch.groupValues[1].toIntOrNull() != 0) {
            return null
        }

        val answerArraySlice = extractJsonArraySlice(json, "Answer") ?: return null
        val objectSlices = extractTopLevelJsonObjects(answerArraySlice)
        if (objectSlices.isEmpty()) return null

        val addresses = mutableListOf<InetAddress>()
        val seenIps = HashSet<String>()
        var minTtl = DEFAULT_CACHE_TTL_SEC

        for (obj in objectSlices) {
            val typeVal = TYPE_REGEX.find(obj)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (typeVal != desiredType) continue

            val dataIp = DATA_REGEX.find(obj)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val addr = parseNumericIpAddress(hostname, dataIp) ?: continue
            val key = addr.hostAddress ?: dataIp
            if (seenIps.add(key)) {
                addresses.add(addr)
                val ttl = TTL_REGEX.find(obj)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (ttl != null && ttl > 0L && ttl < minTtl) {
                    minTtl = ttl
                }
            }
        }

        return if (addresses.isNotEmpty()) DohResolution(addresses, minTtl) else null
    }

    companion object {
        private const val TAG = "ResilientDns"
        internal const val DNS_TYPE_A = 1
        internal const val DNS_TYPE_AAAA = 28
        private const val MIN_CACHE_TTL_SEC = 30L
        private const val DEFAULT_CACHE_TTL_SEC = 120L
        private const val MAX_CACHE_TTL_SEC = 300L

        private val STATUS_REGEX = Regex(""""Status"\s*:\s*(\d+)""")
        private val TYPE_REGEX = Regex(""""type"\s*:\s*(\d+)""")
        private val TTL_REGEX = Regex(""""TTL"\s*:\s*(\d+)""")
        private val DATA_REGEX = Regex(""""data"\s*:\s*"([^"]+)"""")

        internal val DEFAULT_DOH_ENDPOINTS = listOf(
            DohEndpoint("https://1.1.1.1/dns-query"),
            DohEndpoint("https://8.8.8.8/resolve"),
            DohEndpoint("https://1.0.0.1/dns-query"),
            DohEndpoint("https://8.8.4.4/resolve"),
            DohEndpoint("https://[2606:4700:4700::1111]/dns-query"),
            DohEndpoint("https://[2001:4860:4860::8888]/resolve")
        )

        private val NUMERIC_LITERAL_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return listOf(
                    parseNumericIpAddress(hostname, hostname)
                        ?: throw UnknownHostException("Bootstrap DoH client only allows numeric IPs: $hostname")
                )
            }
        }

        internal val DEFAULT_DOH_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .dns(NUMERIC_LITERAL_DNS)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .build()

        internal fun parseNumericIpAddress(hostname: String, literal: String): InetAddress? {
            val clean = literal.trim().removeSurrounding("[", "]")
            if (clean.isEmpty()) return null

            // IPv4 strict parse -> direct byte[4] avoids any OS/JVM DNS resolution.
            if (!clean.contains(':')) {
                val parts = clean.split('.')
                if (parts.size != 4) return null
                val bytes = ByteArray(4)
                for (i in 0 until 4) {
                    val segment = parts[i]
                    if (segment.isEmpty() || segment.length > 3 || !segment.all { it in '0'..'9' }) {
                        return null
                    }
                    val value = segment.toIntOrNull() ?: return null
                    if (value !in 0..255) return null
                    bytes[i] = value.toByte()
                }
                return InetAddress.getByAddress(hostname, bytes)
            }

            // IPv6 strict literal check (hex digits, colons, optional embedded IPv4 dots, optional scope).
            val withoutZone = clean.substringBefore('%')
            if (withoutZone.isEmpty() || !withoutZone.contains(':')) return null
            if (!withoutZone.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return null
            }
            return try {
                val raw = InetAddress.getByName(withoutZone).address
                InetAddress.getByAddress(hostname, raw)
            } catch (_: Exception) {
                null
            }
        }

        private fun extractJsonArraySlice(json: String, key: String): String? {
            val pattern = Regex(""""$key"\s*:\s*\[""")
            val match = pattern.find(json) ?: return null
            val openBracketIdx = match.range.last
            var depth = 0
            var inString = false
            var escaped = false

            for (i in openBracketIdx until json.length) {
                val ch = json[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString) {
                    if (ch == '\\') escaped = true
                    else if (ch == '"') inString = false
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return json.substring(openBracketIdx, i + 1)
                        }
                    }
                }
            }
            return null
        }

        private fun extractTopLevelJsonObjects(arraySlice: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = -1
            var inString = false
            var escaped = false

            for (i in arraySlice.indices) {
                val ch = arraySlice[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString) {
                    if (ch == '\\') escaped = true
                    else if (ch == '"') inString = false
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            result.add(arraySlice.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return result
        }
    }
}

object ResilientDns : Dns {
    private val delegate = DohDnsResolver()

    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname)

    fun clearCache(hostname: String? = null) {
        delegate.clearCache(hostname)
    }
}
