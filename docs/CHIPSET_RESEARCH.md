# HAI MANAGER — Chipset Research Map

هذه الوثيقة تربط موديلات الراوتر بمنصة المودم والمشاريع المفتوحة المصدر التي نستفيد منها في التشخيص والتوافق. الهدف ليس نسخ أدوات منخفضة المستوى داخل التطبيق، بل معرفة الطبقة الصحيحة لكل جيل قبل أي عملية حساسة.

## قاعدة التنفيذ

1. Web API / HiLink / goform أولاً.
2. AT / QMI / MBIM / DIAG للقراءة والتشخيص عند توفر قناة موثقة.
3. EDL / Firehose / NV / EFS تعتبر طبقة بحث واستعادة وليست مسار فك افتراضي داخل التطبيق.
4. لا ينتج HAI كود NCK من IMEI لجيل حديث إلا عند وجود خوارزمية مثبتة لنفس المنصة.
5. اسم المنتج وحده لا يكفي إذا كان نفس الاسم يباع بأكثر من chipset/SKU.

## Qualcomm SIM personalization — نتيجة مهمة

مشاريع `linux-mobile-broadband/libqmi` و`openwrt/uqmi` تثبت أن بروتوكول Qualcomm نفسه يدعم personalization/depersonalization:

- قراءة حالة SIM/personalization.
- تنفيذ Depersonalization عند توفير DCK/NCK الصحيح.
- إرجاع عدد محاولات التحقق/فك الحظر في الرسائل ذات الصلة.

هذا يثبت **مسار إدخال الكود** ولا يثبت **مصدر الكود**. QMI يستقبل DCK/NCK كقيمة خارجية ولا توجد في هذه الواجهات خوارزمية IMEI→DCK. لذلك يفصل HAI بين:

`Personalization protocol = supported/observable`

و

`IMEI-only DCK derivation = unverified for modern SDX generations`

هذا الفصل مهم جدًا لـMC801A/MC888: العثور على QMI depersonalization لا يعني أن الكود يمكن حسابه من IMEI وحده.

---

## Qualcomm SDX55 / Snapdragon X55

### ZTE MC801A
- أقرب هدف للمشروع الحالي.
- طبقة الإدارة: ZTE goform/WebUI.
- مشاريع مرجعية:
  - `nicjac/python-zte-mc801a` — تسجيل الدخول والـgoform والـtelemetry الخاصة بالـMC801A.
  - `tpoechtrager/ZTE-Web-Script` وMC801A scripts — أوامر ZTE المخفية وAD/action seed.
  - `linux-mobile-broadband/libqmi` — QMI DMS/UIM personalization/depersonalization protocol.
  - `openwrt/uqmi` — حالة UIM وpersonalization ضمن OpenWrt QMI stack.
  - `bkerler/edl` — Qualcomm Sahara/Firehose/DIAG كمرجع منخفض المستوى.
  - `iamromulan/qfenix` — GPT/NV/EFS/device detection لعائلة Qualcomm.

### ZTE MC7010
- SDX55 موثق مباشرة في مشروع الجهاز.
- مشروع مرجعي:
  - `stich86/ZTE-MC7010` — USB diagnostic/firmware family ومعلومات المنصة.

### ZTE MU5001
- Snapdragon X55.
- نستخدمه كمرجع إضافي لسلوك ZTE MBB على نفس الجيل.

### قرار HAI
`SDX55 = goform -> personalization diagnostics -> QMI/DIAG research -> EDL only for recovery/research`

---

## Qualcomm SDX62 / Snapdragon X62

### ZTE MC888 / MC888D / MC888 Pro
- MC888 القياسي موثق على X62.
- لا نعمم نفس التصنيف على كل MC888 Ultra/A variants.

### ZTE MU5120
- X62 موثق من ZTE.

### مشاريع مرجعية
- `linux-mobile-broadband/libqmi` و`openwrt/uqmi` — personalization/depersonalization وحالة UIM.
- `iamromulan/quectel-rgmii-toolkit` — RM520N/RM521F، MHI/RGMII وLinux AP على منصات Qualcomm الحديثة.
- `dr-dolomite/QManager-RM520N` — إدارة كاملة لـRM520N عبر internal Linux/CGI.
- `snowzach/quectool` — AT/QENG/QCAINFO/bands/cell lock abstraction لعائلة RM520/RM521.
- `bkerler/Loaders#82` — بحث loaders لـSDX62/65 وتوضيح مشكلة signed loaders على ZTE الحديثة.
- `quectel-official/QLog` — logging/PCIe support لـSDX55/62/65.
- `iamromulan/qfenix` — منصة عامة للـNV/EFS/GPT/EDL.

### قرار HAI
لا نفترض أن وجود Qualcomm EDL يعني إمكانية الكتابة. Secure Boot والـsigned loader جزء من Profile الجهاز. كما أن وجود QMI depersonalization لا يعني وجود IMEI-only NCK generator.

---

## Qualcomm SDX65 / Snapdragon X65

### ZTE MC888A / MC888A Ultra
- توجد أدلة مجتمعية قوية على X65، لكن HAI يبقي الثقة أقل من الموديلات التي لها مواصفات رسمية مباشرة.

### MC888 Ultra / MC889 variants
- قد تختلف المنصة حسب SKU والسوق.
- يجب استخدام Hardware version / firmware family / diagnostic fingerprint قبل تثبيت SDX62 أو SDX65.

### مشاريع مرجعية
- `linux-mobile-broadband/libqmi` و`openwrt/uqmi` — personalization protocol مستقل عن جيل SDX المحدد.
- `bkerler/Loaders#82` — loaders لـSIMCom SDX65 وأبحاث secure boot.
- `quectel-official/QLog` — يدعم SDX65 في PCIe/MHI logging.
- `iamromulan/qfenix` — تعريف أجهزة Qualcomm وعمليات NV/EFS/GPT.
- `bkerler/edl` — Sahara/Firehose/DIAG generic reference.

### قرار HAI
`MC888A` و`MC888A Ultra` يمكن تصنيفهما SDX65 بدرجة Community، أما `MC888 Ultra` و`MC889*` فلا تثبت المنصة من الاسم فقط.

---

## Qualcomm SDX75 / Snapdragon X75 — SDXPINN

### ZTE U60 Pro / MU5250
- مرجع مباشر للجيل الأحدث.

### مشاريع مرجعية
- `jesther-ai/open-u60-pro` — أهم مشروع: ZTE MU5250، OpenWrt/ZWRT، أكثر من 100 endpoint، config backup/decryption وmobile companions.
- `amenekowo/mu5250_tweaking` — ملاحظات عملية على debug mode وOpenWrt الداخلي.
- `linux-mobile-broadband/libqmi` و`openwrt/uqmi` — مرجع personalization/QMI.
- `iamromulan/quectel-rgmii-toolkit` — فرع SDXPINN لـRM550/RM551.
- `iamromulan/qfenix` — SDX75 ضمن قاعدة Qualcomm device detection.
- `qualcomm/qdlrs` — تنفيذ رسمي مفتوح المصدر لـSahara/Firehose في Rust.

### قرار HAI
نستفيد من SDX75 لمعرفة اتجاه ZTE الجديد: OpenWrt/ubus/services أولاً، وليس نسخ منطق MC801A القديم حرفيًا.

---

# Huawei Balong

## V7R1 / Hi6920
- E5172 / E5180 / B593s.

## V7R11 / Hi6921
- B310 / B315s / E3372h / E8372h / E5573 / E5576.
- أهم جيل لموضوع B310/B315 وقفل V4.

## V7R22 / Hi6932
- B316 / B525 / B528 / B535 / E5785 / E5885.

## V7R5 / Hi6950
- B612s / B618s / B715s.

## V7R65 / Hi6965
- B625 / B818.

## Balong 5000 / Hi9500
- H112 / H122 / E6878.
- Huawei 5G؛ لا يستخدم HAI خوارزميات V1/V2/V201 عليه.

### مشاريع مرجعية
- `Huawei-LTE-routers-mods/README` — خريطة الأجيال والموديلات.
- `forth32/balongflash` — firmware format/flashing research.
- `forth32/balong-usbdload` — emergency loader ودعم أجيال Balong حتى 5000.
- `forth32/balong-nvtool` — NVRAM structure research.
- `huawei-lte-api` — HiLink API واختبارات على B310/B315/B525/B535/B618/B818/H122 وغيرها.

### قرار HAI
Huawei يُعامل حسب Balong generation قبل اختيار أي NCK strategy. B-series ليست عائلة خوارزمية واحدة.

---

# MediaTek

سيتم إدخال MediaTek كمرحلة مستقلة بعد اكتمال Qualcomm/Balong mappings. لا يربط HAI أي Huawei model بـMediaTek بدون دليل موديل/Hardware واضح. منصات MediaTek في CPE تختلف جذريًا عن Qualcomm EDL وHuawei Balong، لذلك لها Adapter مستقل عند إضافتها.
