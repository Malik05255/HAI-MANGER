Package com.hai.manager.router

Import kotlinx.coroutines.Dispatchers
Import kotlinx.coroutines.withContext
Import kotlinx.coroutines.withTimeout

Class RouterAuthRepository(private val url: String) {
    Private val authService = RouterAuthService(url)

    Suspend fun login(username: String, password: String): RouterAuthResult {
        Return withContext(Dispatchers.IO) {
            Try {
                withTimeout(15_000) {
                    Val result = authService.login(username.trim(), password)
                    RouterAuthResult(
                        success = result.success,
                        brand = result.brand,
                        errorMessage = null
                    )
                }
            } Catch (e: Exception) {
                RouterAuthResult(
                    success = false,
                    brand = NativeRouterAuthBrand.UNKNOWN,
                    errorMessage = e.message ?: "خطأ في الاتصال بالراوتر"
                )
            }
        }
    }

    Suspend fun detectBrand(): NativeRouterAuthBrand {
        Return withContext(Dispatchers.IO) {
            Try {
                withTimeout(10_000) {
                    authService.detectBrand()
                }
            } Catch (e: Exception) {
                NativeRouterAuthBrand.UNKNOWN
            }
        }
    }
}