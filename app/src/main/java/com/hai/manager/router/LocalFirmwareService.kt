package com.hai.manager.router

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Local firmware flow selected from Android's document picker.
 *
 * HAI MANAGER never guesses a hidden flashing endpoint. A local file can be sent only when:
 * 1) the package can be tied to the connected model/hardware (catalog SHA-256 match or package evidence), and
 * 2) the router's authenticated WebUI itself advertises a multipart firmware/update form.
 */
data class LocalFirmwareSelection(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val candidate: FirmwareCandidate,
    val uploadTarget: LocalFirmwareUploadTarget?,
    val catalogVerified: Boolean,
    val modelEvidence: Boolean,
    val hardwareEvidence: Boolean,
    val statusMessage: String
)

data class LocalFirmwareUploadTarget(
    val actionUrl: String,
    val fieldName: String,
    val hiddenFields: Map<String, String>,
    val sourcePage: String
)

class LocalFirmwareService(private val context: Context) {
    suspend fun prepare(
        inspection: RouterInspection,
        catalogJson: String?,
        uri: Uri,
        onProgress: suspend (Int, String) -> Unit
    ): LocalFirmwareSelection = withContext(Dispatchers.IO) {
        onProgress(5, "قراءة الملف")
        val meta = documentMeta(uri)
        require(meta.sizeBytes > 0) { "تعذر قراءة حجم ملف الـFirmware" }
        require(meta.sizeBytes >= 512 * 1024) { "الملف صغير جدًا ليكون Firmware صالحًا" }

        val extension = meta.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("bin", "zip", "img", "upd", "fw", "tar", "trx")) {
            "امتداد الملف غير معروف كحزمة Firmware"
        }

        onProgress(18, "حساب SHA-256")
        val digest = sha256(uri) { done ->
            val ratio = if (meta.sizeBytes <= 0) 0.0 else done.toDouble() / meta.sizeBytes.toDouble()
            val mapped = (18 + ratio * 22).toInt().coerceIn(18, 40)
            onProgress(mapped, "التحقق من سلامة الملف")
        }

        onProgress(43, "قراءة هوية الحزمة")
        val evidenceText = scanPackageEvidence(uri, meta.fileName)
        val device = inspection.device
        val brand = inspection.snapshot.brand.displayName
        val model = device?.model.orEmpty()
        val hardware = device?.hardwareVersion.orEmpty()
        val current = device?.firmwareVersion.orEmpty()

        val searchable = (meta.fileName + "\n" + evidenceText).uppercase(Locale.ROOT)
        val normalizedModel = normalizeToken(model)
        val normalizedHardware = normalizeToken(hardware)
        val normalizedSearchable = normalizeToken(searchable)
        val modelEvidence = normalizedModel.isNotBlank() && normalizedSearchable.contains(normalizedModel)
        val hardwareEvidence = normalizedHardware.isBlank() || normalizedSearchable.contains(normalizedHardware)

        onProgress(55, "مطابقة الملف بقاعدة HAI")
        val catalogMatch = findCatalogMatch(
            catalogJson = catalogJson,
            sha256 = digest,
            brand = brand,
            model = model,
            hardware = hardware,
            currentFirmware = current
        )

        onProgress(68, "فحص التحديث المحلي في الراوتر")
        val uploadTarget = discoverUploadTarget(inspection.snapshot.managementUrl)

        val targetVersion = catalogMatch?.optString("version")?.takeIf { it.isNotBlank() }
            ?: inferVersion(meta.fileName, evidenceText)
            ?: "ملف محلي"
        val catalogVerified = catalogMatch?.optBoolean("verified", false) == true
        val packageIdentityVerified = catalogVerified || (modelEvidence && hardwareEvidence)
        val installable = packageIdentityVerified && uploadTarget != null

        val summary = when {
            catalogVerified && uploadTarget != null ->
                "تطابق SHA-256 مع حزمة موثقة، والراوتر يعلن مسار تحديث محلي. الملف جاهز للرفع بعد إعادة التحقق."
            packageIdentityVerified && uploadTarget != null ->
                "تم العثور داخل الحزمة على هوية مطابقة للموديل/Hardware، والراوتر يعلن مسار تحديث محلي. سيعاد التحقق قبل الرفع."
            uploadTarget == null ->
                "تم فحص الملف، لكن Firmware الحالي لا يعلن مسار Local Upgrade يمكن استخدامه بأمان من التطبيق."
            !modelEvidence ->
                "لم يستطع التطبيق إثبات أن الحزمة تخص الموديل $model. لن يرسل الملف إلى الراوتر."
            else ->
                "الملف غير موثق بما يكفي لهذا Hardware. لن يبدأ التثبيت حتى يثبت التطابق."
        }

        val candidate = FirmwareCandidate(
            currentVersion = current,
            version = targetVersion,
            size = formatBytes(meta.sizeBytes),
            source = FirmwareSearchSource.COMPANIES,
            sourceLabel = "ملف من الجوال",
            summaryArabic = summary,
            installable = installable,
            installMode = "local_upload",
            brand = brand,
            model = model.takeIf { it.isNotBlank() },
            hardware = hardware.takeIf { it.isNotBlank() },
            requiredCurrentFirmware = catalogMatch?.optString("currentFirmwareContains")?.takeIf { it.isNotBlank() },
            sha256 = digest
        )

        onProgress(100, if (installable) "الملف جاهز" else "اكتمل فحص الملف")
        LocalFirmwareSelection(
            uri = uri,
            fileName = meta.fileName,
            sizeBytes = meta.sizeBytes,
            sha256 = digest,
            candidate = candidate,
            uploadTarget = uploadTarget,
            catalogVerified = catalogVerified,
            modelEvidence = modelEvidence,
            hardwareEvidence = hardwareEvidence,
            statusMessage = summary
        )
    }

    suspend fun execute(
        inspection: RouterInspection,
        selection: LocalFirmwareSelection,
        onProgress: suspend (Int, String) -> Unit
    ): RouterActionResult = withContext(Dispatchers.IO) {
        val target = selection.uploadTarget
            ?: return@withContext RouterActionResult(false, "الراوتر لا يعلن مسار تحديث محلي موثق")
        if (!selection.candidate.installable) {
            return@withContext RouterActionResult(false, "الملف لم يجتز فحص التوافق")
        }

        val compatibility = RouterFirmwareService().verifyCompatibility(inspection, selection.candidate, onProgress)
        if (!compatibility.compatible && !compatibility.message.contains("ملف", true)) {
            return@withContext RouterActionResult(false, compatibility.message)
        }

        onProgress(12, "إعادة حساب SHA-256")
        val secondDigest = sha256(selection.uri) { done ->
            val ratio = done.toDouble() / selection.sizeBytes.coerceAtLeast(1).toDouble()
            onProgress((12 + ratio * 13).toInt().coerceIn(12, 25), "إعادة التحقق من الملف")
        }
        if (!secondDigest.equals(selection.sha256, ignoreCase = true)) {
            return@withContext RouterActionResult(false, "تغير الملف بعد اختياره. أعد اختيار الحزمة")
        }

        onProgress(28, "رفع Firmware إلى الراوتر")
        val upload = uploadMultipart(selection, target, onProgress)
        if (!upload.success) return@withContext RouterActionResult(false, upload.message)

        val oldVersion = inspection.device?.firmwareVersion.orEmpty()
        onProgress(90, "بانتظار إعادة تشغيل الراوتر")
        for (attempt in 0 until 120) {
            delay(2_000)
            val refreshed = runCatching { RouterInspectorService().inspect(inspection.snapshot) }.getOrNull()
            val newVersion = refreshed?.device?.firmwareVersion.orEmpty()
            if (newVersion.isNotBlank() && !newVersion.equals(oldVersion, ignoreCase = true)) {
                onProgress(100, "اكتمل التحديث")
                return@withContext RouterActionResult(true, "تم تحديث الراوتر إلى $newVersion")
            }
            if (attempt % 10 == 0) {
                onProgress(92 + (attempt / 30).coerceAtMost(6), "بانتظار عودة الراوتر")
            }
        }

        RouterActionResult(
            true,
            "قبل الراوتر ملف الـFirmware وبدأت العملية، لكن لم يتمكن HAI MANAGER من تأكيد الإصدار الجديد بعد. أعد الفحص بعد عودة الراوتر."
        )
    }

    private data class DocumentMeta(val fileName: String, val sizeBytes: Long)

    private fun documentMeta(uri: Uri): DocumentMeta {
        var name = "firmware.bin"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size <= 0) {
            size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        return DocumentMeta(name, size)
    }

    private suspend fun sha256(uri: Uri, onBytes: suspend (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.buffered(128 * 1024)?.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
                onBytes(total)
            }
        } ?: error("تعذر فتح ملف الـFirmware")
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun scanPackageEvidence(uri: Uri, fileName: String): String {
        return if (fileName.endsWith(".zip", ignoreCase = true)) {
            buildString {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        var entries = 0
                        while (entries < 40) {
                            val entry = zip.nextEntry ?: break
                            entries++
                            val entryName = entry.name.lowercase(Locale.ROOT)
                            if (!entry.isDirectory && Regex("manifest|version|info|meta|config|package|update|build").containsMatchIn(entryName)) {
                                val buffer = ByteArray(128 * 1024)
                                val read = zip.read(buffer)
                                if (read > 0) append(String(buffer, 0, read, Charsets.ISO_8859_1)).append('\n')
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(2 * 1024 * 1024)
                val read = input.read(buffer)
                if (read > 0) String(buffer, 0, read, Charsets.ISO_8859_1) else ""
            }.orEmpty()
        }
    }

    private fun findCatalogMatch(
        catalogJson: String?,
        sha256: String,
        brand: String,
        model: String,
        hardware: String,
        currentFirmware: String
    ): JSONObject? {
        val root = catalogJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val updates = root.optJSONArray("firmwareUpdates") ?: return null
        for (index in 0 until updates.length()) {
            val item = updates.optJSONObject(index) ?: continue
            if (!item.optString("brand").equals(brand, true)) continue
            if (!item.optString("sha256").equals(sha256, true)) continue
            val modelMatch = item.optString("modelContains")
            if (modelMatch.isNotBlank() && !model.contains(modelMatch, true)) continue
            val hardwareMatch = item.optString("hardwareContains")
            if (hardwareMatch.isNotBlank() && !hardware.contains(hardwareMatch, true)) continue
            val currentMatch = item.optString("currentFirmwareContains")
            if (currentMatch.isNotBlank() && !currentFirmware.contains(currentMatch, true)) continue
            return item
        }
        return null
    }

    private fun inferVersion(fileName: String, evidenceText: String): String? {
        val haystack = "$fileName\n${evidenceText.take(200_000)}"
        val full = Regex("(?i)BD_[A-Z0-9_\\.-]+B\\d{1,4}").find(haystack)?.value
        if (!full.isNullOrBlank()) return full
        return Regex("(?i)(?:^|[^A-Z0-9])B(\\d{1,4})(?:[^A-Z0-9]|$)")
            .find(haystack)?.groupValues?.getOrNull(1)?.let { "B$it" }
    }

    private fun discoverUploadTarget(baseUrl: String?): LocalFirmwareUploadTarget? {
        if (baseUrl.isNullOrBlank()) return null
        val base = runCatching { URL(baseUrl) }.getOrNull() ?: return null
        val pages = listOf("/", "/index.html", "/html/update.html", "/html/upgrade.html", "/html/firmware.html")
        for (page in pages) {
            val pageUrl = URL(base, page)
            val html = fetchHtml(pageUrl) ?: continue
            val formRegex = Regex("(?is)<form\\b([^>]*)>(.*?)</form>")
            for (match in formRegex.findAll(html)) {
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                val enctype = attr(attrs, "enctype").lowercase(Locale.ROOT)
                if (!enctype.contains("multipart/form-data")) continue
                val method = attr(attrs, "method").ifBlank { "post" }
                if (!method.equals("post", true)) continue
                val action = attr(attrs, "action")
                if (action.isBlank()) continue
                val lowerAction = action.lowercase(Locale.ROOT)
                if (listOf("firmware", "upgrade", "update", "upload").none { it in lowerAction }) continue

                val fileInput = Regex("(?is)<input\\b([^>]*type\\s*=\\s*['\"]?file['\"]?[^>]*)>")
                    .find(body)?.groupValues?.getOrNull(1) ?: continue
                val fieldName = attr(fileInput, "name").ifBlank { "file" }
                val actionUrl = runCatching { URL(pageUrl, action) }.getOrNull() ?: continue
                if (!actionUrl.host.equals(base.host, true)) continue
                if (actionUrl.protocol !in setOf("http", "https")) continue

                val hidden = linkedMapOf<String, String>()
                Regex("(?is)<input\\b([^>]*)>").findAll(body).forEach { input ->
                    val inputAttrs = input.groupValues[1]
                    if (!attr(inputAttrs, "type").equals("hidden", true)) return@forEach
                    val name = attr(inputAttrs, "name")
                    if (name.isNotBlank()) hidden[name] = attr(inputAttrs, "value")
                }
                return LocalFirmwareUploadTarget(
                    actionUrl = actionUrl.toString(),
                    fieldName = fieldName,
                    hiddenFields = hidden,
                    sourcePage = pageUrl.toString()
                )
            }
        }
        return null
    }

    private fun fetchHtml(url: URL): String? {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HAI-MANAGER/0.18")
            CookieManager.getInstance().getCookie(url.toString())?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..399) return null
            connection.inputStream.bufferedReader().use { it.readText().take(800_000) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private data class UploadResult(val success: Boolean, val message: String)

    private suspend fun uploadMultipart(
        selection: LocalFirmwareSelection,
        target: LocalFirmwareUploadTarget,
        onProgress: suspend (Int, String) -> Unit
    ): UploadResult {
        val url = URL(target.actionUrl)
        val boundary = "----HAIManager${System.currentTimeMillis()}"
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 10 * 60_000
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            instanceFollowRedirects = true
            setChunkedStreamingMode(128 * 1024)
            setRequestProperty("User-Agent", "HAI-MANAGER/0.18")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Referer", target.sourcePage)
            CookieManager.getInstance().getCookie(url.toString())?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }

        return try {
            BufferedOutputStream(connection.outputStream, 128 * 1024).use { output ->
                for ((name, value) in target.hiddenFields) writeTextPart(output, boundary, name, value)
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"${escapeHeader(target.fieldName)}\"; filename=\"${escapeHeader(selection.fileName)}\"\r\n".toByteArray()
                )
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())

                var sent = 0L
                context.contentResolver.openInputStream(selection.uri)?.buffered(128 * 1024)?.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        val ratio = sent.toDouble() / selection.sizeBytes.coerceAtLeast(1).toDouble()
                        val progress = (30 + ratio * 55).toInt().coerceIn(30, 85)
                        onProgress(progress, "رفع الملف ${(ratio * 100).toInt().coerceIn(0, 100)}%")
                    }
                } ?: return UploadResult(false, "تعذر فتح الملف للرفع")
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.flush()
            }

            val code = connection.responseCode
            val body = runCatching {
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText().take(200_000) }.orEmpty()
            }.getOrDefault("")
            val obviousError = Regex("(?i)invalid|incompatible|wrong firmware|signature error|upgrade failed|update failed")
                .containsMatchIn(body)
            if (code !in 200..399 || obviousError) {
                UploadResult(false, "رفض الراوتر ملف الـFirmware أو فشل التحقق منه")
            } else {
                onProgress(88, "تم رفع الملف")
                UploadResult(true, "تم رفع الملف")
            }
        } catch (e: Exception) {
            UploadResult(false, e.message ?: "فشل رفع ملف الـFirmware")
        } finally {
            connection.disconnect()
        }
    }

    private fun writeTextPart(output: OutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"${escapeHeader(name)}\"\r\n\r\n".toByteArray())
        output.write(value.toByteArray())
        output.write("\r\n".toByteArray())
    }

    private fun attr(attributes: String, name: String): String {
        val quoted = Regex("(?is)\\b${Regex.escape(name)}\\s*=\\s*(['\"])(.*?)\\1").find(attributes)
        if (quoted != null) return quoted.groupValues[2]
        return Regex("(?is)\\b${Regex.escape(name)}\\s*=\\s*([^\\s>]+)")
            .find(attributes)?.groupValues?.getOrNull(1).orEmpty().trim('"', '\'')
    }

    private fun normalizeToken(value: String): String = value.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun escapeHeader(value: String): String = value.replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
