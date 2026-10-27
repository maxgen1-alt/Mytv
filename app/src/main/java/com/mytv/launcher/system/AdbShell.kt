package com.mytv.launcher.system

import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * A tiny ADB client that talks to the TV's own debugging service
 * (127.0.0.1:5555, on while "USB debugging" is on). It runs the same shell
 * commands you'd type into an ADB app on your phone (disable an app, set the
 * home screen, the screensaver...), so MyTV can set itself up, and undo it,
 * with one button. The first time, the TV asks "Allow USB debugging?":
 * tick "Always allow" and choose OK.
 *
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/protocol.txt
 */
class AdbShell(private val keyDir: File, private val host: String = "127.0.0.1", private val port: Int = 5555) {

    class AdbException(message: String) : Exception(message)

    /** Runs [command] in the TV's shell and returns what it printed. Blocking: call off the main thread. */
    fun run(command: String, authTimeoutMs: Int = 60_000): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3_000)
            socket.soTimeout = authTimeoutMs
            val input = DataInputStream(socket.getInputStream().buffered())
            val out = socket.getOutputStream()
            connect(input, out)
            socket.soTimeout = 30_000
            return shell(input, out, command)
        }
    }

    private fun connect(input: DataInputStream, out: OutputStream) {
        write(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        val keys = keyPair()
        var sentSignature = false
        while (true) {
            val m = read(input)
            when (m.command) {
                CNXN -> return
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw AdbException("Unexpected auth message")
                    if (!sentSignature) {
                        write(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                        sentSignature = true
                    } else {
                        // Not trusted yet: offer our key, the TV asks "Allow USB debugging?".
                        write(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes(keys.public as RSAPublicKey))
                    }
                }
                else -> throw AdbException("Unexpected reply while connecting")
            }
        }
    }

    private fun shell(input: DataInputStream, out: OutputStream, command: String): String {
        val localId = 1
        write(out, OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        val output = StringBuilder()
        var remoteId = 0
        while (true) {
            val m = read(input)
            when (m.command) {
                OKAY -> remoteId = m.arg0
                WRTE -> {
                    output.append(String(m.data))
                    write(out, OKAY, localId, m.arg0, ByteArray(0))
                }
                CLSE -> {
                    if (remoteId != 0) runCatching { write(out, CLSE, localId, remoteId, ByteArray(0)) }
                    return output.toString()
                }
            }
        }
    }

    // ---- Keys ----

    private fun keyPair(): KeyPair {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (priv.exists() && pub.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                )
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        keyDir.mkdirs()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** adbd checks the token with RSA_verify(NID_sha1): PKCS#1 v1.5 over SHA-1's DigestInfo prefix + the token. */
    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

        private fun write(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
                .putInt(data.sumOf { it.toInt() and 0xff })
                .putInt(command.inv())
            out.write(header.array())
            out.write(data)
            out.flush()
        }

        private fun read(input: DataInputStream): Message {
            val header = ByteArray(24)
            input.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            val data = ByteArray(length)
            input.readFully(data)
            return Message(command, arg0, arg1, data)
        }

        /**
         * The public key in adbd's format: base64 of
         * { len (words), n0inv, modulus (LE), R² mod n (LE), exponent } + " name\0".
         */
        fun publicKeyBytes(key: RSAPublicKey, name: String = "mytv@tv"): ByteArray {
            val words = 64 // 2048 bits
            val n = key.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
            val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(words).putInt(n0inv)
            buf.put(littleEndian(n, words * 4))
            buf.put(littleEndian(rr, words * 4))
            buf.putInt(key.publicExponent.toInt())
            return (Base64.getEncoder().encodeToString(buf.array()) + " $name\u0000").toByteArray()
        }

        private fun littleEndian(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
            val out = ByteArray(size)
            for (i in be.indices) out[i] = be[be.size - 1 - i]
            return out
        }
    }
}
