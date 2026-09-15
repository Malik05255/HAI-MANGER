package com.hai.manager.unlock

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.abs

enum class UnlockBrand(val displayName: String) {
    AUTO("تلقائي"),
    HUAWEI("Huawei"),
    ZTE("ZTE")
}

enum class UnlockGeneration(val displayName: String) {
    LEGACY_3G_4G("3G / 4G قديم"),
    FOUR_G_HILINK("4G / 4G+"),
    FIVE_G("5G"),
    UNKNOWN("غير معروف")
}

enum class UnlockConfidence(val displayName: String) {
    VERIFIED("موثق"),
    FAMILY_ONLY("خاص بعائلة محددة"),
    DIAGNOSTICS_ONLY("تشخيص فقط")
}

data class UnlockCodeCandidate(
    val label: String,
    val code: String,
    val family: String,
    val confidence: UnlockConfidence,
    val note: String
)

data class ImeiUnlockReport(
    val imei: String,
    val formatValid: Boolean,
    val luhnValid: Boolean,
    val brand: UnlockBrand,
    val generation: UnlockGeneration,
    val family: String,
    val codes: List<UnlockCodeCandidate>,
    val warning: String,
    val sourceNotes: List<String>
)

/**
 * Offline IMEI unlock research engine.
 *
 * Important: a calculated code is only returned for algorithm families that are publicly
 * documented and reproducible. Modern Huawei/ZTE 5G devices are deliberately classified as
 * diagnostics-only until an IMEI-only derivation is independently verified for that family.
 */
object ImeiUnlockEngine {

    fun analyze(
        rawImei: String,
        brandHint: UnlockBrand = UnlockBrand.AUTO,
        modelHint: String? = null
    ): ImeiUnlockReport {
        val imei = rawImei.filter(Char::isDigit).take(15)
        val formatValid = imei.length == 15
        val luhnValid = formatValid && isValidLuhn(imei)
        if (!formatValid) {
            return ImeiUnlockReport(
                imei = imei,
                formatValid = false,
                luhnValid = false,
                brand = brandHint,
                generation = UnlockGeneration.UNKNOWN,
                family = "غير محدد",
                codes = emptyList(),
                warning = "IMEI يجب أن يتكون من 15 رقمًا.",
                sourceNotes = emptyList()
            )
        }

        val model = modelHint.orEmpty().uppercase()
        val brand = when {
            brandHint != UnlockBrand.AUTO -> brandHint
            model.startsWith("H") || model.startsWith("B") || model.startsWith("E") -> UnlockBrand.HUAWEI
            model.startsWith("MC") || model.startsWith("MF") || model.contains("ZTE") -> UnlockBrand.ZTE
            else -> UnlockBrand.AUTO
        }

        return when (brand) {
            UnlockBrand.HUAWEI -> analyzeHuawei(imei, model, luhnValid)
            UnlockBrand.ZTE -> analyzeZte(imei, model, luhnValid)
            UnlockBrand.AUTO -> ImeiUnlockReport(
                imei = imei,
                formatValid = true,
                luhnValid = luhnValid,
                brand = UnlockBrand.AUTO,
                generation = UnlockGeneration.UNKNOWN,
                family = "لم يتم تحديد Huawei أو ZTE",
                codes = emptyList(),
                warning = "حدد الشركة أو افتح الأداة من صفحة الراوتر ليتم تمرير الشركة والموديل تلقائيًا.",
                sourceNotes = listOf("لا يتم تخمين الشركة من IMEI دون قاعدة TAC موثقة.")
            )
        }
    }

    private fun analyzeHuawei(imei: String, model: String, luhnValid: Boolean): ImeiUnlockReport {
        val modern5g = model.startsWith("H112") || model.startsWith("H122") ||
            model.startsWith("H138") || model.startsWith("H155") || model.startsWith("H158")
        val hilink4g = model.startsWith("B310") || model.startsWith("B315") ||
            model.startsWith("B525") || model.startsWith("B535") || model.startsWith("B612") ||
            model.startsWith("B618") || model.startsWith("B628") || model.startsWith("B715") ||
            model.startsWith("B818")

        if (modern5g) {
            return diagnosticsOnly(
                imei, UnlockBrand.HUAWEI, UnlockGeneration.FIVE_G,
                family = "Huawei 5G CPE الحديث",
                luhnValid = luhnValid,
                note = "لا توجد خوارزمية IMEI→NCK مفتوحة وموثقة لهذه العائلة؛ لا يتم تطبيق V1/V2/V201 عليها تلقائيًا."
            )
        }
        if (hilink4g) {
            return diagnosticsOnly(
                imei, UnlockBrand.HUAWEI, UnlockGeneration.FOUR_G_HILINK,
                family = "Huawei HiLink 4G/4G+",
                luhnValid = luhnValid,
                note = "خوارزميات Huawei القديمة موجودة، لكن لا يمكن تعميمها بأمان على كل B-series بدون Profile/TAC موثق."
            )
        }

        val codes = HuaweiLegacyAlgorithms.calculateAll(imei)
        return ImeiUnlockReport(
            imei = imei,
            formatValid = true,
            luhnValid = luhnValid,
            brand = UnlockBrand.HUAWEI,
            generation = UnlockGeneration.LEGACY_3G_4G,
            family = if (model.isBlank()) "Huawei legacy algorithm set" else "Huawei $model legacy candidate",
            codes = listOf(
                UnlockCodeCandidate("NCK V1", codes.v1, "Huawei V1", UnlockConfidence.VERIFIED, "خوارزمية Huawei V1 العامة للأجيال القديمة."),
                UnlockCodeCandidate("NCK V2", codes.v2, "Huawei V2", UnlockConfidence.VERIFIED, "يستخدم selector داخليًا لاختيار واحدة من 7 خوارزميات."),
                UnlockCodeCandidate("NCK V201", codes.v201, "Huawei V201/V3", UnlockConfidence.VERIFIED, "خوارزمية V201 للأجيال التي تستخدمها فعليًا."),
                UnlockCodeCandidate("Flash Code", codes.flash, "Huawei Flash", UnlockConfidence.VERIFIED, "ليس NCK؛ يظهر منفصلًا حتى لا يُستخدم مكان كود الشبكة.")
            ),
            warning = "لا تجرب أكثر من كود على جهاز محدود المحاولات. استخدم الإصدار المطابق لعائلة المودم فقط.",
            sourceNotes = listOf(
                "الخوارزميات من عائلة forth32/huaweicalc وkenshaw/huaweihash.",
                "تمت إضافة متجهات اختبار مرجعية للمحرك."
            )
        )
    }

    private fun analyzeZte(imei: String, model: String, luhnValid: Boolean): ImeiUnlockReport {
        val modern5g = model.startsWith("MC801") || model.startsWith("MC888") ||
            model.startsWith("MC889") || model.startsWith("MC7010") || model.startsWith("MU5")
        val modern4g = model.startsWith("MF286") || model.startsWith("MF289") || model.startsWith("MF297")
        val zx297520 = model.contains("H220M") || model.contains("ZX297520V3")

        if (modern5g) {
            return diagnosticsOnly(
                imei, UnlockBrand.ZTE, UnlockGeneration.FIVE_G,
                family = "ZTE Qualcomm 5G / goform",
                luhnValid = luhnValid,
                note = "MC801A/MC888/MC889/MC7010 لا تُعامل كـZX297520V3. يدعم HAI تشخيص NCK/goform، لكن IMEI-only غير مثبت."
            )
        }
        if (modern4g) {
            return diagnosticsOnly(
                imei, UnlockBrand.ZTE, UnlockGeneration.FOUR_G_HILINK,
                family = "ZTE MF 4G / goform",
                luhnValid = luhnValid,
                note = "يمكن قراءة حالة القفل ومحاولات NCK في بعض الـFirmware، لكن توليد الكود من IMEI غير معمم على هذه العائلة."
            )
        }

        val code = ZteZx297520v3Algorithm.calculate(imei)
        return ImeiUnlockReport(
            imei = imei,
            formatValid = true,
            luhnValid = luhnValid,
            brand = UnlockBrand.ZTE,
            generation = UnlockGeneration.LEGACY_3G_4G,
            family = if (zx297520) "ZTE ZX297520V3" else "ZTE ZX297520V3 candidate only",
            codes = listOf(
                UnlockCodeCandidate(
                    label = "NCK ZX297520V3",
                    code = code,
                    family = "ZX297520V3",
                    confidence = if (zx297520) UnlockConfidence.VERIFIED else UnlockConfidence.FAMILY_ONLY,
                    note = if (zx297520) "الموديل يطابق عائلة الخوارزمية." else "لا تستخدم هذا الكود إلا إذا تأكدت أن الجهاز مبني على ZX297520V3."
                )
            ),
            warning = if (zx297520) {
                "تحقق من عدد المحاولات المتبقية قبل الإدخال."
            } else {
                "هذه الخوارزمية ليست لـMC801A/MC888 أو Qualcomm SDX55/62/75. لا تدخل الكود لمجرد أن الجهاز ZTE."
            },
            sourceNotes = listOf("الخوارزمية مستندة إلى kozik47/zte-imei-unlock ومقيدة بعائلة ZX297520V3.")
        )
    }

    private fun diagnosticsOnly(
        imei: String,
        brand: UnlockBrand,
        generation: UnlockGeneration,
        family: String,
        luhnValid: Boolean,
        note: String
    ) = ImeiUnlockReport(
        imei = imei,
        formatValid = true,
        luhnValid = luhnValid,
        brand = brand,
        generation = generation,
        family = family,
        codes = emptyList(),
        warning = note,
        sourceNotes = listOf("يمنع HAI توليد أكواد تجريبية للأجهزة الحديثة حفاظًا على عداد NCK.")
    )

    fun isValidLuhn(imei: String): Boolean {
        if (imei.length != 15 || !imei.all(Char::isDigit)) return false
        var sum = 0
        imei.forEachIndexed { index, ch ->
            var n = ch.digitToInt()
            if (index % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return sum % 10 == 0
    }
}

data class HuaweiLegacyCodes(val v1: String, val v2: String, val v201: String, val flash: String)

internal object HuaweiLegacyAlgorithms {
    fun calculateAll(imei: String): HuaweiLegacyCodes = HuaweiLegacyCodes(
        v1 = encryptV1(imei, "hwe620datacard"),
        v2 = calculateV2Family(imei, 2),
        v201 = calculateV2Family(imei, 201),
        flash = encryptV1(imei, "e630upgrade")
    )

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
    private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    private fun encryptV1(imei: String, key: String): String {
        val keyHex = md5(key.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val salt = keyHex.substring(8, 24)
        val digest = md5((imei + salt).lowercase().toByteArray(Charsets.US_ASCII))
        var code = 0L
        for (i in 0..3) {
            val digit = (digest[i].toInt() and 0xff) xor (digest[i + 4].toInt() and 0xff) xor
                (digest[i + 8].toInt() and 0xff) xor (digest[i + 12].toInt() and 0xff)
            code += digit.toLong() shl ((3 - i) * 8)
        }
        return ((code and 0x1ffffffL) or 0x2000000L).toString()
    }

    private fun calculateV2Family(imei: String, version: Int): String {
        return when (procIndex(imei, version)) {
            0 -> algo1(imei, version)
            1 -> algo2(imei, version)
            2 -> algo3(imei, version)
            3 -> algo4(imei, version)
            4 -> if (version == 201) algo6(imei, 5) else algo5(imei)
            5 -> if (version == 201) algo6(imei, 6) else algo6(imei, 2)
            else -> algo7(imei, version)
        }
    }

    private fun procIndex(imei: String, version: Int): Int {
        var sum = 0L
        imei.forEachIndexed { zeroIndex, ch ->
            val i = zeroIndex + 1
            val value = ch.code.toLong()
            sum += if (version == 201) (value + i) * value * (value + 313) else (value + i) * i
        }
        return (sum % 7L).toInt()
    }

    private fun algo1(imei: String, version: Int): String {
        val key2 = longArrayOf(
            0x01966A9,0x021058F,0x02AEDA9,0x037CE91,0x0488C9F,0x05E507D,0x07A9BE5,0x09F644B,
            0x0CF35A1,0x10D5F55,0x15E2F25,0x1C73D6B,0x24FCFDD,0x3015B47,0x3E829E9,0x5143685
        )
        val key201 = longArrayOf(
            0x06E9C2A,0x3CA2B3C,0x01080DC,0x30855EE,0x3D3283A,0x2F4F85A,0x1F8808E,0x3147D10,
            0x34BBBB5,0x29EEADD,0x2318616,0x50F3ADC,0x0D11F38,0x2123BD2,0x4276C86,0x355CAAD
        )
        val key = if (version == 201) key201 else key2
        var sum = 0L
        imei.forEachIndexed { index, ch -> sum = (sum + ch.code.toLong() * key[index]) and 0xffffffffL }
        val out = IntArray(8) { i -> ((sum ushr (i * 4)) and 0xf).toInt() % 10 }
        if (out[0] == 0) out[0] = 1
        return out.joinToString("")
    }

    private fun algo2(imei: String, version: Int): String {
        val value: Int = if (version == 201) {
            val crc = customCrc201(imei)
            abs((crc.inv() and 0xffffffffL).toInt())
        } else {
            val crc = CRC32().apply { update(imei.toByteArray(Charsets.US_ASCII)) }.value
            abs(crc.toInt())
        }
        if (value == 0) return "99999999"
        val chars = value.toString().takeLast(8).padStart(8, '9').toCharArray()
        if (chars[0] == '0') chars[0] = '9'
        return String(chars)
    }

    private fun algo3(imei: String, version: Int): String {
        val digest = md5(imei.toByteArray(Charsets.US_ASCII))
        val offset = if (version == 201) 5 else 0
        val bytes = IntArray(8) { digest[offset + it].toInt() and 0xff }
        val first = bytes[0] % 10
        bytes[0] = if (first == 0) 0x35 else 0x30 + first
        return bytes.joinToString("") { b -> if (b in 0x30..0x39) b.toChar().toString() else (b % 10).toString() }
    }

    private fun algo4(imei: String, version: Int): String {
        val key = if (version == 201) "dfkdkfllekkodk" else "hwideadatacard"
        val salt = md5(key.toByteArray(Charsets.US_ASCII))
        val digest = md5(imei.toByteArray(Charsets.US_ASCII) + salt)
        var code = 0L
        for (i in 0..3) {
            val d = (digest[i].toInt() and 0xff) xor (digest[i + 4].toInt() and 0xff) xor
                (digest[i + 8].toInt() and 0xff) xor (digest[i + 12].toInt() and 0xff)
            code = (code shl 8) or (d.toLong() and 0xff)
        }
        return ((code and 0x1ffffffL) or 0x2000000L).toString()
    }

    private fun algo5(imei: String): String {
        val table = "5739146280098765432112345678905\u0000"
        val source = imei + "Z"
        val result = IntArray(8) { i ->
            val d = (source[i].code xor source[i + 8].code) and 0xff
            table[(d ushr 4) + (d and 0x0f)].digitToInt()
        }
        if (result[0] == 0) result[0] = result.indexOfFirst { it != 0 }.coerceAtLeast(0)
        return result.joinToString("")
    }

    private fun algo6(imei: String, version: Int): String {
        val digest = sha1(imei.toByteArray(Charsets.US_ASCII))
        val ints = (0 until 5).map { index ->
            val offset = index * 4
            val value = ((digest[offset].toLong() and 0xff) shl 24) or
                ((digest[offset + 1].toLong() and 0xff) shl 16) or
                ((digest[offset + 2].toLong() and 0xff) shl 8) or
                (digest[offset + 3].toLong() and 0xff)
            value.toString()
        }
        val value = when (version) {
            5 -> ints[1] + ints[4]
            6 -> ints[2] + ints[3]
            else -> ints[0] + ints[1]
        }
        return value.take(8).padEnd(8, '0')
    }

    private fun algo7(imei: String, version: Int): String {
        val key2 = intArrayOf(0x01,0x01,0x02,0x03,0x05,0x08,0x0D,0x15,0x22,0x37,0x59,0x90)
        val key201 = intArrayOf(0x0B,0x0D,0x11,0x13,0x17,0x1D,0x1F,0x25,0x29,0x2B,0x3B,0x61)
        val key = if (version == 201) key201 else key2
        val transformed = IntArray(15)
        imei.forEachIndexed { i, ch ->
            val d = ch.code
            transformed[i] = when (i % 3) {
                0 -> ((d shl 6) or (d ushr 2)) and 0xff
                1 -> ((d shl 5) or (d ushr 3)) and 0xff
                else -> ((d ushr 4) or (d shl 4)) and 0xff
            }
        }
        var hsum = 0
        for (i in 0..6) hsum += transformed[14 - i] + (transformed[i] shl 8)
        hsum += transformed[8]
        val buf = IntArray(128)
        transformed.copyInto(buf)
        var r8 = 0L
        for (i in 15 until 128) {
            val r6 = i.toLong()
            val r3Start = i.toLong() ushr 31
            val magic = 0x2AAAAAABL
            var cx = magic * i.toLong()
            val r1High = cx ushr 32
            cx = magic * r8
            val lrHigh = cx ushr 32
            val r0Start = r8 ushr 31
            var r2 = r0Start
            val r5 = (r1High ushr 1) - r3Start
            var r12 = r5 shl 4
            var r0 = (lrHigh ushr 1) - r0Start
            r2 = (lrHigh ushr 1) - r2
            var r1 = r0 shl 4
            r12 -= (r5 shl 2)
            var r3 = r2 shl 4
            val lr = r6 - r12
            r1 -= (r0 shl 2)
            var r7 = r5 + lr
            r3 -= (r2 shl 2)
            r1 = r8 - r1
            val r2b = r5 + r1
            r3 = r8 - r3
            r12 -= 0x18
            if (r7 > 0xb) r7 -= 0xc
            r3 += r5
            if (r5 > 1) r3 = r2b + r12
            var r4: Int
            if (r8 == 0L) {
                r4 = buf[r3.toInt()]
                r0 = hsum.toLong() % r6
                r4 = r4 and key[r7.toInt()]
                r4 = r4 or buf[r0.toInt()]
            } else {
                r4 = buf[r3.toInt()]
                r0 = hsum.toLong() % r6
                val r5b = buf[r0.toInt()]
                val r0b = hsum.toLong() % r8
                val r3b = buf[r0b.toInt()]
                r4 = (r4 and key[r7.toInt()]) or r5b
                r3 = r3b.toLong()
            }
            val previous = if (r8 == 0L) buf[(hsum.toLong() % r6 + 1).toInt()] else r3.toInt()
            buf[i] = ((previous.inv()) or r4) and 0xff
            r8++
        }
        val digest = md5(ByteArray(128) { buf[it].toByte() })
        var csum = 0
        for (i in 0..6) csum += imei[i + 1].code or (imei[i].code shl 8)
        csum += imei[14].code
        val result = mutableListOf<Char>()
        digest.forEach { b ->
            val value = b.toInt() and 0xff
            if (value in 0x30..0x39 && result.size < 8) result += value.toChar()
        }
        fun uint32At(offset: Int): Long = ByteBuffer.wrap(digest, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        var offset = (csum and 3) shl 2
        var extra = uint32At(offset).toString()
        while (result.size < 8) {
            if (extra.isEmpty()) {
                offset = (3 - (csum and 3)) shl 2
                extra = uint32At(offset).toString()
            }
            result += extra.last()
            extra = extra.dropLast(1)
        }
        if (result[0] == '0') {
            val digestOffset = if (csum != 0) 1 else 0
            result[0] = (((digest[digestOffset].toInt() and 0xff) and 7) + 1).digitToChar()
        }
        return result.joinToString("")
    }

    private fun customCrc201(imei: String): Long {
        var crc = 0xffffffffL
        imei.forEach { ch ->
            val index = ((crc and 0xff) xor ch.code.toLong()).toInt()
            crc = (CRC201_TABLE[index] xor (crc ushr 8)) and 0xffffffffL
        }
        return crc
    }

    private val CRC201_TABLE = longArrayOf(
        0x00000000,0x77073096,0xEE0E612C,0x990951BA,0x076DC419,0x196C3671,0x6E6B06E7,0xFED41B76,
        0x89D32BE0,0x10DA7A5A,0xFBD44C65,0x4DB26158,0x3AB551CE,0xA3BC0074,0xD4BB30E2,0x4ADFA541,
        0x3DD895D7,0xA4D1C46D,0xD3D6F4FB,0x4369E96A,0xD6D6A3E8,0xA1D1937E,0x38D8C2C4,0x4FDFF252,
        0xD1BB67F1,0xA6BC5767,0x3FB506DD,0x48B2364B,0xD80D2BDA,0xAF0A1B4C,0x36034AF6,0x41047A60,
        0xDF60EFC3,0xA867DF55,0x316E8EEF,0x90BF1D91,0x1DB71064,0x6AB020F2,0xF3B97148,0x84BE41DE,
        0x1ADAD47D,0x6DDDE4EB,0xF4D4B551,0x83D385C7,0x136C9856,0xFA0F3D63,0x8D080DF5,0x3B6E20C8,
        0x4C69105E,0xD56041E4,0xA2677172,0x3C03E4D1,0x4B04D447,0xD20D85FD,0xA50AB56B,0x646BA8C0,
        0xFD62F97A,0x8A65C9EC,0x14015C4F,0x63066CD9,0x45DF5C75,0xDCD60DCF,0xABD13D59,0x26D930AC,
        0x51DE003A,0xC8D75180,0xBFD06116,0x21B4F4B5,0x56B3C423,0xCFBA9599,0x706AF48F,0xE963A535,
        0x9E6495A3,0x0EDB8832,0x79DCB8A4,0xE0D5E91E,0x97D2D988,0x09B64C2B,0x7EB17CBD,0xE7B82D07,
        0x35B5A8FA,0x42B2986C,0xDBBBC9D6,0xACBCF940,0x32D86CE3,0xB8BDA50F,0x2802B89E,0x5F058808,
        0xC60CD9B2,0xB10BE924,0x2F6F7C87,0x58684C11,0xC1611DAB,0xB6662D3D,0x76DC4190,0x4969474D,
        0x3E6E77DB,0xAED16A4A,0xD9D65ADC,0x40DF0B66,0x37D83BF0,0xA9BCAE53,0xDEBB9EC5,0x47B2CF7F,
        0x30B5FFE9,0xBDBDF21C,0xCABAC28A,0x53B39330,0x24B4A3A6,0xBAD03605,0x03B6E20C,0x74B1D29A,
        0xEAD54739,0x9DD277AF,0x04DB2615,0xE10E9818,0x7F6A0DBB,0x086D3D2D,0x91646C97,0xE6635C01,
        0x6B6B51F4,0x1C6C6162,0x856530D8,0xF262004E,0x6C0695ED,0x1B01A57B,0x8208F4C1,0xF50FC457,
        0x65B0D9C6,0x12B7E950,0x8BBEB8EA,0xFCB9887C,0x62DD1DDF,0x15DA2D49,0x8CD37CF3,0xE40ECF0B,
        0x9309FF9D,0x0A00AE27,0x7D079EB1,0xF00F9344,0x4669BE79,0xCB61B38C,0xBC66831A,0x256FD2A0,
        0x5268E236,0xCC0C7795,0xBB0B4703,0x220216B9,0x5505262F,0xC5BA3BBE,0x68DDB3F8,0x1FDA836E,
        0x81BE16CD,0xF6B9265B,0x6FB077E1,0x18B74777,0x88085AE6,0xFF0F6A70,0x66063BCA,0x11010B5C,
        0x8F659EFF,0xF862AE69,0x616BFFD3,0x166CCF45,0xA00AE278,0xB2BD0B28,0x2BB45A92,0x5CB36A04,
        0xC2D7FFA7,0xB5D0CF31,0x2CD99E8B,0x5BDEAE1D,0x9B64C2B0,0xEC63F226,0x756AA39C,0x026D930A,
        0x9C0906A9,0xEB0E363F,0x72076785,0x05005713,0x346ED9FC,0xAD678846,0xDA60B8D0,0x44042D73,
        0x33031DE5,0xAA0A4C5F,0xDD0D7CC9,0x5005713C,0x270241AA,0xBE0B1010,0x01DB7106,0x98D220BC,
        0xEFD5102A,0x71B18589,0x06B6B51F,0x9FBFE4A5,0xE8B8D433,0x7807C9A2,0x0F00F934,0x9609A88E,
        0xC90C2086,0x5768B525,0x206F85B3,0xB966D409,0xCE61E49F,0x5EDEF90E,0x29D9C998,0xB0D09822,
        0xC7D7A8B4,0x59B33D17,0xCDD70693,0x54DE5729,0x23D967BF,0xB3667A2E,0xC4614AB8,0x5D681B02,
        0x2A6F2B94,0xB40BBE37,0xC30C8EA1,0x5A05DF1B,0x2EB40D81,0xB7BD5C3B,0xC0BA6CAD,0xEDB88320,
        0x9ABFB3B6,0x73DC1683,0xE3630B12,0x94643B84,0x0D6D6A3E,0x7A6A5AA8,0x67DD4ACC,0xF9B9DF6F,
        0x8EBEEFF9,0x17B7BE43,0x60B08ED5,0x8708A3D2,0x1E01F268,0x6906C2FE,0xF762575D,0x806567CB,
        0x95BF4A82,0xE2B87A14,0x7BB12BAE,0x0CB61B38,0x92D28E9B,0xE5D5BE0D,0x7CDCEFB7,0x0BDBDF21,
        0x86D3D2D4,0xF1D4E242,0xD70DD2EE,0x4E048354,0x3903B3C2,0xA7672661,0xD06016F7,0x2D02EF8D
    )
}

internal object ZteZx297520v3Algorithm {
    fun calculate(imei: String): String {
        require(imei.length >= 15 && imei.take(15).all(Char::isDigit))
        val map = intArrayOf(1,3,5,7,9,0,2,4,6,8)
        val transformed = imei.take(15).map { map[it.digitToInt()] }
        return (0 until 8).joinToString("") { index -> transformed.drop(index).take(8).sum().mod(10).toString() }
    }
}
