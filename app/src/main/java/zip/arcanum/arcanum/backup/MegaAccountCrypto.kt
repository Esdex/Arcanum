package zip.arcanum.arcanum.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPrivateKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import zip.arcanum.R

internal object MegaAccountCrypto {
    private val secureRandom = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private val zeroIv = ByteArray(16)

    data class PasswordMaterial(
        val passwordAesKey: IntArray,
        val userHash: String
    ) {
        override fun equals(other: Any?): Boolean =
            other is PasswordMaterial &&
                passwordAesKey.contentEquals(other.passwordAesKey) &&
                userHash == other.userHash

        override fun hashCode(): Int = 31 * passwordAesKey.contentHashCode() + userHash.hashCode()
    }

    data class RsaPrivateKey(
        val p: BigInteger,
        val q: BigInteger,
        val d: BigInteger
    )

    fun passwordMaterialV1(email: String, password: String): PasswordMaterial {
        val passwordKey = prepareMasterKey(bytesToInts(password.toByteArray(Charsets.UTF_8)))
        return PasswordMaterial(
            passwordAesKey = passwordKey,
            userHash = userHash(email.lowercase().toByteArray(Charsets.UTF_8), passwordKey)
        )
    }

    fun passwordMaterialV2(password: String, salt: String): PasswordMaterial {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec(password.toCharArray(), apiBase64Decode(salt), 100_000, 256))
            .encoded
        return PasswordMaterial(
            passwordAesKey = bytesToInts(key.copyOfRange(0, 16)),
            userHash = apiBase64Encode(key.copyOfRange(16, 32))
        )
    }

    fun decryptMasterKey(encryptedMasterKey: String, passwordAesKey: IntArray): ByteArray =
        aesEcbDecrypt(apiBase64Decode(encryptedMasterKey), intsToBytes(passwordAesKey))

    fun decryptKey(encryptedKey: String, masterKey: ByteArray): ByteArray =
        aesEcbDecrypt(apiBase64Decode(encryptedKey), masterKey)

    fun encryptKey(key: ByteArray, masterKey: ByteArray): String =
        apiBase64Encode(aesEcbEncrypt(key, masterKey))

    fun randomUploadKey(): IntArray = bytesToInts(randomBytes(24))

    fun randomFolderKey(): ByteArray = randomBytes(16)

    fun uploadContentKey(uploadKey: IntArray): ByteArray =
        intsToBytes(uploadKey.copyOfRange(0, 4))

    fun uploadContentIv(uploadKey: IntArray): ByteArray =
        intsToBytes(intArrayOf(uploadKey[4], uploadKey[5], 0, 0))

    fun uploadAttributeKey(uploadKey: IntArray): ByteArray =
        uploadContentKey(uploadKey)

    fun finalFileKey(uploadKey: IntArray, metaMac: IntArray): IntArray = intArrayOf(
        uploadKey[0] xor uploadKey[4],
        uploadKey[1] xor uploadKey[5],
        uploadKey[2] xor metaMac[0],
        uploadKey[3] xor metaMac[1],
        uploadKey[4],
        uploadKey[5],
        metaMac[0],
        metaMac[1]
    )

    fun encryptAttributes(attributesJson: String, key: ByteArray): String {
        val bytes = ("MEGA$attributesJson").toByteArray(Charsets.UTF_8)
        val padded = bytes.copyOf(roundUp(bytes.size, 16))
        return apiBase64Encode(aesCbcEncrypt(padded, key, zeroIv))
    }

    fun decryptAttributes(encodedAttributes: String, key: ByteArray): JsonObject? =
        runCatching {
            val decrypted = aesCbcDecrypt(apiBase64Decode(encodedAttributes), key, zeroIv)
            val value = decrypted
                .toString(Charsets.UTF_8)
                .trimEnd('\u0000')
                .removePrefix("MEGA")
            json.parseToJsonElement(value).jsonObject
        }.getOrNull()

    fun decryptNodeKey(rawKey: String, masterKey: ByteArray, attributes: String?): ByteArray? {
        val candidates = rawKey.split('/')
            .mapNotNull { entry -> entry.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotBlank() } }
        for (candidate in candidates) {
            val decrypted = runCatching { aesEcbDecrypt(apiBase64Decode(candidate), masterKey) }.getOrNull() ?: continue
            val attributeKey = decrypted.copyOfRange(0, min(16, decrypted.size))
            if (attributes == null || decryptAttributes(attributes, attributeKey) != null) return decrypted
        }
        return null
    }

    fun extractRsaPrivateKey(decryptedPrivateKey: ByteArray): RsaPrivateKey {
        var offset = 0
        val values = ArrayList<BigInteger>(4)
        repeat(4) {
            if (offset + 2 > decryptedPrivateKey.size) throw BackupValidationException(R.string.backup_error_mega_rsa_corrupt)
            val bitLength = ((decryptedPrivateKey[offset].toInt() and 0xff) shl 8) or
                (decryptedPrivateKey[offset + 1].toInt() and 0xff)
            val byteLength = (bitLength + 7) / 8
            val start = offset + 2
            val end = start + byteLength
            if (end > decryptedPrivateKey.size) throw BackupValidationException(R.string.backup_error_mega_rsa_incomplete)
            values += BigInteger(1, decryptedPrivateKey.copyOfRange(start, end))
            offset = end
        }
        return RsaPrivateKey(p = values[0], q = values[1], d = values[2])
    }

    fun sessionIdFromEncryptedCsid(csid: String, privateKey: RsaPrivateKey): String {
        val encrypted = mpiToBigInteger(apiBase64Decode(csid))
        val decrypted = rsaDecrypt(encrypted, privateKey)
        if (decrypted.size < SESSION_ID_BYTES) throw BackupValidationException(R.string.backup_error_mega_session_short)
        return apiBase64Encode(decrypted.copyOfRange(0, SESSION_ID_BYTES))
    }

    fun encryptUploadChunk(plain: ByteArray, key: ByteArray, baseIv: ByteArray, offset: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(forwardIv(baseIv, offset)))
        return cipher.doFinal(plain)
    }

    fun apiBase64Encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun apiBase64Decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    fun bytesToInts(bytes: ByteArray): IntArray {
        val padded = bytes.copyOf(roundUp(bytes.size, 4))
        val buffer = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also(buffer::get)
    }

    fun intsToBytes(values: IntArray): ByteArray =
        ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.BIG_ENDIAN)
            .also { buffer -> values.forEach(buffer::putInt) }
            .array()

    fun solveHashcash(challenge: String, timeoutMs: Long = 60_000L): String {
        val parts = challenge.split(':')
        if (parts.size != 4 || parts[0] != "1") throw BackupValidationException(R.string.backup_error_mega_hashcash_unsupported)
        val easiness = parts[1].toIntOrNull() ?: throw BackupValidationException(R.string.backup_error_mega_hashcash_corrupt)
        val token = parts[3]
        val tokenBytes = apiBase64Decode(token)
        if (tokenBytes.size != HASHCASH_TOKEN_BYTES) throw BackupValidationException(R.string.backup_error_mega_hashcash_token_corrupt)

        val threshold = (((((easiness and 63) shl 1) + 1).toLong()) shl (((easiness ushr 6) and 3) * 7 + 3)) and
            0xffffffffL
        val tokenArea = ByteArray(HASHCASH_PREFIX_BYTES + HASHCASH_REPEAT * HASHCASH_TOKEN_BYTES)
        var filled = 0
        while (filled < HASHCASH_REPEAT * HASHCASH_TOKEN_BYTES) {
            val copy = min(tokenBytes.size, HASHCASH_REPEAT * HASHCASH_TOKEN_BYTES - filled)
            tokenBytes.copyInto(tokenArea, HASHCASH_PREFIX_BYTES + filled, 0, copy)
            filled += copy
        }

        val stopped = AtomicBoolean(false)
        val winner = AtomicReference<String?>(null)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) { worker ->
                pool.execute {
                    val answer = solveHashcashWorker(tokenArea, threshold, worker.toLong(), workers.toLong(), stopped)
                    if (answer != null && winner.compareAndSet(null, answer)) stopped.set(true)
                }
            }
            pool.shutdown()
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                stopped.set(true)
                pool.shutdownNow()
                throw BackupValidationException(R.string.backup_error_mega_hashcash_timeout, timeoutMs / 1000)
            }
        } finally {
            stopped.set(true)
            if (!pool.isTerminated) pool.shutdownNow()
        }
        return "1:$token:${winner.get() ?: throw BackupValidationException(R.string.backup_error_mega_hashcash_not_found)}"
    }

    class UploadMac(private val fileKey: ByteArray, fileIv: ByteArray) {
        private val ivInts = bytesToInts(fileIv)
        private var fileMac = intArrayOf(0, 0, 0, 0)

        fun updateChunk(plain: ByteArray) {
            var chunkMac = intArrayOf(ivInts[0], ivInts[1], ivInts[0], ivInts[1])
            var offset = 0
            while (offset < plain.size) {
                val block = ByteArray(16)
                val count = min(16, plain.size - offset)
                plain.copyInto(block, 0, offset, offset + count)
                val blockInts = bytesToInts(block)
                repeat(4) { chunkMac[it] = chunkMac[it] xor blockInts[it] }
                chunkMac = bytesToInts(aesEcbEncrypt(intsToBytes(chunkMac), fileKey))
                offset += count
            }
            repeat(4) { fileMac[it] = fileMac[it] xor chunkMac[it] }
            fileMac = bytesToInts(aesEcbEncrypt(intsToBytes(fileMac), fileKey))
        }

        fun finish(): IntArray = intArrayOf(
            fileMac[0] xor fileMac[1],
            fileMac[2] xor fileMac[3]
        )
    }

    private fun userHash(emailBytes: ByteArray, aesKey: IntArray): String {
        val source = bytesToInts(emailBytes)
        var hash = intArrayOf(0, 0, 0, 0)
        source.forEachIndexed { index, value -> hash[index % 4] = hash[index % 4] xor value }
        repeat(0x4000) {
            hash = bytesToInts(aesCbcEncrypt(intsToBytes(hash), intsToBytes(aesKey), zeroIv))
        }
        return apiBase64Encode(intsToBytes(intArrayOf(hash[0], hash[2])))
    }

    private fun prepareMasterKey(key: IntArray): IntArray {
        var prepared = intArrayOf(0x93C467E3.toInt(), 0x7DB0C7A4.toInt(), 0xD1BE3F81.toInt(), 0x0152CB56)
        repeat(0x10000) {
            var offset = 0
            while (offset < key.size) {
                val part = IntArray(4)
                for (i in 0 until 4) if (offset + i < key.size) part[i] = key[offset + i]
                prepared = bytesToInts(aesCbcEncrypt(intsToBytes(prepared), intsToBytes(part), zeroIv))
                offset += 4
            }
        }
        return prepared
    }

    private fun solveHashcashWorker(
        tokenArea: ByteArray,
        threshold: Long,
        start: Long,
        stride: Long,
        stopped: AtomicBoolean
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val firstBlock = ByteArray(64)
        tokenArea.copyInto(firstBlock, HASHCASH_PREFIX_BYTES, HASHCASH_PREFIX_BYTES, 64)
        var nonce = start
        while (nonce < HASHCASH_NONCE_LIMIT && !stopped.get()) {
            firstBlock[0] = ((nonce ushr 24) and 0xff).toByte()
            firstBlock[1] = ((nonce ushr 16) and 0xff).toByte()
            firstBlock[2] = ((nonce ushr 8) and 0xff).toByte()
            firstBlock[3] = (nonce and 0xff).toByte()
            digest.reset()
            digest.update(firstBlock)
            digest.update(tokenArea, 64, tokenArea.size - 64)
            val hash = digest.digest()
            val firstWord = ((hash[0].toLong() and 0xff) shl 24) or
                ((hash[1].toLong() and 0xff) shl 16) or
                ((hash[2].toLong() and 0xff) shl 8) or
                (hash[3].toLong() and 0xff)
            if (firstWord <= threshold) {
                return apiBase64Encode(byteArrayOf(firstBlock[0], firstBlock[1], firstBlock[2], firstBlock[3]))
            }
            nonce += stride
        }
        return null
    }

    private fun forwardIv(baseIv: ByteArray, offset: Long): ByteArray {
        val iv = ByteArray(baseIv.size)
        baseIv.copyInto(iv, 0, 0, baseIv.size / 2)
        val counter = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offset / baseIv.size).array()
        counter.copyInto(iv, baseIv.size / 2)
        return iv
    }

    private fun rsaDecrypt(encrypted: BigInteger, privateKey: RsaPrivateKey): ByteArray {
        val modulus = privateKey.p.multiply(privateKey.q)
        val spec = RSAPrivateKeySpec(modulus, privateKey.d)
        val key = KeyFactory.getInstance("RSA").generatePrivate(spec)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val rawInput = encrypted.toByteArray().dropLeadingZero()
        val expectedLength = (modulus.bitLength() + 7) / 8
        val input = if (rawInput.size < expectedLength) {
            ByteArray(expectedLength - rawInput.size) + rawInput
        } else {
            rawInput
        }
        return cipher.doFinal(input).dropLeadingZero()
    }

    private fun mpiToBigInteger(bytes: ByteArray): BigInteger =
        if (bytes.size <= 2) BigInteger.ZERO else BigInteger(1, bytes.copyOfRange(2, bytes.size))

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray =
        aes("AES/ECB/NoPadding", Cipher.ENCRYPT_MODE, data, key, null)

    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray =
        aes("AES/ECB/NoPadding", Cipher.DECRYPT_MODE, data, key, null)

    private fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes("AES/CBC/NoPadding", Cipher.ENCRYPT_MODE, data, key, iv)

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes("AES/CBC/NoPadding", Cipher.DECRYPT_MODE, data, key, iv)

    private fun aes(mode: String, op: Int, data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(mode)
        if (iv == null) {
            cipher.init(op, SecretKeySpec(key, "AES"))
        } else {
            cipher.init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return cipher.doFinal(data)
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    private fun roundUp(value: Int, step: Int): Int =
        if (value == 0) 0 else ((value + step - 1) / step) * step

    private fun ByteArray.dropLeadingZero(): ByteArray =
        if (isNotEmpty() && this[0].toInt() == 0) copyOfRange(1, size) else this

    private const val SESSION_ID_BYTES = 43
    private const val HASHCASH_TOKEN_BYTES = 48
    private const val HASHCASH_PREFIX_BYTES = 4
    private const val HASHCASH_REPEAT = 262_144
    private const val HASHCASH_NONCE_LIMIT = 1L shl 32
}
