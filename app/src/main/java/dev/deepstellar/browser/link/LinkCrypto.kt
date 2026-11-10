package dev.deepstellar.browser.link

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of the Deep Stellar link protocol, version 1 (docs/LINK-PROTOCOL.md in the
 * Deep Stellar Station repository): ECDH P-256, HKDF-SHA256, HMAC-SHA256, AES-256-GCM.
 * Android's built-in providers only — no extra libraries.
 */
object LinkCrypto {
    private val rng = SecureRandom()
    private val utf8 = Charsets.UTF_8

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val curve: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    /** 32-byte big-endian, left-padded. */
    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // leading sign byte
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** Uncompressed point: 0x04 || X || Y (65 bytes). */
    fun rawPublic(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    fun publicFromRaw(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "Not an uncompressed P-256 key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    fun privateFromPkcs8(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(bytes))

    fun fingerprint(pubB64: String): String = b64(MessageDigest.getInstance("SHA-256").digest(unb64(pubB64))).take(16)

    fun hmac(key: ByteArray, text: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return b64(m.doFinal(text.toByteArray(utf8)))
    }

    fun sameText(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(utf8), b.toByteArray(utf8))

    /** HKDF-SHA256 with a 32-byte output (one block). */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = m.doFinal(ikm)
        m.init(SecretKeySpec(prk, "HmacSHA256"))
        m.update(info)
        m.update(1.toByte())
        return m.doFinal()
    }

    fun sharedKey(myPrivate: PrivateKey, myPubB64: String, peerPubB64: String): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(publicFromRaw(unb64(peerPubB64)), true)
        val z = ka.generateSecret()
        val info = listOf(myPubB64, peerPubB64).sorted().joinToString("|")
        return SecretKeySpec(hkdf(z, "deep-stellar-link-v1".toByteArray(utf8), info.toByteArray(utf8)), "AES")
    }

    fun seal(key: SecretKeySpec, senderFp: String, body: JSONObject): JSONObject {
        val n = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, n))
        c.updateAAD("dsl-msg-1|$senderFp".toByteArray(utf8))
        val ct = c.doFinal(body.toString().toByteArray(utf8))
        return JSONObject().put("t", "msg").put("fp", senderFp).put("n", b64(n)).put("c", b64(ct))
    }

    /** Null if the frame wasn't made with this key (or was altered). */
    fun open(key: SecretKeySpec, frame: JSONObject): JSONObject? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(frame.getString("n"))))
        c.updateAAD(("dsl-msg-1|" + frame.getString("fp")).toByteArray(utf8))
        JSONObject(String(c.doFinal(unb64(frame.getString("c"))), utf8))
    } catch (e: Exception) {
        null
    }
}
