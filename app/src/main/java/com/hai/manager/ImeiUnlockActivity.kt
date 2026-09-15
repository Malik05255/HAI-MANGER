package com.hai.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hai.manager.unlock.ConnectedLockState
import com.hai.manager.unlock.ImeiUnlockFacade
import com.hai.manager.unlock.ImeiUnlockReport
import com.hai.manager.unlock.PlatformResolver
import com.hai.manager.unlock.TacResolver
import com.hai.manager.unlock.UnlockBrand
import com.hai.manager.unlock.UnlockConfidence
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImeiUnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialImei = intent.getStringExtra(EXTRA_IMEI).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL)
        val brand = intent.getStringExtra(EXTRA_BRAND)
            ?.let { runCatching { UnlockBrand.valueOf(it) }.getOrNull() }
            ?: UnlockBrand.AUTO
        val connectedState = intent.getStringExtra(EXTRA_LOCK_STATE)
            ?.let { runCatching { ConnectedLockState.valueOf(it) }.getOrNull() }
            ?: ConnectedLockState.UNKNOWN

        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ImeiUnlockScreen(
                        initialImei = initialImei,
                        initialBrand = brand,
                        modelHint = model,
                        connectedState = connectedState,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_IMEI = "unlock_imei"
        const val EXTRA_MODEL = "unlock_model"
        const val EXTRA_BRAND = "unlock_brand"
        const val EXTRA_CONNECTED = "unlock_connected"
        const val EXTRA_LOCK_STATE = "unlock_lock_state"
        const val EXTRA_ATTEMPTS = "unlock_attempts"
        const val EXTRA_FIRMWARE = "unlock_firmware"
        const val EXTRA_OPERATOR = "unlock_operator"
        const val EXTRA_LOCK_SOURCE = "unlock_lock_source"
        const val EXTRA_WAITING_NCK = "unlock_waiting_nck"
        const val EXTRA_LOCKED_HPLMNS = "unlock_locked_hplmns"
        const val EXTRA_MODEM_STATE = "unlock_modem_state"
        const val EXTRA_NCK_RAW = "unlock_nck_raw"
        const val EXTRA_WEBUI = "unlock_webui"
        const val EXTRA_LOCK_DIAGNOSTIC = "unlock_lock_diagnostic"
    }
}

@Composable
private fun ImeiUnlockScreen(
    initialImei: String,
    initialBrand: UnlockBrand,
    modelHint: String?,
    connectedState: ConnectedLockState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imei by remember { mutableStateOf(initialImei.filter(Char::isDigit).take(15)) }
    var progress by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImeiUnlockReport?>(null) }

    fun diagnose() {
        if (imei.length != 15 || working) return
        scope.launch {
            working = true
            report = null
            progress = 0
            while (progress < 28) {
                delay(14)
                progress += 1
            }
            val analyzed = runCatching { ImeiUnlockFacade.analyze(imei, initialBrand, modelHint) }.getOrNull()
            while (progress < 100) {
                delay(10)
                progress += 2
                if (progress > 100) progress = 100
            }
            report = analyzed
            working = false
        }
    }

    HaiPage(title = "فك القفل عبر IMEI", subtitle = "أدخل رقم IMEI فقط") {
        HaiCard {
            OutlinedTextField(
                value = imei,
                onValueChange = {
                    imei = it.filter(Char::isDigit).take(15)
                    report = null
                    progress = 0
                },
                label = { Text("IMEI — 15 رقمًا") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = ::diagnose,
                enabled = imei.length == 15 && !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Text(" تشخيص")
            }
        }

        if (working) {
            HaiCard {
                Text("جاري التشخيص", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$progress%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        report?.let { result ->
            SimpleImeiResult(
                context = context,
                report = result,
                modelHint = modelHint,
                connectedState = connectedState
            )
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("رجوع")
        }
    }
}

@Composable
private fun SimpleImeiResult(
    context: Context,
    report: ImeiUnlockReport,
    modelHint: String?,
    connectedState: ConnectedLockState
) {
    val tac = TacResolver.resolve(report.imei)
    val model = tac?.model ?: modelHint ?: "غير معروف"
    val platform = PlatformResolver.resolve(model)
    val verified = report.codes.firstOrNull { it.confidence == UnlockConfidence.VERIFIED }
    val lockText = when (connectedState) {
        ConnectedLockState.LOCKED -> "مقفل"
        ConnectedLockState.UNLOCKED -> "غير مقفل"
        ConnectedLockState.UNKNOWN -> "لا يمكن معرفتها من IMEI فقط"
    }
    val canUnlock = verified != null && connectedState != ConnectedLockState.UNLOCKED

    HaiCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("النتيجة", fontWeight = FontWeight.Bold)
                Text(
                    if (report.luhnValid) "تم التشخيص" else "راجع رقم IMEI",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }

        HaiValueRow("الراوتر", model)
        HaiValueRow("المعالج", platform?.name ?: "غير معروف")
        HaiValueRow("حالة القفل", lockText)
        HaiValueRow(
            "قابل للفك",
            when {
                connectedState == ConnectedLockState.UNLOCKED -> "لا يحتاج فك"
                verified != null -> "نعم"
                else -> "غير متاح بالكود حاليًا"
            }
        )
    }

    if (canUnlock && report.luhnValid) {
        HaiCard {
            Text("رقم الفك", fontWeight = FontWeight.Bold)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            ) {
                Text(
                    verified!!.code,
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = { copyCode(context, verified.code) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text(" نسخ الرقم")
            }
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("NCK", code))
}
