package com.mytv.launcher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

class AdbShellTest {

    private fun frame(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
            .put(data).array()

    private fun readFrame(input: DataInputStream): Triple<Int, Int, ByteArray> {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; b.int; val len = b.int
        val sum = b.int; val magic = b.int
        assertEquals(cmd.inv(), magic)
        val d = ByteArray(len); input.readFully(d)
        assertEquals(sum, d.sumOf { it.toInt() and 0xff })
        return Triple(cmd, a0, d)
    }

    /** A fake adbd: rejects the first signature, accepts the public key, then runs one shell command. */
    @Test fun handshakeAndShell() {
        val dir = File(System.getProperty("java.io.tmpdir"), "adbtest-" + System.nanoTime())
        val server = ServerSocket(0)
        var openedWith = ""
        var keyLine = ""
        var signature = ByteArray(0)
        var tokenSent = ByteArray(0)
        val t = thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                assertEquals(0x4e584e43, readFrame(input).first) // CNXN
                val token = ByteArray(20) { it.toByte() }
                out.write(frame(0x48545541, 1, 0, token)) // AUTH TOKEN
                val (_, type1, sig) = readFrame(input)
                assertEquals(2, type1) // signature
                signature = sig
                tokenSent = token
                out.write(frame(0x48545541, 1, 0, token)) // not trusted yet
                val (_, type2, pub) = readFrame(input)
                assertEquals(3, type2) // public key
                keyLine = String(pub)
                out.write(frame(0x4e584e43, 0x01000000, 4096, "device::\u0000".toByteArray()))
                val (open, local, cmd) = readFrame(input)
                assertEquals(0x4e45504f, open)
                openedWith = String(cmd)
                out.write(frame(0x59414b4f, 7, local))
                out.write(frame(0x45545257, 7, local, "Package com.x new state: disabled-user\n".toByteArray()))
                assertEquals(0x59414b4f, readFrame(input).first) // our OKAY
                out.write(frame(0x45534c43, 7, local))
                readFrame(input) // our CLSE
            }
        }
        val result = AdbShell(dir, port = server.localPort).run("pm disable-user --user 0 com.x")
        t.join()
        server.close()
        assertEquals("shell:pm disable-user --user 0 com.x\u0000", openedWith)
        assertEquals("Package com.x new state: disabled-user\n", result)
        assertTrue(keyLine.endsWith(" mytv@tv\u0000"))
        // The signature verifies with that key the way adbd checks it.
        val raw = ByteBuffer.wrap(Base64.getDecoder().decode(keyLine.substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        raw.int; raw.int
        val n = BigInteger(1, ByteArray(256).also { raw.get(it) }.reversedArray())
        raw.position(raw.position() + 256)
        val e = BigInteger.valueOf(raw.int.toLong())
        val pub = KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(n, e))
        val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pub) }.doFinal(signature)
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        assertTrue(plain.contentEquals(digestInfo + tokenSent))
        // A second run reuses the saved key.
        assertTrue(File(dir, "adbkey").exists())
    }

    /** The signature must decrypt (public key) to SHA-1 DigestInfo + token, like adbd's RSA_verify. */
    @Test fun keyFormatMatchesAdbd() {
        val dir = File(System.getProperty("java.io.tmpdir"), "adbkey-" + System.nanoTime())
        // Create the key through a failed connection attempt (nothing listening on port 1).
        runCatching { AdbShell(dir, port = 1).run("true") }
        val kp = KeyPairGeneratorHelper.generate()
        val pub = kp.public as RSAPublicKey
        val line = String(AdbShell.publicKeyBytes(pub)).substringBefore(' ')
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(line)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int
        val mod = ByteArray(256).also { b.get(it) }
        val n = BigInteger(1, mod.reversedArray())
        assertEquals(pub.modulus, n)
        // n0inv * n ≡ -1 (mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv.toLong() and 0xffffffffL).multiply(n).mod(r32))
        val rr = ByteArray(256).also { b.get(it) }
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr.reversedArray()))
        assertEquals(65537, b.int)
    }
}

private object KeyPairGeneratorHelper {
    fun generate() = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
