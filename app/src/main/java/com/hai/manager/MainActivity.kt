package com.hai.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hai.manager.router.CarrierLockState
import com.hai.manager.router.RouterAccessStatus
import com.hai.manager.router.RouterBrand
import com.hai.manager.router.RouterCarrierLockProbeService
import com.hai.manager.router.RouterCarrierLockSummary
import com.hai.manager.router.RouterCapabilityProbeService
import com.hai.manager.router.RouterDiscoveryService
import com.hai.manager.router.RouterInspection
import com.hai.manager.router.RouterInspectorService
import com.hai.manager.router.RouterNetworkUnlockService
import com.hai.manager.router.RouterSnapshot
import com.hai.manager.router.firmwareProfileInfo
import com.hai.manager.unlock.ImeiUnlockFacade
import com.hai.manager.unlock.PlatformResolver
import com.hai.manager.unlock.UnlockBrand
import com.hai.manager.unlock.UnlockConfidence
import com.hai.manager.update.UpdateNotifications
import com.hai.manager.update.UpdateScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateNotifications.createChannel(this)
        UpdateScheduler.schedule(this)
        requestPermissionsIfNeeded()
        setContent { HaiManagerApp() }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 201)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }
}

private enum class AppScreen { HOME, SYSTEM_UNLOCK }
private enum class SystemStage { READY, DIAGNOSING, RESULT, UNLOCKING, DONE }

@Composable
fun HaiManagerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    val probe = remember { RouterCapabilityProbeService() }
    val lockProbe = remember { RouterCarrierLockProbeService() }
    val unlocker = remember { RouterNetworkUnlockService() }

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var stage by remember { mutableStateOf(SystemStage.READY) }
    var progress by remember { mutableIntStateOf(0) }
    var router by remember { mutableStateOf<RouterSnapshot?>(null) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var lockSummary by remember { mutableStateOf<RouterCarrierLockSummary?>(null) }
    var verifiedNck by remember { mutableStateOf<String?>(null) }
    var manualNck by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loginUrl by remember { mutableStateOf<String?>(null) }

    suspend fun moveProgress(target: Int) {
        while (progress < target) {
            delay(10)
            progress = (progress + 2).coerceAtMost(target)
        }
    }

    fun resetSystem() {
        stage = SystemStage.READY
        progress = 0
        router = null
        inspection = null
        lockSummary = null
        verifiedNck = null
        manualNck = ""
        message = null
    }

    fun diagnose() {
        if (stage == SystemStage.DIAGNOSING || stage == SystemStage.UNLOCKING) return
        scope.launch {
            stage = SystemStage.DIAGNOSING
            progress = 0
            message = null
            lockSummary = null
            verifiedNck = null
            moveProgress(12)

            val found = discovery.discover()
            router = found
            moveProgress(30)
            if (!found.connected || found.managementUrl == null) {
                message = "لم يتم العثور على الراوتر. اتصل بشبكة الراوتر ثم أعد المحاولة."
                moveProgress(100)
                stage = SystemStage.RESULT
                return@launch
            }

            val inspected = inspector.inspect(found)
            inspection = inspected
            moveProgress(52)
            if (inspected.accessStatus == RouterAccessStatus.AUTH_REQUIRED) {
                message = "يحتاج الراوتر تسجيل الدخول أولًا."
                moveProgress(100)
                stage = SystemStage.RESULT
                return@launch
            }
            if (inspected.accessStatus != RouterAccessStatus.AVAILABLE) {
                message = "تعذر قراءة الراوتر. تأكد من الاتصال ثم أعد التشخيص."
                moveProgress(100)
                stage = SystemStage.RESULT
                return@launch
            }

            val enriched = probe.enrich(inspected)
            inspection = enriched
            moveProgress(74)
            lockSummary = runCatching { lockProbe.probe(enriched) }.getOrNull()
            moveProgress(88)

            val imei = enriched.device?.imei.orEmpty().filter(Char::isDigit)
            if (imei.length == 15) {
                val brand = when (enriched.snapshot.brand) {
                    RouterBrand.HUAWEI -> UnlockBrand.HUAWEI
                    RouterBrand.ZTE -> UnlockBrand.ZTE
                    else -> UnlockBrand.AUTO
                }
                verifiedNck = runCatching {
                    ImeiUnlockFacade.analyze(imei, brand, enriched.device?.model)
                        .codes
                        .firstOrNull { it.confidence == UnlockConfidence.VERIFIED }
                        ?.code
                }.getOrNull()
            }

            moveProgress(100)
            stage = if (lockSummary?.state == CarrierLockState.UNLOCKED) SystemStage.DONE else SystemStage.RESULT
        }
    }

    fun unlock() {
        val current = inspection ?: return
        val code = verifiedNck ?: manualNck.takeIf { it.matches(Regex("[0-9]{6,32}")) } ?: return
        if (stage == SystemStage.UNLOCKING) return
        scope.launch {
            stage = SystemStage.UNLOCKING
            progress = 0
            message = null
            moveProgress(28)
            val result = unlocker.unlock(current, code)
            moveProgress(72)
            if (result.success) {
                delay(700)
                lockSummary = runCatching { lockProbe.probe(current) }.getOrNull()
            }
            moveProgress(100)
            val unlocked = lockSummary?.state == CarrierLockState.UNLOCKED
            if (unlocked) {
                stage = SystemStage.DONE
                message = "تم فك القفل. الراوتر جاهز لشريحة أخرى."
            } else {
                stage = SystemStage.RESULT
                message = if (result.success) "تم إرسال رقم الفك، لكن لم يتم تأكيد فتح القفل بعد." else result.message
            }
        }
    }

    HaiTheme {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            loginUrl?.let { url ->
                InAppRouterPanel(
                    url = url,
                    onClose = {
                        loginUrl = null
                        diagnose()
                    }
                )
                return@CompositionLocalProvider
            }

            when (screen) {
                AppScreen.HOME -> SimpleHomeScreen(
                    onImei = {
                        context.startActivity(Intent(context, ImeiUnlockActivity::class.java))
                    },
                    onSystem = {
                        resetSystem()
                        screen = AppScreen.SYSTEM_UNLOCK
                    }
                )

                AppScreen.SYSTEM_UNLOCK -> {
                    BackHandler {
                        resetSystem()
                        screen = AppScreen.HOME
                    }
                    SystemUnlockScreen(
                        stage = stage,
                        progress = progress,
                        router = router,
                        inspection = inspection,
                        lockSummary = lockSummary,
                        verifiedNck = verifiedNck,
                        manualNck = manualNck,
                        message = message,
                        onNckChange = { value -> manualNck = value.filter(Char::isDigit).take(32) },
                        onDiagnose = ::diagnose,
                        onLogin = { loginUrl = router?.managementUrl },
                        onUnlock = ::unlock,
                        onBack = {
                            resetSystem()
                            screen = AppScreen.HOME
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleHomeScreen(
    onImei: () -> Unit,
    onSystem: () -> Unit
) {
    HaiPage(title = "HAI MANAGER", subtitle = "فك قفل الراوتر") {
        Text("اختر الطريقة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        UnlockOptionCard(
            title = "فك القفل عبر IMEI",
            subtitle = "أدخل الرقم واحصل على نتيجة الفك",
            icon = Icons.Outlined.Lock,
            onClick = onImei
        )
        UnlockOptionCard(
            title = "فك القفل عبر الراوتر",
            subtitle = "وصّل الراوتر ودع التطبيق يشخّصه",
            icon = Icons.Outlined.Router,
            onClick = onSystem
        )
    }
}

@Composable
private fun UnlockOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SystemUnlockScreen(
    stage: SystemStage,
    progress: Int,
    router: RouterSnapshot?,
    inspection: RouterInspection?,
    lockSummary: RouterCarrierLockSummary?,
    verifiedNck: String?,
    manualNck: String,
    message: String?,
    onNckChange: (String) -> Unit,
    onDiagnose: () -> Unit,
    onLogin: () -> Unit,
    onUnlock: () -> Unit,
    onBack: () -> Unit
) {
    val model = inspection?.device?.model ?: router?.model
    val platform = PlatformResolver.resolve(model)
    val attemptsZero = lockSummary?.attemptsRemaining?.trim()?.toIntOrNull() == 0
    val profile = inspection?.firmwareProfileInfo
    val runtimeNckReady = inspection != null &&
        profile?.nckEntry?.canWrite == true &&
        inspection.probeReport?.nckEntryVerified == true
    val manualNckValid = manualNck.matches(Regex("[0-9]{6,32}"))
    val effectiveNckAvailable = verifiedNck != null || manualNckValid
    val needsManualNck = inspection != null &&
        lockSummary?.state == CarrierLockState.LOCKED &&
        !attemptsZero &&
        runtimeNckReady &&
        verifiedNck == null
    val autoReady = inspection != null &&
        lockSummary?.state == CarrierLockState.LOCKED &&
        !attemptsZero &&
        effectiveNckAvailable &&
        runtimeNckReady

    HaiPage(title = "فك القفل عبر الراوتر", subtitle = "اتبع الخطوات فقط") {
        if (stage == SystemStage.READY) {
            HaiCard {
                HaiSectionTitle("قبل التشخيص")
                Text("1. شغّل الراوتر")
                Text("2. اتصل من الجوال بشبكة Wi‑Fi الخاصة بالراوتر")
                Text("3. إذا كان الراوتر يوفّر شبكة عبر Type‑C يمكنك استخدامها بدل Wi‑Fi")
                Button(onClick = onDiagnose, modifier = Modifier.fillMaxWidth()) {
                    Text("تشخيص")
                }
            }
        }

        if (stage == SystemStage.DIAGNOSING || stage == SystemStage.UNLOCKING) {
            HaiCard {
                Text(
                    if (stage == SystemStage.UNLOCKING) "جاري فك القفل" else "جاري التشخيص",
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$progress%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        if (stage == SystemStage.RESULT || stage == SystemStage.DONE) {
            HaiCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("النتيجة", fontWeight = FontWeight.Bold)
                    if (stage == SystemStage.DONE) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HaiValueRow("الراوتر", model ?: "غير معروف")
                HaiValueRow("المعالج", platform?.name ?: "غير معروف")
                HaiValueRow("حالة القفل", lockSummary?.state?.displayName ?: "غير معروف")
                HaiValueRow("المحاولات", lockSummary?.attemptsRemaining ?: "غير معروف")
                HaiValueRow(
                    "الفك",
                    when {
                        lockSummary?.state == CarrierLockState.UNLOCKED -> "لا يحتاج فك"
                        autoReady -> "جاهز"
                        attemptsZero -> "متوقف — المحاولات منتهية"
                        needsManualNck -> "جاهز — أدخل رقم الفك"
                        lockSummary?.state == CarrierLockState.LOCKED -> "غير متاح لهذا الإصدار"
                        else -> "غير محسوم"
                    }
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (inspection?.accessStatus == RouterAccessStatus.AUTH_REQUIRED && router?.managementUrl != null) {
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("تسجيل الدخول للراوتر")
                }
            } else {
                if (needsManualNck) {
                    HaiCard {
                        OutlinedTextField(
                            value = manualNck,
                            onValueChange = onNckChange,
                            label = { Text("رقم الفك NCK") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (autoReady) {
                    Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                        Text("فك القفل")
                    }
                } else if (lockSummary?.state == CarrierLockState.UNLOCKED || stage == SystemStage.DONE) {
                    HaiCard {
                        Text("الراوتر مفتوح وجاهز لشريحة أخرى.", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (stage != SystemStage.DONE) {
                OutlinedButton(onClick = onDiagnose, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة التشخيص")
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("رجوع")
        }
    }
}
