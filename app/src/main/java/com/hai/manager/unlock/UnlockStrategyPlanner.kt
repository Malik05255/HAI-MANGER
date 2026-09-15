package com.hai.manager.unlock

enum class UnlockPathStatus(val displayName: String) {
    READY("متاح"),
    DIAGNOSTICS("تشخيص"),
    RESEARCH("بحث"),
    UNAVAILABLE("غير متاح")
}

data class UnlockPathStep(
    val order: Int,
    val title: String,
    val status: UnlockPathStatus,
    val description: String
)

data class UnlockStrategyPlan(
    val title: String,
    val summary: String,
    val steps: List<UnlockPathStep>
)

/**
 * Turns device/platform identification into a user-facing next-step plan.
 *
 * This planner never grants write capability and never fabricates an NCK. Low-level paths are
 * intentionally labelled as research/recovery unless a device-specific flow is already verified.
 */
object UnlockStrategyPlanner {

    fun plan(
        report: ImeiUnlockReport,
        platform: ModemPlatformProfile?,
        modelHint: String?,
        connected: ConnectedUnlockContext? = null
    ): UnlockStrategyPlan {
        val model = modelHint.orEmpty().uppercase()

        val base = when {
            report.codes.any { it.confidence == UnlockConfidence.VERIFIED } -> UnlockStrategyPlan(
                title = "المسار المقترح",
                summary = "توجد خوارزمية Offline موثقة لهذه العائلة. ابدأ بالكود المطابق للعائلة فقط ولا تستخدم أكواد أجيال أخرى.",
                steps = listOf(
                    UnlockPathStep(1, "IMEI → NCK", UnlockPathStatus.READY, "استخدم المرشح الموثق المطابق للموديل/العائلة."),
                    UnlockPathStep(2, "فحص عدد المحاولات", UnlockPathStatus.DIAGNOSTICS, "تحقق من عداد المحاولات قبل إدخال أي كود إذا كان الراوتر يعرضه."),
                    UnlockPathStep(3, "إدخال الكود", UnlockPathStatus.DIAGNOSTICS, "يتم الإدخال فقط عبر واجهة الجهاز أو API/AT موثق لنفس الموديل.")
                )
            )

            platform == null -> UnlockStrategyPlan(
                title = "المسار المقترح",
                summary = "المنصة غير محددة بما يكفي لاختيار مسار منخفض المستوى.",
                steps = listOf(
                    UnlockPathStep(1, "تحديد الموديل والـFirmware", UnlockPathStatus.DIAGNOSTICS, "اجمع الموديل الكامل وHardware/Firmware version أولًا."),
                    UnlockPathStep(2, "تحديد المنصة", UnlockPathStatus.DIAGNOSTICS, "حدد Qualcomm أو Balong أو MediaTek قبل اختيار أي أداة."),
                    UnlockPathStep(3, "NCK", UnlockPathStatus.UNAVAILABLE, "لا يتم توليد كود حتى تثبت عائلة خوارزمية متوافقة.")
                )
            )

            platform.family.startsWith("QUALCOMM-SDX") -> qualcommPlan(platform)
            platform.family.startsWith("HUAWEI-BALONG-5000") -> balong5000Plan()
            platform.family.startsWith("HUAWEI-BALONG") -> balongPlan(model)
            platform.family.startsWith("MEDIATEK-T750") -> mediatekT750Plan()
            platform.family.startsWith("MEDIATEK-T830") || platform.family.startsWith("ZTE-MC8512") -> mediatekT830Plan(platform)
            else -> genericPlatformPlan(platform)
        }

        return applyConnectedContext(base, connected)
    }

    private fun applyConnectedContext(
        base: UnlockStrategyPlan,
        connected: ConnectedUnlockContext?
    ): UnlockStrategyPlan {
        if (connected == null) return base

        if (connected.state == ConnectedLockState.UNLOCKED) {
            return UnlockStrategyPlan(
                title = "المسار المقترح — لا يحتاج فك",
                summary = "الراوتر المتصل يعلن أن Network/SIM lock غير مفعّل. لا يوجد سبب لتجربة NCK أو مسارات منخفضة المستوى.",
                steps = listOf(
                    UnlockPathStep(1, "حالة القفل", UnlockPathStatus.READY, "الجهاز يعلن أنه غير مقفل على مشغل."),
                    UnlockPathStep(2, "التحقق الدوري", UnlockPathStatus.DIAGNOSTICS, "يمكن إعادة قراءة الحالة بعد تغيير الشريحة أو الـFirmware فقط."),
                    UnlockPathStep(3, "مسارات الفك", UnlockPathStatus.UNAVAILABLE, "لا تشغّل NCK/EDL/NVRAM ما دام القفل غير مفعّل.")
                )
            )
        }

        val identityDetails = buildList {
            connected.firmware?.takeIf { it.isNotBlank() }?.let { add("Firmware: $it") }
            connected.currentOperator?.takeIf { it.isNotBlank() }?.let { add("الشبكة: $it") }
            connected.source?.takeIf { it.isNotBlank() }?.let { add("المصدر: $it") }
        }.joinToString(" • ")

        if (connected.state == ConnectedLockState.LOCKED && connected.attemptsExhausted) {
            val guarded = base.steps.map { step ->
                if (step.status == UnlockPathStatus.READY && step.title.contains("NCK", ignoreCase = true)) {
                    step.copy(
                        status = UnlockPathStatus.DIAGNOSTICS,
                        description = "يمكن حساب الكود Offline، لكن لا يتم إدخاله لأن عداد المحاولات الظاهر = 0."
                    )
                } else step
            }
            return base.copy(
                title = "${base.title} — المحاولات منتهية",
                summary = "الجهاز مقفل ويعرض 0 محاولة متبقية. أوقف إدخال الأكواد وانتقل إلى تشخيص Firmware/القفل أو مسار استعادة موثق. ${identityDetails}".trim(),
                steps = renumber(
                    listOf(
                        UnlockPathStep(0, "عداد NCK", UnlockPathStatus.UNAVAILABLE, "0 محاولة متبقية — لا ترسل أي كود إضافي.")
                    ) + guarded
                )
            )
        }

        val lockDescription = when (connected.state) {
            ConnectedLockState.LOCKED -> {
                val attempts = connected.attemptsRemaining?.takeIf { it.isNotBlank() }
                if (attempts != null) "القفل مؤكد. المحاولات الظاهرة: $attempts." else "القفل مؤكد، لكن عداد المحاولات غير مكشوف."
            }
            ConnectedLockState.UNKNOWN -> "الراوتر متصل، لكن حالة القفل لم تُحسم من الواجهة الحالية."
            ConnectedLockState.UNLOCKED -> "غير مقفل"
        }

        return base.copy(
            summary = listOf(lockDescription, identityDetails, base.summary)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            steps = renumber(
                listOf(
                    UnlockPathStep(0, "تشخيص الراوتر المتصل", UnlockPathStatus.DIAGNOSTICS, lockDescription)
                ) + base.steps
            )
        )
    }

    private fun renumber(steps: List<UnlockPathStep>): List<UnlockPathStep> =
        steps.mapIndexed { index, step -> step.copy(order = index + 1) }

    private fun qualcommPlan(platform: ModemPlatformProfile): UnlockStrategyPlan {
        val modelLabel = platform.models.substringBefore("/").trim().ifBlank { "الجهاز" }
        return UnlockStrategyPlan(
            title = "المسار المقترح — Qualcomm",
            summary = "ابدأ من واجهة الراوتر ثم QMI personalization. EDL/NV يبقيان طبقة بحث/استعادة ولا يعنيان وجود مولد IMEI→NCK.",
            steps = listOf(
                UnlockPathStep(1, "Web API / goform", UnlockPathStatus.DIAGNOSTICS, "اقرأ SIM Lock وحالة المودم وعدد محاولات NCK إن كانت الواجهة تعرضها لـ$modelLabel."),
                UnlockPathStep(2, "QMI UIM/DMS", UnlockPathStatus.DIAGNOSTICS, "حدد personalization state وعداد المحاولات؛ QMI يستطيع استقبال DCK/NCK الصحيح لكنه لا يحسبه من IMEI."),
                UnlockPathStep(3, "DIAG / NV / EFS", UnlockPathStatus.RESEARCH, "استخدم القراءة والمقارنة بين locked/unlocked لفهم مكان وحالة القفل قبل أي تعديل."),
                UnlockPathStep(4, "EDL / Firehose", UnlockPathStatus.RESEARCH, "مسار استعادة/بحث فقط ويتطلب loader متوافقًا ومقبولًا من Secure Boot."),
                UnlockPathStep(5, "IMEI → NCK", UnlockPathStatus.UNAVAILABLE, "لا توجد خوارزمية موثقة لهذه المنصة داخل HAI حاليًا.")
            )
        )
    }

    private fun balong5000Plan(): UnlockStrategyPlan = UnlockStrategyPlan(
        title = "المسار المقترح — Balong 5000",
        summary = "H112/H122/E6878 تُعامل كمنصة Balong 5000 مستقلة. HiLink أولًا، ثم Emergency USB/loader كبحث واستعادة فقط.",
        steps = listOf(
            UnlockPathStep(1, "HiLink / SIM security", UnlockPathStatus.DIAGNOSTICS, "اقرأ حالة SIM Lock والـFirmware وبيانات الجهاز من الواجهة الموثقة أولًا."),
            UnlockPathStep(2, "Balong 5000 fingerprint", UnlockPathStatus.DIAGNOSTICS, "ثبت Hardware/Firmware revision قبل اختيار أي مسار منخفض المستوى."),
            UnlockPathStep(3, "Emergency USB / balong-usbdload", UnlockPathStatus.RESEARCH, "مرجع بحث واستعادة للـBalong 5000؛ لا يتم تشغيله تلقائيًا من HAI ولا يمنح صلاحية كتابة بحد ذاته."),
            UnlockPathStep(4, "Firmware / NVRAM analysis", UnlockPathStatus.RESEARCH, "قارن بنية الفيرموير/NVRAM لتحديد SIMLOCK بدل تعميم V1/V2/V201."),
            UnlockPathStep(5, "IMEI → NCK", UnlockPathStatus.UNAVAILABLE, "لا توجد خوارزمية IMEI-only موثقة لـH112/H122 داخل HAI.")
        )
    )

    private fun balongPlan(model: String): UnlockStrategyPlan {
        val v4Aware = listOf("B310", "B311", "B315").any(model::startsWith)
        return UnlockStrategyPlan(
            title = "المسار المقترح — Huawei Balong",
            summary = if (v4Aware) {
                "هذا الموديل V4-aware؛ لا تستخدم V1/V2/V201 كحل نهائي بدون مطابقة Firmware."
            } else {
                "ابدأ بـHiLink وFirmware profile، ثم انتقل لطبقة AT/NVRAM فقط عند وجود دليل لنفس الجيل."
            },
            steps = listOf(
                UnlockPathStep(1, "HiLink / Web API", UnlockPathStatus.DIAGNOSTICS, "اقرأ SIM security والموديل والـFirmware."),
                UnlockPathStep(2, "Firmware Profile", UnlockPathStatus.DIAGNOSTICS, "حدد جيل Balong ونوع القفل قبل اختيار الخوارزمية."),
                UnlockPathStep(3, "AT / NVRAM", UnlockPathStatus.RESEARCH, "يستخدم فقط عندما يكون مسار الموديل والـFirmware موثقًا."),
                UnlockPathStep(4, "Legacy NCK", if (v4Aware) UnlockPathStatus.UNAVAILABLE else UnlockPathStatus.RESEARCH, "لا يُرفع إلى متاح إلا عند تطابق عائلة الخوارزمية فعليًا.")
            )
        )
    }

    private fun mediatekT750Plan(): UnlockStrategyPlan = UnlockStrategyPlan(
        title = "المسار المقترح — MediaTek T750 / MT6890",
        summary = "MediaTek له مسار مستقل عن Qualcomm. التخزين وPMT/NAND/eMMC يجب تحديدها قبل أي تعامل منخفض المستوى.",
        steps = listOf(
            UnlockPathStep(1, "Web / AT diagnostics", UnlockPathStatus.DIAGNOSTICS, "اجمع حالة SIM والموديل والـFirmware من الواجهات المتاحة."),
            UnlockPathStep(2, "Storage fingerprint", UnlockPathStatus.DIAGNOSTICS, "حدد NAND/eMMC وبنية الأقسام قبل استخدام أي أداة استعادة."),
            UnlockPathStep(3, "NVRAM / protect data", UnlockPathStatus.RESEARCH, "تحليل SIMLOCK يكون قراءة ومقارنة أولًا؛ لا تفترض أن القفل ملف واحد قابل للحذف."),
            UnlockPathStep(4, "BROM / SP Flash Tool", UnlockPathStatus.RESEARCH, "طبقة استعادة فقط بملفات متوافقة مع نفس hardware revision."),
            UnlockPathStep(5, "IMEI → NCK", UnlockPathStatus.UNAVAILABLE, "لا توجد خوارزمية IMEI-only موثقة لهذه المنصة داخل HAI.")
        )
    )

    private fun mediatekT830Plan(platform: ModemPlatformProfile): UnlockStrategyPlan = UnlockStrategyPlan(
        title = "المسار المقترح — MediaTek T830 / M80",
        summary = "T830 جيل مستقل؛ لا يتم إسقاط أدوات MT6890/T750 عليه. ${platform.confidence.displayName}.",
        steps = listOf(
            UnlockPathStep(1, "Hardware/Firmware fingerprint", UnlockPathStatus.DIAGNOSTICS, "ثبت المنصة الفعلية وSKU أولًا، خصوصًا MC8512."),
            UnlockPathStep(2, "Web / modem diagnostics", UnlockPathStatus.DIAGNOSTICS, "اقرأ حالة SIM Lock والـFirmware قبل أي أداة منخفضة المستوى."),
            UnlockPathStep(3, "T830-specific NVRAM research", UnlockPathStatus.RESEARCH, "لا تستخدم offsets أو Download Agent مأخوذة من T750 دون دليل."),
            UnlockPathStep(4, "IMEI → NCK", UnlockPathStatus.UNAVAILABLE, "لا يوجد مولد موثق لهذه المنصة داخل HAI.")
        )
    )

    private fun genericPlatformPlan(platform: ModemPlatformProfile): UnlockStrategyPlan = UnlockStrategyPlan(
        title = "المسار المقترح",
        summary = "تم تحديد المنصة لكن لا يوجد مسار فك آلي موثق لها.",
        steps = listOf(
            UnlockPathStep(1, "تشخيص المنصة", UnlockPathStatus.DIAGNOSTICS, platform.note),
            UnlockPathStep(2, "واجهات الجهاز", UnlockPathStatus.DIAGNOSTICS, "ابدأ بالواجهات الرسمية/الموثقة قبل أي مسار منخفض المستوى."),
            UnlockPathStep(3, "بحث منخفض المستوى", UnlockPathStatus.RESEARCH, platform.accessLayers.joinToString(" • ")),
            UnlockPathStep(4, "IMEI → NCK", UnlockPathStatus.UNAVAILABLE, platform.imeiNckDerivation)
        )
    )
}
