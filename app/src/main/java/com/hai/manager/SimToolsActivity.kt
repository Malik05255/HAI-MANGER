package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
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
        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SimToolsScreen(url = url, onClose = { finish() })
                }
            }
        }
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

    HaiPage(title = "SIM") {
        when {
            loading -> HaiCard { CircularProgressIndicator() }
            probe == null -> HaiCard {
                Text("تعذر قراءة SIM")
                Button(onClick = { scope.launch { reload() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة المحاولة")
                }
            }
            else -> {
                val current = probe!!
                val security = current.security

                HaiCard {
                    HaiSectionTitle("الحالة")
                    HaiValueRow("SIM", security.simState)
                    HaiValueRow("PIN", security.pinState)
                    HaiValueRow("قفل الشبكة", security.networkLockState)
                    HaiValueRow("محاولات PIN", security.pinAttemptsRemaining)
                    HaiValueRow("محاولات PUK", security.pukAttemptsRemaining)
                    HaiValueRow("محاولات الفك", security.unlockAttemptsRemaining)
                }

                when (current.requiredAction) {
                    SimRequiredAction.PIN -> HaiCard {
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
                        ) { Text("إرسال") }
                    }

                    SimRequiredAction.PUK -> HaiCard {
                        OutlinedTextField(
                            value = puk,
                            onValueChange = { value -> puk = value.filter(Char::isDigit).take(8) },
                            label = { Text("PUK") },
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
                        ) { Text("إرسال") }
                    }

                    SimRequiredAction.NONE -> HaiStatusChip("SIM جاهزة")
                    SimRequiredAction.UNKNOWN -> HaiStatusChip("الحالة غير معروفة", active = false)
                }

                if (busy) CircularProgressIndicator()
                message?.let { Text(it) }
            }
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
    }
}
