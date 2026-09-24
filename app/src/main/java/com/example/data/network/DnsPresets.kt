package com.example.data.network

import okhttp3.Dns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class DnsPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Empty = system resolver */
    val servers: List<String>
)

object DnsPresets {
    val ALL = listOf(
        DnsPreset("google", "Google", "8.8.8.8 · 8.8.4.4", listOf("8.8.8.8", "8.8.4.4")),
        DnsPreset("system", "System", "Phone default DNS", emptyList()),
        DnsPreset("cloudflare", "Cloudflare", "1.1.1.1 · 1.0.0.1", listOf("1.1.1.1", "1.0.0.1")),
        DnsPreset("quad9", "Quad9", "9.9.9.9 · 149.112.112.112", listOf("9.9.9.9", "149.112.112.112")),
        DnsPreset("opendns", "OpenDNS", "208.67.222.222 · 208.67.220.220", listOf("208.67.222.222", "208.67.220.220")),
        DnsPreset("adguard", "AdGuard", "94.140.14.14 · 94.140.15.15", listOf("94.140.14.14", "94.140.15.15")),
        DnsPreset("shecan", "Shecan", "178.22.122.100 · 185.51.200.2", listOf("178.22.122.100", "185.51.200.2")),
        DnsPreset("electro", "Electro", "78.157.42.100 · 78.157.42.101", listOf("78.157.42.100", "78.157.42.101")),
        DnsPreset("begzar", "Begzar", "185.55.226.26 · 185.55.225.25", listOf("185.55.226.26", "185.55.225.25")),
        DnsPreset("custom", "Custom", "Your own DNS servers", emptyList())
    )

    fun byId(id: String): DnsPreset = ALL.find { it.id == id } ?: ALL.first()

    fun resolveOkHttpDns(presetId: String, customPrimary: String, customSecondary: String): Dns {
        val preset = byId(presetId)
        val servers = when (preset.id) {
            "system" -> return defaultResilientDns()
            "custom" -> listOfNotNull(
                customPrimary.trim().takeIf { it.isNotEmpty() },
                customSecondary.trim().takeIf { it.isNotEmpty() }
            )
            else -> preset.servers
        }
        if (servers.isEmpty()) return defaultResilientDns()
        return UdpDns(servers)
    }

    fun defaultResilientDns(): Dns = ResilientDns()
}

/**
 * Minimal UDP DNS (A records) against fixed recursive servers.
 * Falls back to [Dns.SYSTEM] if all custom servers fail.
 */
class UdpDns(
    private val serverIps: List<String>,
    private val timeoutMs: Int = 2_500
) : Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val cacheTtlMs = 60_000L

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("empty hostname")

        parseLiteralIp(hostname)?.let { return listOf(it) }

        val key = hostname.lowercase()
        cache[key]?.let { (at, addrs) ->
            if (System.currentTimeMillis() - at < cacheTtlMs && addrs.isNotEmpty()) return addrs
        }

        var lastError: Exception? = null
        for (server in serverIps) {
            try {
                val addrs = queryA(hostname, InetAddress.getByName(server))
                if (addrs.isNotEmpty()) {
                    cache[key] = System.currentTimeMillis() to addrs
                    return addrs
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return try {
            Dns.SYSTEM.lookup(hostname).also {
                if (it.isNotEmpty()) cache[key] = System.currentTimeMillis() to it
            }
        } catch (e: Exception) {
            val err = UnknownHostException(
                "Unable to resolve $hostname via ${serverIps.joinToString()}"
            )
            err.initCause(lastError ?: e)
            throw err
        }
    }

    private fun parseLiteralIp(host: String): InetAddress? {
        return try {
            // Reject hostnames; only pure literals
            if (host.any { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) {
                // might still be IPv6 hex — allow ':' or all digits/dots
                if (':' !in host && host.any { it.isLetter() }) return null
            }
            if (host.contains('.') && host.all { it.isDigit() || it == '.' }) {
                InetAddress.getByName(host)
            } else if (host.contains(':')) {
                InetAddress.getByName(host)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryA(hostname: String, server: InetAddress): List<InetAddress> {
        val query = buildQuery(hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(query, query.size, server, 53))
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return parseResponse(buf, packet.length)
        }
    }

    private fun buildQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        val id = (System.nanoTime() and 0xFFFF).toInt()
        data.writeShort(id)
        data.writeShort(0x0100) // standard query, recursion desired
        data.writeShort(1) // QDCOUNT
        data.writeShort(0)
        data.writeShort(0)
        data.writeShort(0)
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            data.writeByte(bytes.size)
            data.write(bytes)
        }
        data.writeByte(0)
        data.writeShort(1) // A
        data.writeShort(1) // IN
        data.flush()
        return out.toByteArray()
    }

    private fun parseResponse(buf: ByteArray, length: Int): List<InetAddress> {
        val input = DataInputStream(ByteArrayInputStream(buf, 0, length))
        input.readUnsignedShort() // id
        val flags = input.readUnsignedShort()
        if (flags and 0x000F != 0) return emptyList() // RCODE
        val qd = input.readUnsignedShort()
        val an = input.readUnsignedShort()
        input.readUnsignedShort()
        input.readUnsignedShort()
        repeat(qd) {
            skipName(input, buf)
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
        val results = ArrayList<InetAddress>(an)
        repeat(an) {
            skipName(input, buf)
            val type = input.readUnsignedShort()
            input.readUnsignedShort() // class
            input.readInt() // ttl
            val rdLength = input.readUnsignedShort()
            val rdata = ByteArray(rdLength)
            input.readFully(rdata)
            if (type == 1 && rdLength == 4) {
                results += InetAddress.getByAddress(rdata)
            }
        }
        return results
    }

    private fun skipName(input: DataInputStream, buf: ByteArray) {
        while (true) {
            val len = input.readUnsignedByte()
            if (len == 0) return
            if (len and 0xC0 == 0xC0) {
                input.readUnsignedByte() // pointer low
                return
            }
            input.skipBytes(len)
        }
    }
}

class ResilientDns(
    fallbackServers: List<String> = listOf("8.8.8.8", "1.1.1.1")
) : Dns {
    private val udpDns = UdpDns(fallbackServers)

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val sysAddrs = Dns.SYSTEM.lookup(hostname)
            val isFiltered = sysAddrs.any { addr ->
                val ip = addr.hostAddress ?: ""
                ip.startsWith("10.10.") || ip.startsWith("10.202.") || ip == "127.0.0.1" || ip == "0.0.0.0"
            }
            if (sysAddrs.isNotEmpty() && !isFiltered) {
                return sysAddrs
            }
        } catch (_: Exception) {
            // System DNS failed, fallback to direct DNS
        }

        try {
            val addrs = udpDns.lookup(hostname)
            if (addrs.isNotEmpty()) {
                val isFiltered = addrs.any { addr ->
                    val ip = addr.hostAddress ?: ""
                    ip.startsWith("10.10.") || ip.startsWith("10.202.")
                }
                if (!isFiltered) return addrs
            }
        } catch (_: Exception) {
            // UDP DNS failed
        }

        // Hardcoded Cloudflare Anycast fallback for worker domain when all DNS resolution is poisoned/offline
        if (hostname.contains("workers.dev")) {
            val fallbacks = listOfNotNull(
                try { InetAddress.getByName("104.21.16.156") } catch (_: Exception) { null },
                try { InetAddress.getByName("172.67.213.172") } catch (_: Exception) { null }
            )
            if (fallbacks.isNotEmpty()) return fallbacks
        }

        throw UnknownHostException("Unable to resolve hostname: $hostname")
    }
}


