package com.hai.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hai.manager.router.NativeRouterAuthBrand
import com.hai.manager.router.RouterAuthService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * الاسم الداخلي بقي للتوافق مع MainActivity، لكن الشاشة Native بالكامل ولا تعرض WebUI/HTML أو عنوان الإدارة للمستخدم.
 */
@Composable
fun InAppRouterPanel(
    url: String,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val auth = remember(url) { RouterAuthService(url) }
    var brand by remember(url) { mutableStateOf(NativeRouterAuthBrand.UNKNOWN) }
    var detecting by remember(url) { mutableStateOf(true) }
    var username by remember(url) { mutableStateOf("admin") }
    var password by remember(url) { mutableStateOf("") }
    var busy by remember(url) { mutableStateOf(false) }
    var message by remember(url) { mutableStateOf<String?>(null) }
    var success by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        detecting = true
        brand = runCatching { auth.detectBrand() }.getOrDefault(NativeRouterAuthBrand.UNKNOWN)
        detecting = false
    }

    BackHandler(enabled = !busy) { onClose() }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HAI MANAGER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            when {
                detecting -> "تجهيز الراوتر…"
                brand == NativeRouterAuthBrand.UNKNOWN -> "تسجيل الدخول إلى الراوتر"
                else -> "تسجيل الدخول إلى ${brand.displayName}"
            },
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "إدارة Huawei وZTE تتم مباشرة من التطبيق. لن تظهر لك صفحة الراوتر الأصلية.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("الراوتر", if (detecting) "جارٍ التعرف…" else brand.displayName)

                if (brand != NativeRouterAuthBrand.ZTE) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("اسم المستخدم") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "ZTE يستخدم كلمة مرور الإدارة مباشرة في واجهات WebUI المدعومة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة مرور الإدارة") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            val result = auth.login(username.trim(), password)
                            busy = false
                            brand = result.brand
                            message = result.message
                            success = result.success
                            if (result.success) {
                                password = ""
                                delay(350)
                                onClose()
                            }
                        }
                    },
                    enabled = !busy && !detecting && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp)
                    else Text("تسجيل الدخول")
                }

                message?.let {
                    Text(
                        it,
                        color = if (success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("بعد تسجيل الدخول", style = MaterialTheme.typography.titleMedium)
                Text("تظهر أدوات Wi‑Fi وSIM والشبكة وBand Lock وقفل المشغل داخل HAI MANAGER حسب دعم Model + Firmware.")
                Text("كلمة المرور لا تُحفظ؛ يحتفظ التطبيق فقط بجلسة الإدارة التي يصدرها الراوتر.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("إلغاء")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        detecting = true
                        brand = runCatching { auth.detectBrand() }.getOrDefault(NativeRouterAuthBrand.UNKNOWN)
                        detecting = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("إعادة التعرف") }
        }
    }
}
