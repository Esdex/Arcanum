package zip.arcanum.arcanum.backup

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import zip.arcanum.R

internal class MegaAccountClient(private val context: Context? = null) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private var sequence = System.currentTimeMillis()
    private var sessionId: String? = null
    private var masterKey: ByteArray? = null
    private var rootHandle: String = ""
    private val nodes = linkedMapOf<String, MegaNode>()

    fun login(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) throw BackupValidationException(R.string.backup_error_mega_email_required)
        if (password.isBlank()) throw BackupValidationException(R.string.backup_error_mega_password_required)

        val accountInfo = api(
            buildJsonObject {
                put("a", "us0")
                put("user", normalizedEmail)
            },
            includeSession = false
        )[0].jsonObject

        val version = accountInfo["v"]?.jsonPrimitive?.intOrNull ?: 1
        val material = if (version == 1) {
            MegaAccountCrypto.passwordMaterialV1(normalizedEmail, password)
        } else {
            val salt = accountInfo["s"]?.jsonPrimitive?.contentOrNull()
                ?: throw BackupValidationException(R.string.backup_error_mega_no_salt)
            MegaAccountCrypto.passwordMaterialV2(password, salt)
        }

        val loginResponse = api(
            buildJsonObject {
                put("a", "us")
                put("user", normalizedEmail)
                put("uh", material.userHash)
            },
            includeSession = false
        )[0].jsonObject

        if (loginResponse["k"] == null && loginResponse["keys"] != null) {
            throw BackupValidationException(R.string.backup_error_mega_new_key_format)
        }

        val encryptedMaster = loginResponse["k"]?.jsonPrimitive?.contentOrNull()
            ?: throw BackupValidationException(R.string.backup_error_mega_no_account_key)
        val encryptedPrivateKey = loginResponse["privk"]?.jsonPrimitive?.contentOrNull()
            ?: throw BackupValidationException(R.string.backup_error_mega_no_private_key)
        val encryptedSessionId = loginResponse["csid"]?.jsonPrimitive?.contentOrNull()
            ?: throw BackupValidationException(R.string.backup_error_mega_no_session)

        val decryptedMasterKey = MegaAccountCrypto.decryptMasterKey(encryptedMaster, material.passwordAesKey)
        val decryptedPrivateKey = MegaAccountCrypto.decryptKey(encryptedPrivateKey, decryptedMasterKey)
        val rsaKey = MegaAccountCrypto.extractRsaPrivateKey(decryptedPrivateKey)

        masterKey = decryptedMasterKey
        sessionId = MegaAccountCrypto.sessionIdFromEncryptedCsid(encryptedSessionId, rsaKey)
        fetchNodes()
    }

    fun ensureFolder(path: String): String = resolveFolder(path, create = true)

    suspend fun upload(
        source: BackupSource,
        fileName: String,
        folderPath: String,
        onProgress: BackupProgressCallback
    ): String {
        val parent = resolveFolder(folderPath, create = true)
        val total = source.sizeBytes
        val uploadUrl = initUpload(total)
        val uploadKey = MegaAccountCrypto.randomUploadKey()
        val fileKey = MegaAccountCrypto.uploadContentKey(uploadKey)
        val fileIv = MegaAccountCrypto.uploadContentIv(uploadKey)
        val mac = MegaAccountCrypto.UploadMac(fileKey, fileIv)
        var completionHandle: String? = null

        if (total > 0L) {
            completionHandle = uploadChunksParallel(
                source = source,
                total = total,
                uploadUrl = uploadUrl,
                fileKey = fileKey,
                fileIv = fileIv,
                mac = mac,
                onProgress = onProgress
            )
        } else {
            completionHandle = postUploadChunk(uploadUrl, total, 0L, ByteArray(0))
            onProgress.onProgress(0L, 0L, text(R.string.backup_progress_mega_empty_uploaded))
        }

        val completion = completionHandle
            ?: throw BackupValidationException(R.string.backup_error_mega_no_completion)
        val finalKey = MegaAccountCrypto.finalFileKey(uploadKey, mac.finish())
        return finishUpload(fileName, parent, completion, uploadKey, finalKey)
    }

    private suspend fun uploadChunksParallel(
        source: BackupSource,
        total: Long,
        uploadUrl: String,
        fileKey: ByteArray,
        fileIv: ByteArray,
        mac: MegaAccountCrypto.UploadMac,
        onProgress: BackupProgressCallback
    ): String? = coroutineScope {
        val parallelism = if (source.container.safUri.isBlank()) MEGA_PARALLEL_CHUNKS else MEGA_PARALLEL_CHUNKS_FOR_SAF
        val inFlight = linkedMapOf<Long, Deferred<MegaChunkResult>>()
        var nextToSchedule = 1L
        var nextToCommit = 1L
        var uploaded = 0L
        var completionHandle: String? = null

        while (chunkOffset(nextToSchedule) < total || inFlight.isNotEmpty()) {
            while (inFlight.size < parallelism && chunkOffset(nextToSchedule) < total) {
                val chunkId = nextToSchedule
                val offset = chunkOffset(chunkId)
                val size = chunkSize(chunkId, total, offset).toInt()
                inFlight[chunkId] = async(Dispatchers.IO) {
                    val plain = source.open(offset).use { input -> input.readExact(size) }
                    val encrypted = MegaAccountCrypto.encryptUploadChunk(plain, fileKey, fileIv, offset)
                    MegaChunkResult(
                        chunkId = chunkId,
                        plain = plain,
                        response = postUploadChunk(uploadUrl, total, offset, encrypted)
                    )
                }
                nextToSchedule++
            }

            coroutineContext.ensureActive()
            val result = inFlight.remove(nextToCommit)?.await()
                ?: throw BackupValidationException(R.string.backup_error_mega_lost_chunk, nextToCommit)
            val plainSize = result.plain.size.toLong()
            try {
                mac.updateChunk(result.plain)
            } finally {
                result.plain.fill(0)
            }
            if (result.response.isNotBlank()) completionHandle = result.response
            uploaded += plainSize
            onProgress.onProgress(
                uploaded,
                total,
                text(R.string.backup_progress_mega_parallel, parallelism, uploaded.formatBytes(), total.formatBytes())
            )
            nextToCommit++
        }

        completionHandle
    }

    fun deleteNode(handle: String) {
        if (handle.isBlank()) return
        api(
            buildJsonObject {
                put("a", "d")
                put("n", handle)
            }
        )
        nodes.remove(handle)
    }

    private fun fetchNodes() {
        val response = api(
            buildJsonObject {
                put("a", "f")
                put("c", 1)
            }
        )[0].jsonObject

        nodes.clear()
        rootHandle = ""
        val accountKey = requireMasterKey()
        response["f"]?.jsonArray?.forEach { element ->
            val node = element.jsonObject
            val handle = node["h"]?.jsonPrimitive?.contentOrNull() ?: return@forEach
            val type = node["t"]?.jsonPrimitive?.intOrNull ?: return@forEach
            val parent = node["p"]?.jsonPrimitive?.contentOrNull()
            val attr = node["a"]?.jsonPrimitive?.contentOrNull()
            val key = node["k"]?.jsonPrimitive?.contentOrNull()
                ?.let { rawKey -> MegaAccountCrypto.decryptNodeKey(rawKey, accountKey, attr) }
            val name = if (attr != null && key != null && key.size >= 16) {
                MegaAccountCrypto.decryptAttributes(attr, key.copyOfRange(0, 16))
                    ?.get("n")
                    ?.jsonPrimitive
                    ?.contentOrNull()
            } else null
            if (type == ROOT_NODE_TYPE) rootHandle = handle
            nodes[handle] = MegaNode(
                handle = handle,
                parent = parent,
                type = type,
                name = name,
                key = key
            )
        }
        if (rootHandle.isBlank()) throw BackupValidationException(R.string.backup_error_mega_no_root)
    }

    private fun resolveFolder(path: String, create: Boolean): String {
        if (rootHandle.isBlank()) throw BackupValidationException(R.string.backup_error_mega_not_connected)
        val segments = path.trim().trim('/').split('/').filter { it.isNotBlank() }
        var current = rootHandle
        for (segment in segments) {
            val existing = nodes.values.firstOrNull {
                it.type == FOLDER_NODE_TYPE && it.parent == current && it.name == segment
            }
            current = existing?.handle ?: if (create) {
                createFolder(segment, current)
            } else {
                throw BackupValidationException(R.string.backup_error_mega_folder_not_found, segments.joinToString("/"))
            }
        }
        return current
    }

    private fun createFolder(name: String, parent: String): String {
        val folderKey = MegaAccountCrypto.randomFolderKey()
        val attributes = MegaAccountCrypto.encryptAttributes(
            buildJsonObject { put("n", name) }.toString(),
            folderKey
        )
        val encryptedKey = MegaAccountCrypto.encryptKey(folderKey, requireMasterKey())
        val response = api(
            buildJsonObject {
                put("a", "p")
                put("t", parent)
                putJsonArray("n") {
                    add(
                        buildJsonObject {
                            put("h", "xxxxxxxx")
                            put("t", FOLDER_NODE_TYPE)
                            put("a", attributes)
                            put("k", encryptedKey)
                        }
                    )
                }
                put("i", requestId())
            }
        )[0]
        val handle = response.createdHandle()
            ?: throw BackupValidationException(R.string.backup_error_mega_created_folder_no_handle)
        nodes[handle] = MegaNode(
            handle = handle,
            parent = parent,
            type = FOLDER_NODE_TYPE,
            name = name,
            key = folderKey
        )
        return handle
    }

    private fun initUpload(sizeBytes: Long): String {
        val response = api(
            buildJsonObject {
                put("a", "u")
                put("s", sizeBytes)
            }
        )[0].jsonObject
        val uploadUrl = response["p"]?.jsonPrimitive?.contentOrNull()
            ?: throw BackupValidationException(R.string.backup_error_mega_no_upload_url)
        return validateMegaUploadUrl(uploadUrl)
    }

    private fun finishUpload(
        fileName: String,
        parent: String,
        completionHandle: String,
        uploadKey: IntArray,
        finalKey: IntArray
    ): String {
        val attributes = MegaAccountCrypto.encryptAttributes(
            buildJsonObject { put("n", fileName) }.toString(),
            MegaAccountCrypto.uploadAttributeKey(uploadKey)
        )
        val encryptedKey = MegaAccountCrypto.encryptKey(
            MegaAccountCrypto.intsToBytes(finalKey),
            requireMasterKey()
        )
        val response = api(
            buildJsonObject {
                put("a", "p")
                put("t", parent)
                putJsonArray("n") {
                    add(
                        buildJsonObject {
                            put("h", completionHandle)
                            put("t", FILE_NODE_TYPE)
                            put("a", attributes)
                            put("k", encryptedKey)
                        }
                    )
                }
                put("i", requestId())
            }
        )[0]
        val handle = response.createdHandle()
            ?: throw BackupValidationException(R.string.backup_error_mega_uploaded_no_handle)
        nodes[handle] = MegaNode(
            handle = handle,
            parent = parent,
            type = FILE_NODE_TYPE,
            name = fileName,
            key = MegaAccountCrypto.intsToBytes(finalKey)
        )
        return handle
    }

    private fun api(command: JsonObject, includeSession: Boolean = true): JsonArray =
        api(buildJsonArray { add(command) }, includeSession)

    private fun api(commands: JsonArray, includeSession: Boolean = true): JsonArray {
        val body = commands.toString().toByteArray(Charsets.UTF_8)
        val url = URL(buildApiUrl(includeSession))
        var hashcash: String? = null
        var solvedHashcashCount = 0
        repeat(MAX_API_ATTEMPTS) { attempt ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Content-type", "text/plain;charset=UTF-8")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", USER_AGENT)
                hashcash?.let { setRequestProperty("X-Hashcash", it) }
                setFixedLengthStreamingMode(body.size)
            }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status == HTTP_HASHCASH_REQUIRED) {
                    val challenge = connection.hashcashChallenge()
                        ?: connection.readResponseText(status).extractHashcashChallenge()
                    if (challenge.isNullOrBlank()) {
                        if (attempt == MAX_API_ATTEMPTS - 1) {
                            throw BackupValidationException(
                                R.string.backup_error_mega_hashcash_no_challenge,
                                connection.headerNames()
                            )
                        }
                        return@repeat
                    }
                    if (attempt == MAX_API_ATTEMPTS - 1) {
                        throw BackupValidationException(
                            R.string.backup_error_mega_hashcash_rejected,
                            solvedHashcashCount
                        )
                    }
                    drainError(connection)
                    hashcash = MegaAccountCrypto.solveHashcash(challenge, HASHCASH_TIMEOUT_MS)
                    solvedHashcashCount++
                    return@repeat
                }
                val responseText = connection.readResponseText(status)
                if (status !in 200..299) {
                    throw BackupValidationException("MEGA HTTP $status: ${responseText.take(160)}")
                }
                val response = json.parseToJsonElement(responseText).jsonArray
                response.throwIfMegaError()
                return response
            } finally {
                connection.disconnect()
            }
        }
        throw BackupValidationException(R.string.backup_error_mega_api_no_response)
    }

    private fun postUploadChunk(uploadUrl: String, total: Long, offset: Long, encrypted: ByteArray): String {
        val targetUrl = validateMegaUploadUrl(chunkUrl(uploadUrl, total, offset, encrypted.size.toLong()))
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            readTimeout = HTTP_READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", USER_AGENT)
            setFixedLengthStreamingMode(encrypted.size)
        }
        try {
            connection.outputStream.use { it.write(encrypted) }
            val status = connection.responseCode
            val text = connection.readResponseText(status)
            if (status !in 200..299) throw BackupValidationException("MEGA upload HTTP $status: ${text.take(160)}")
            text.trim().toIntOrNull()?.takeIf { it < 0 }?.let { throw megaApiException(it) }
            return text.trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun buildApiUrl(includeSession: Boolean): String {
        val sid = sessionId
        return buildString {
            append(API_URL).append("/cs?id=").append(sequence++).append("&ak=").append(APP_KEY)
            if (includeSession && !sid.isNullOrBlank()) append("&sid=").append(sid)
        }
    }

    private fun requireMasterKey(): ByteArray =
        masterKey ?: throw BackupValidationException(R.string.backup_error_mega_not_connected)

    private fun JsonArray.throwIfMegaError() {
        forEach { element ->
            val code = element.megaErrorCode()
            if (code != null && code < 0) throw megaApiException(code)
        }
    }

    private fun JsonElement.megaErrorCode(): Int? =
        when (this) {
            is kotlinx.serialization.json.JsonPrimitive -> jsonPrimitive.intOrNull
            is JsonArray -> firstOrNull()?.megaErrorCode()
            else -> null
        }

    private fun JsonElement.createdHandle(): String? {
        if (this is JsonArray) return firstOrNull()?.createdHandle()
        val obj = runCatching { jsonObject }.getOrNull() ?: return null
        obj["h"]?.jsonPrimitive?.contentOrNull()?.let { return it }
        val files = obj["f"]?.jsonArray ?: return null
        return files.firstOrNull()?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()

    private fun HttpURLConnection.readResponseText(status: Int): String {
        val stream = if (status in 200..299) inputStream else errorStream
        if (stream == null) return ""
        val decoded = if (contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
        return decoded.use { it.readFullyToString() }
    }

    private fun HttpURLConnection.hashcashChallenge(): String? {
        getHeaderField(HASHCASH_HEADER)?.takeIf { it.isNotBlank() }?.let { return it }
        return headerFields.entries
            .firstOrNull { (name, values) -> name.equals(HASHCASH_HEADER, ignoreCase = true) && values.isNotEmpty() }
            ?.value
            ?.firstOrNull { it.isNotBlank() }
    }

    private fun HttpURLConnection.headerNames(): String =
        headerFields.keys
            .filterNotNull()
            .joinToString()
            .ifBlank { text(R.string.backup_none) }

    private fun String.extractHashcashChallenge(): String? =
        HASHCASH_CHALLENGE_REGEX.find(this)?.value

    private fun drainError(connection: HttpURLConnection) {
        runCatching { connection.errorStream?.use { it.copyTo(ByteArrayOutputStream()) } }
    }

    private fun InputStream.readFullyToString(): String =
        ByteArrayOutputStream().use { output ->
            copyTo(output)
            output.toString(Charsets.UTF_8.name())
        }

    private fun InputStream.readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var readTotal = 0
        while (readTotal < size) {
            val read = read(bytes, readTotal, size - readTotal)
            if (read < 0) throw BackupValidationException(R.string.backup_error_mega_read_file)
            readTotal += read
        }
        return bytes
    }

    private fun Long.formatBytes(): String {
        if (this < 1024L) return "$this ${text(R.string.backup_unit_bytes)}"
        val units = arrayOf(
            text(R.string.backup_unit_kb),
            text(R.string.backup_unit_mb),
            text(R.string.backup_unit_gb),
            text(R.string.backup_unit_tb)
        )
        var value = this.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }

    private fun chunkUrl(uploadUrl: String, total: Long, offset: Long, size: Long): String =
        if (offset + size == total) "$uploadUrl/$offset" else "$uploadUrl/$offset-${offset + size - 1}"

    internal fun validateMegaUploadUrl(value: String): String {
        val url = runCatching { URL(value.trim()) }.getOrNull()
            ?: throw BackupValidationException(R.string.backup_error_mega_upload_https_required)
        val host = url.host.lowercase(Locale.US)
        val isMegaHost = host == "mega.nz" ||
            host.endsWith(".mega.nz") ||
            host == "mega.co.nz" ||
            host.endsWith(".mega.co.nz")
        val isMegaStorageHost = host == "userstorage.mega.nz" ||
            host.endsWith(".userstorage.mega.nz") ||
            host == "userstorage.mega.co.nz" ||
            host.endsWith(".userstorage.mega.co.nz")
        val protocol = url.protocol.lowercase(Locale.US)
        val isAllowedProtocol = protocol == "https" || (protocol == "http" && isMegaStorageHost)
        if (!isAllowedProtocol || !isMegaHost) {
            throw BackupValidationException(R.string.backup_error_mega_upload_https_required)
        }
        return url.toString().trimEnd('/')
    }

    private fun chunkOffset(chunkId: Long): Long {
        val offsets = longArrayOf(0, 128, 384, 768, 1280, 1920, 2688)
        val kb = if (chunkId <= 7) offsets[(chunkId - 1).toInt()] else 3584 + (chunkId - 8) * 1024
        return kb * 1024L
    }

    private fun chunkSize(chunkId: Long, total: Long, offset: Long): Long {
        val raw = if (chunkId in 1..7) chunkId * 128L * 1024L else 1024L * 1024L
        return min(raw, total - offset)
    }

    private fun requestId(): String =
        UUID.randomUUID().toString().replace("-", "").take(REQUEST_ID_LENGTH)

    private data class MegaNode(
        val handle: String,
        val parent: String?,
        val type: Int,
        val name: String?,
        val key: ByteArray?
    )

    private data class MegaChunkResult(
        val chunkId: Long,
        val plain: ByteArray,
        val response: String
    )

    private fun megaApiException(code: Int): BackupValidationException = BackupValidationException(
        when (code) {
            -1 -> text(R.string.backup_error_mega_api_unavailable)
            -2 -> text(R.string.backup_error_mega_api_internal)
            -3 -> text(R.string.backup_error_mega_api_busy)
            -6 -> text(R.string.backup_error_mega_api_rate_limit)
            -8 -> text(R.string.backup_error_mega_api_exists)
            -9 -> text(R.string.backup_error_mega_api_not_found)
            -11 -> text(R.string.backup_error_mega_api_blocked)
            -14 -> text(R.string.backup_error_mega_api_bad_credentials)
            -15 -> text(R.string.backup_error_mega_api_no_folder_access)
            -16 -> text(R.string.backup_error_mega_api_session_expired)
            -17 -> text(R.string.backup_error_mega_api_transfer_limit)
            -18 -> text(R.string.backup_error_mega_api_no_space)
            -26 -> text(R.string.backup_error_mega_api_2fa_required)
            else -> "MEGA API error $code"
        }
    )

    private fun text(@StringRes resId: Int, vararg args: Any): String {
        context?.let { return it.getString(resId, *args) }
        val template = when (resId) {
            R.string.backup_progress_mega_empty_uploaded -> "MEGA: empty file uploaded"
            R.string.backup_progress_mega_parallel -> "MEGA: %1\$d threads, uploaded %2\$s of %3\$s"
            R.string.backup_none -> "none"
            R.string.backup_unit_bytes -> "B"
            R.string.backup_unit_kb -> "KB"
            R.string.backup_unit_mb -> "MB"
            R.string.backup_unit_gb -> "GB"
            R.string.backup_unit_tb -> "TB"
            R.string.backup_error_mega_api_unavailable -> "MEGA is temporarily unavailable, try again later"
            R.string.backup_error_mega_api_internal -> "MEGA returned an internal error"
            R.string.backup_error_mega_api_busy -> "MEGA is busy, try again later"
            R.string.backup_error_mega_api_rate_limit -> "MEGA rate limit exceeded"
            R.string.backup_error_mega_api_exists -> "MEGA: file or folder already exists"
            R.string.backup_error_mega_api_not_found -> "MEGA could not find the account, folder, or file. Check email/password and path."
            R.string.backup_error_mega_api_blocked -> "MEGA blocked access to the account"
            R.string.backup_error_mega_api_bad_credentials -> "MEGA: incorrect email or password"
            R.string.backup_error_mega_api_no_folder_access -> "MEGA: no access to the selected folder"
            R.string.backup_error_mega_api_session_expired -> "MEGA: session expired"
            R.string.backup_error_mega_api_transfer_limit -> "MEGA: transfer limit exceeded"
            R.string.backup_error_mega_api_no_space -> "MEGA: not enough account storage"
            R.string.backup_error_mega_api_2fa_required -> "MEGA requires a two-factor code or rejected password-only login"
            else -> "Resource $resId"
        }
        return if (args.isEmpty()) template else String.format(java.util.Locale.US, template, *args)
    }

    companion object {
        private const val API_URL = "https://g.api.mega.co.nz"
        private const val APP_KEY = "BdARkQSQ"
        private const val USER_AGENT = "Arcanum Android"
        private const val MEGA_PARALLEL_CHUNKS = 6
        private const val MEGA_PARALLEL_CHUNKS_FOR_SAF = 6
        private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
        private const val HTTP_READ_TIMEOUT_MS = 90_000
        private const val HTTP_HASHCASH_REQUIRED = 402
        private const val MAX_API_ATTEMPTS = 8
        private const val HASHCASH_TIMEOUT_MS = 5L * 60_000L
        private const val HASHCASH_HEADER = "X-Hashcash"
        private const val REQUEST_ID_LENGTH = 10
        private const val FILE_NODE_TYPE = 0
        private const val FOLDER_NODE_TYPE = 1
        private const val ROOT_NODE_TYPE = 2
        private val HASHCASH_CHALLENGE_REGEX = Regex("""1:\d{1,3}:\d+:[A-Za-z0-9_-]{64}""")
    }
}
