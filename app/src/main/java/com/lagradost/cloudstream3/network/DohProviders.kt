package com.lagradost.cloudstream3.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Based on https://github.com/tachiyomiorg/tachiyomi/blob/master/app/src/main/java/eu/kanade/tachiyomi/network/DohProviders.kt
 */

fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>) = dns(
    ResilientDns(
        DnsOverHttps
            .Builder()
            // Délais courts : si le serveur DNS sécurisé est injoignable, on bascule vite.
            .client(build().newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(
                ips.map { InetAddress.getByName(it) }
            )
            .build()
    )
)

/**
 * MISSA : DNS sécurisé (contourne les blocages DNS des fournisseurs d'accès) qui ne casse
 * jamais le réseau. Si le serveur DNS sécurisé est injoignable (réseau qui le bloque), la
 * résolution passe par le DNS normal de l'appareil, et le DNS sécurisé est mis de côté
 * 5 minutes pour ne pas ralentir chaque requête.
 */
class ResilientDns(private val secure: Dns) : Dns {
    @Volatile
    private var secureDownUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now >= secureDownUntil) {
            try {
                val result = secure.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (e: UnknownHostException) {
                // Nom inconnu pour le DNS sécurisé, ou serveur injoignable : on demande au DNS
                // normal. Si lui trouve, c'est que le DNS sécurisé est en panne : pause 5 min.
                val system = Dns.SYSTEM.lookup(hostname)
                if (e.cause != null) secureDownUntil = now + 5 * 60_000L
                return system
            } catch (e: Exception) {
                secureDownUntil = now + 5 * 60_000L
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}

fun OkHttpClient.Builder.addGoogleDns() = (
        addGenericDns(
            "https://dns.google/dns-query",
            listOf(
                "8.8.4.4",
                "8.8.8.8"
            )
        ))

fun OkHttpClient.Builder.addCloudFlareDns() = (
        addGenericDns(
            "https://cloudflare-dns.com/dns-query",
            // https://www.cloudflare.com/ips/
            listOf(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001"
            )
        ))

// Commented out as it doesn't work
//fun OkHttpClient.Builder.addOpenDns() = (
//        addGenericDns(
//            "https://doh.opendns.com/dns-query",
//            // https://support.opendns.com/hc/en-us/articles/360038086532-Using-DNS-over-HTTPS-DoH-with-OpenDNS
//            listOf(
//                "208.67.222.222",
//                "208.67.220.220",
//                "2620:119:35::35",
//                "2620:119:53::53",
//            )
//        ))


fun OkHttpClient.Builder.addAdGuardDns() = (
        addGenericDns(
            "https://dns.adguard.com/dns-query",
            // https://github.com/AdguardTeam/AdGuardDNS
            listOf(
                // "Non-filtering"
                "94.140.14.140",
                "94.140.14.141",
            )
        ))

fun OkHttpClient.Builder.addDNSWatchDns() = (
    addGenericDns(
        "https://resolver2.dns.watch/dns-query",
        // https://dns.watch/
        listOf(
            "84.200.69.80",
            "84.200.70.40",
        )
    ))

fun OkHttpClient.Builder.addQuad9Dns() = (
    addGenericDns(
        "https://dns.quad9.net/dns-query",
        // https://www.quad9.net/service/service-addresses-and-features
        listOf(
            "9.9.9.9",
            "149.112.112.112",
        )
    ))

fun OkHttpClient.Builder.addDnsSbDns() = (
        addGenericDns(
            "https://doh.dns.sb/dns-query",
            //https://dns.sb/guide/
            listOf(
                "185.222.222.222",
                "45.11.45.11",
            )
        ))

fun OkHttpClient.Builder.addCanadianShieldDns() = (
        addGenericDns(
            "https://private.canadianshield.cira.ca/dns-query",
            //https://www.cira.ca/en/canadian-shield/configure/summary-cira-canadian-shield-dns-resolver-addresses/
            listOf(
                "149.112.121.10",
                "149.112.122.10",
            )
        ))
