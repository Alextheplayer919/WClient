package com.retrivedmods.wclient.util

import java.math.BigInteger
import java.security.InvalidKeyException
import java.security.InvalidParameterException
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.SignatureException
import java.security.SignatureSpi
import java.security.interfaces.ECKey

/**
 * Supplies "SHA256withECDSAinP1363Format" (Java 9+ only, missing on Android).
 * MinecraftAuth needs it to sign Xbox Live requests; without it, it falls back to JJWT,
 * which breaks under R8 -> "Fetch account failed: Could not sign request".
 */
object P1363SignatureProvider : Provider("WClientP1363", 1.0, "SHA256withECDSAinP1363Format for Android") {

    private const val ALGORITHM = "SHA256withECDSAinP1363Format"

    init {
        put("Signature.$ALGORITHM", P1363Spi::class.java.name)
        put("Alg.Alias.Signature.SHA256withECDSAinP1363", ALGORITHM)
    }

    fun install() {
        if (Security.getProvider(name) != null) return
        try {
            Signature.getInstance(ALGORITHM) // platform already has it
        } catch (_: Exception) {
            Security.addProvider(this)
        }
    }

    class P1363Spi : SignatureSpi() {

        private val inner: Signature = Signature.getInstance("SHA256withECDSA")
        private var fieldBytes = 32

        private fun updateFieldSize(key: Any) {
            val bits = (key as? ECKey)?.params?.order?.bitLength() ?: 256
            fieldBytes = (bits + 7) / 8
        }

        @Throws(InvalidKeyException::class)
        override fun engineInitVerify(publicKey: PublicKey) {
            updateFieldSize(publicKey); inner.initVerify(publicKey)
        }

        @Throws(InvalidKeyException::class)
        override fun engineInitSign(privateKey: PrivateKey) {
            updateFieldSize(privateKey); inner.initSign(privateKey)
        }

        @Throws(InvalidKeyException::class)
        override fun engineInitSign(privateKey: PrivateKey, random: SecureRandom?) {
            updateFieldSize(privateKey); inner.initSign(privateKey, random)
        }

        @Throws(SignatureException::class)
        override fun engineUpdate(b: Byte) = inner.update(b)

        @Throws(SignatureException::class)
        override fun engineUpdate(b: ByteArray, off: Int, len: Int) = inner.update(b, off, len)

        @Throws(SignatureException::class)
        override fun engineSign(): ByteArray = derToP1363(inner.sign(), fieldBytes)

        @Throws(SignatureException::class)
        override fun engineVerify(sigBytes: ByteArray): Boolean = inner.verify(p1363ToDer(sigBytes))

        @Deprecated("Deprecated in Java")
        @Throws(InvalidParameterException::class)
        override fun engineSetParameter(param: String, value: Any?) {
            throw InvalidParameterException("Unsupported parameter: $param")
        }

        @Deprecated("Deprecated in Java")
        @Throws(InvalidParameterException::class)
        override fun engineGetParameter(param: String): Any? {
            throw InvalidParameterException("Unsupported parameter: $param")
        }
    }

    @Throws(SignatureException::class)
    internal fun derToP1363(der: ByteArray, size: Int): ByteArray {
        try {
            var idx = 0
            require(der[idx++] == 0x30.toByte()) { "Not a DER sequence" }
            val seqLen = der[idx++].toInt() and 0xFF
            if (seqLen and 0x80 != 0) idx += seqLen and 0x7F

            require(der[idx++] == 0x02.toByte()) { "Expected INTEGER r" }
            val rLen = der[idx++].toInt() and 0xFF
            val r = der.copyOfRange(idx, idx + rLen); idx += rLen

            require(der[idx++] == 0x02.toByte()) { "Expected INTEGER s" }
            val sLen = der[idx++].toInt() and 0xFF
            val s = der.copyOfRange(idx, idx + sLen)

            val out = ByteArray(size * 2)
            fixed(r, size).copyInto(out, 0)
            fixed(s, size).copyInto(out, size)
            return out
        } catch (e: Exception) {
            throw SignatureException("Invalid DER signature", e)
        }
    }

    @Throws(SignatureException::class)
    internal fun p1363ToDer(sig: ByteArray): ByteArray {
        try {
            require(sig.size % 2 == 0) { "Invalid P1363 signature length" }
            val half = sig.size / 2
            val r = BigInteger(1, sig.copyOfRange(0, half)).toByteArray()
            val s = BigInteger(1, sig.copyOfRange(half, sig.size)).toByteArray()

            val body = ByteArray(2 + r.size + 2 + s.size)
            var i = 0
            body[i++] = 0x02; body[i++] = r.size.toByte(); r.copyInto(body, i); i += r.size
            body[i++] = 0x02; body[i++] = s.size.toByte(); s.copyInto(body, i)

            return if (body.size < 0x80) byteArrayOf(0x30, body.size.toByte()) + body
            else byteArrayOf(0x30, 0x81.toByte(), body.size.toByte()) + body
        } catch (e: Exception) {
            throw SignatureException("Invalid P1363 signature", e)
        }
    }

    private fun fixed(v: ByteArray, size: Int): ByteArray = when {
        v.size == size -> v
        v.size == size + 1 && v[0] == 0.toByte() -> v.copyOfRange(1, v.size)
        v.size < size -> ByteArray(size).also { v.copyInto(it, size - v.size) }
        else -> throw IllegalArgumentException("ECDSA integer too long")
    }
}
