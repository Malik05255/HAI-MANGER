package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hai.manager.router.RouterActionResult
import com.hai.manager.router.RouterSimService
import com.hai.manager.router.SimProbe
import com.hai.manager.router.SimRequiredAction
import kotlinx.coroutines.launch

class SimToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        setContent { SimToolsScreen(url = url, onClose = { finish() }) }
    }

    companion object {
        const val EXTRA_URL = "router_url"
    }
}

@Composable
private fun SimToolsScreen(url: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val service = remember { RouterSimService() }
    var loading by remember { mutableStateOf(true) }
    var probe by remember { mutableStateOf<SimProbe?>(null) }
    var pin by remember { mutableStateOf("") }
    var puk by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        probe = service.inspect(url)
        loading = false
    }

    fun runAction(block: suspend () -> RouterActionResult) {
        scope.launch {
            busy = true
            val result = block()
            message = result.message
            busy = false
            if (result.success) {
                pin = ""
                puk = ""
                newPin = ""
            }
            reload()
        }
    }

    LaunchedEffect(url) { reload() }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F2933),
            onPrimary = Color.White,
            background = Color(0xFFF6F7F5),
            surface = Color.White,
            onSurface = Color(0xFF202428)
        )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("إدارة SIM", style = MaterialTheme.typography.headlineMedium)
            Text("Huawei + ZTE فقط. لا يتم حفظ PIN أو PUK، ولا يظهر الإرسال إلا عندما يعلن الراوتر أن الرمز مطلوب.")

            when {
                loading -> CircularProgressIndicator()
                probe == null -> {
                    Text("لم تُقرأ حالة SIM. سجّل الدخول إلى الراوتر ثم أعد الفحص.")
                    Button(onClick = { scope.launch { reload() } }, modifier = Modifier.fillMaxWidth()) {
                        Text("إعادة الفحص")
                    }
                }
                else -> {
                    val current = probe!!
                    val security = current.security
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(current.brand.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(current.message)
                            security.simState?.let { Text("حالة SIM: $it") }
                            security.pinState?.let { Text("حالة PIN: $it") }
                            security.pinAttemptsRemaining?.let { Text("محاولات PIN المتبقية: $it") }
                            security.pukAttemptsRemaining?.let { Text("محاولات PUK المتبقية: $it") }
                            security.networkLockState?.let { Text("قفل الشبكة: $it") }
                            security.unlockAttemptsRemaining?.let { Text("محاولات فك قفل الشبكة المتبقية: $it") }
                            security.iccid?.let { Text("ICCID: $it") }
                            security.imsi?.let { Text("IMSI: $it") }
                        }
                    }

                    when (current.requiredAction) {
                        SimRequiredAction.PIN -> {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
                                label = { Text("PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { runAction { service.enterPin(url, current, pin) } },
                                enabled = !busy && current.canEnterPin && pin.length in 4..8,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("إرسال PIN") }
                        }

                        SimRequiredAction.PUK -> {
                            OutlinedTextField(
                                value = puk,
                                onValueChange = { value -> puk = value.filter(Char::isDigit).take(8) },
                                label = { Text("PUK من مشغل الشريحة") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newPin,
                                onValueChange = { value -> newPin = value.filter(Char::isDigit).take(8) },
                                label = { Text("PIN جديد") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { runAction { service.enterPuk(url, current, puk, newPin) } },
                                enabled = !busy && current.canEnterPuk && puk.length == 8 && newPin.length in 4..8,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("إرسال PUK وتعيين PIN جديد") }
                        }

                        SimRequiredAction.NONE -> Text("SIM جاهزة ولا تحتاج PIN أو PUK.")
                        SimRequiredAction.UNKNOWN -> Text("لن يرسل HAI MANAGER أي رمز لأن حالة SIM غير مؤكدة على هذا Firmware.")
                    }

                    if (!security.networkLockState.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("قفل الشبكة", style = MaterialTheme.typography.titleMedium)
                                Text("يعرض التطبيق حالة Network Lock والمحاولات المتبقية عندما يوفرها الراوتر. إدخال NCK لن يُفعّل إلا بعد توثيق endpoint للـFirmware المحدد؛ لن يتم التخمين على محاولات القفل.")
                            }
                        }
                    }

                    if (busy) CircularProgressIndicator()
                    message?.let { Text(it) }
                    OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("تحديث حالة SIM")
                    }
                }
            }

            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
        }
    }
}
