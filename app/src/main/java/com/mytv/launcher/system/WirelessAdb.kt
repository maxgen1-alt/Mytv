package com.mytv.launcher.system

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The TV's "Wireless debugging" (Android 11+), so MyTV can set itself up
 * without a phone: pair once with the 6-digit code from Settings, then MyTV
 * connects to the TV's own debugging service whenever it needs to (only while
 * Wireless debugging is on). Pairing and the encrypted connection are done by
 * libadb-android.
 */
class WirelessAdb private constructor(private val context: Context) : AbsAdbConnectionManager() {
    private val dir = File(context.filesDir, "adb-wireless").apply { mkdirs() }
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(dir, "private.key")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            // MyTV's own debugging key: the TV remembers it once paired.
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=MyTV")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 20 * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            key = pair.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "MyTV"

    /** Pairs with the code shown under Settings → Wireless debugging → Pair device with pairing code. */
    private fun pairNow(port: Int, code: String): Boolean {
        val ok = pair(port, code.trim())
        if (ok) context.getSharedPreferences("home_setup", Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply()
        return ok
    }

    /** Runs [command] in the TV's shell over Wireless debugging; null if MyTV can't reach it. */
    private fun shellNow(command: String): String? {
        if (!isConnected && !connectTls(context, 8_000L)) return null
        val stream = openStream("shell:$command")
        return try {
            stream.openInputStream().readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { stream.close() }
        }
    }

    companion object {
        private const val PAIRED = "wireless_paired"
        @Volatile private var instance: WirelessAdb? = null

        private fun get(context: Context): WirelessAdb =
            instance ?: synchronized(this) { instance ?: WirelessAdb(context.applicationContext).also { instance = it } }

        /** Paired with this TV's Wireless debugging before. */
        fun isPaired(context: Context) =
            context.getSharedPreferences("home_setup", Context.MODE_PRIVATE).getBoolean(PAIRED, false)

        /** Pairs; [port] is the number after the colon on the pairing screen (found automatically when null). */
        suspend fun pair(context: Context, code: String, port: Int?): String? = withContext(Dispatchers.IO) {
            val p = port ?: findPairingPort(context) ?: return@withContext "Couldn't find the pairing screen: type the port shown after the colon too."
            runCatching { if (get(context).pairNow(p, code)) null else "Pairing didn't work: check the code and try again." }
                .getOrElse { "Pairing didn't work (${it.message ?: it.javaClass.simpleName}). Open a new pairing code and try again." }
        }

        suspend fun shell(context: Context, command: String): String? = withContext(Dispatchers.IO) {
            if (!isPaired(context)) return@withContext null
            runCatching { get(context).shellNow(command) }.getOrNull()
        }

        /** The pairing screen's port, found on the network (it's only there while that screen is open). */
        private suspend fun findPairingPort(context: Context): Int? = withContext(Dispatchers.IO) {
            val nsd = context.getSystemService(NsdManager::class.java) ?: return@withContext null
            val found = java.util.concurrent.LinkedBlockingQueue<Int>()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) { found.offer(resolved.port) }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    })
                }
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            runCatching { nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.getOrNull() ?: return@withContext null
            try {
                found.poll(6, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }
}
