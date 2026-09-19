Package com.hai.manager.router

Import android.content.Context
Import kotlinx.coroutines.Dispatchers
Import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

Class RouterAuthService(private val baseUrl: String) {

    Private var authToken: String? = null
    Private var sessionId: String? = null

    /**
     * Detects the router brand from the management URL.
     * Huawei HiLink routers typically use 192.168.8.1 or contain 'huawei'/'hilink'.
     * ZTE GoForm routers typically use 192.168.0.1 or contain 'zte'/'gofor'.
     */
    Suspend fun detectBrand(): NativeRouterAuthBrand = withContext(Dispatchers.IO) {
        Return runCatching {
            Val url = URL(baseUrl)
            Val host = url.host.lowercase()
            Val path = url.path.lowercase()

            If (host.contains("huawei") || host.contains("hilink") ||
                baseUrl.contains("192.168.8", ignoreCase = true) ||
                path.contains("hilink", ignoreCase = true)) {
                NativeRouterAuthBrand.HUAWEI
            } Else If (host.contains("zte") || host.contains("gofor") ||
                baseUrl.contains("192.168.0", ignoreCase = true) ||
                path.contains("gofor", ignoreCase = true)) {
                NativeRouterAuthBrand.ZTE
            } Else {
                // Try a lightweight probe to identify the router
                probeBrand(host)
            }
        }.getOrDefault(NativeRouterAuthBrand.UNKNOWN)
    }

    Private suspend fun probeBrand(host: String): NativeRouterAuthBrand = withContext(Dispatchers.IO) {
        Return try {
            Val connection = URL("http://$host").openConnection() as HttpURLConnection
            Connection.connectTimeout = 5000
            Connection.readTimeout = 5000
            Connection.instanceFollowRedirects = true
            Connection.useCaches = false
            Connection.setRequestProperty("User-Agent", "HAI-Manager/1.0")
            Connection.setRequestProperty("Accept", "text/html,application/json")

            Val responseCode = Connection.responseCode
            Val serverHeader = Connection.getHeaderField("Server")?.lowercase() ?: ""
            val body = runCatching {
                Connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull() ?: ""
            Connection.disconnect()

            If (responseCode in 200..299) {
                If (serverHeader.contains("huawei") || body.contains("hilink", ignoreCase = true) ||
                    body.contains("huawei", ignoreCase = true)) {
                    NativeRouterAuthBrand.HUAWEI
                } Else If (serverHeader.contains("zte") || body.contains("gofor", ignoreCase = true) ||
                    body.contains("zte", ignoreCase = true)) {
                    NativeRouterAuthBrand.ZTE
                } Else {
                    NativeRouterAuthBrand.UNKNOWN
                }
            } Else {
                NativeRouterAuthBrand.UNKNOWN
            }
        } catch (e: Exception) {
            NativeRouterAuthBrand.UNKNOWN
        }
    }

    /**
     * Authenticates with the router using the detected brand's protocol.
     * Huawei uses token-based HiLink authentication.
     * ZTE uses session-based GoForm authentication.
     */
    Suspend fun login(username: String, password: String): RouterAuthResult = withContext(Dispatchers.IO) {
        Val brand = try {
            detectBrand()
        } catch (e: Exception) {
            Return RouterAuthResult(false, NativeRouterAuthBrand.UNKNOWN, message = "تعذر اكتشاف الشركة: ${e.message}")
        }

        Return when (brand) {
            NativeRouterAuthBrand.HUAWEI -> loginHuawei(username, password)
            NativeRouterAuthBrand.ZTE -> loginZte(username, password)
            NativeRouterAuthBrand.UNKNOWN -> RouterAuthResult(false, NativeRouterAuthBrand.UNKNOWN, message = "الراوتر غير مدعوم")
        }
    }

    /**
     * Huawei HiLink token-based authentication.
     * Steps:
     * 1. Fetch the login page to extract the CSRF token.
     * 2. POST credentials with the token to obtain a session token.
     */
    Private suspend fun loginHuawei(username: String, password: String): RouterAuthResult = withContext(Dispatchers.IO) {
        Return try {
            val url = URL(baseUrl)
            val host = url.host

            // Step 1: Get the token from the router
            val token = fetchHuaweiToken(host)
            If (token == null) {
                Return RouterAuthResult(false, NativeRouterAuthBrand.HUAWEI, message = "تعذر استخراج رمز المصادقة من الراوتر")
            }

            // Step 2: Authenticate with the token
            val sessionToken = authenticateHuawei(host, token, username, password)
            If (sessionToken == null) {
                Return RouterAuthResult(false, NativeRouterAuthBrand.HUAWEI, message = "فشل تسجيل الدخول: بيانات غير صحيحة")
            }

            authToken = sessionToken
            RouterAuthResult(true, NativeRouterAuthBrand.HUAWEI, token = sessionToken, message = "تم تسجيل الدخول بنجاح")
        } catch (e: Exception) {
            RouterAuthResult(false, NativeRouterAuthBrand.HUAWEI, message = "خطأ في الاتصال: ${e.message}")
        }
    }

    /**
     * ZTE GoForm session-based authentication.
     * Steps:
     * 1. Establish a session by hitting the login endpoint.
     * 2. Send credentials and retrieve a session ID.
     */
    Private suspend fun loginZte(username: String, password: String): RouterAuthResult = withContext(Dispatchers.IO) {
        Return try {
            val url = URL(baseUrl)
            val host = url.host

            // Step 1: Establish session
            val sessionCookie = establishZteSession(host)
            If (sessionCookie == null) {
                Return RouterAuthResult(false, NativeRouterAuthBrand.ZTE, message = "تعذر إنشاء جلسة مع الراوتر")
            }

            // Step 2: Authenticate
            val sessionId = authenticateZte(host, sessionCookie, username, password)
            If (sessionId == null) {
                Return RouterAuthResult(false, NativeRouterAuthBrand.ZTE, message = "فشل تسجيل الدخول: بيانات غير صحيحة")
            }

            sessionId = sessionId
            RouterAuthResult(true, NativeRouterAuthBrand.ZTE, sessionId = sessionId, message = "تم تسجيل الدخول بنجاح")
        } catch (e: Exception) {
            RouterAuthResult(false, NativeRouterAuthBrand.ZTE, message = "خطأ في الاتصال: ${e.message}")
        }
    }

    // --- Huawei helpers ---

    Private suspend fun fetchHuaweiToken(host: String): String? = withContext(Dispatchers.IO) {
        Return try {
            val connection = URL("http://$host").openConnection() as HttpURLConnection
            Connection.connectTimeout = 5000
            Connection.readTimeout = 5000
            Connection.instanceFollowRedirects = true
            Connection.useCaches = false
            Connection.setRequestProperty("User-Agent", "HAI-Manager/1.0")
            Connection.setRequestProperty("Accept", "text/html")

            If (Connection.responseCode!in 200..299) {
                Connection.disconnect()
                Return@runCatching null
            }

            val body = Connection.inputStream.bufferedReader().use { it.readText() }
            Connection.disconnect()

            // Extract token from HTML response
            // Huawei HiLink typically embeds a token in meta tags or JavaScript
            Regex("name=\"token\" content=\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let {
                Return@runCatching it
            } ?: run {
                // Alternative: look for token in JavaScript variables
                Regex("token['\":\s]*([a-zA-Z0-9]{16,})").find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    Private suspend fun authenticateHuawei(host: String, token: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        Return try {
            val connection = URL("http://$host/api/login").openConnection() as HttpURLConnection
            Connection.connectTimeout = 5000
            Connection.readTimeout = 5000
            Connection.instanceFollowRedirects = true
            Connection.useCaches = false
            Connection.requestMethod = "POST"
            Connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            Connection.setRequestProperty("User-Agent", "HAI-Manager/1.0")
            Connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            Connection.setRequestProperty("Accept", "application/json")

            val postData = "username=$username&password=$password&token=$token"
            Connection.doOutput = true
            Connection.outputStream.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }

            val responseCode = Connection.responseCode
            val responseBody = Connection.inputStream.bufferedReader().use { it.readText() }
            Connection.disconnect()

            If (responseCode in 200..299) {
                // Parse token from JSON response
                Regex("\"token\":\s*\"([^\"]+)\"").find(responseBody)?.groupValues?.get(1)
            } Else null
        } catch (e: Exception) {
            null
        }
    }

    // --- ZTE helpers ---

    Private suspend fun establishZteSession(host: String): String? = withContext(Dispatchers.IO) {
        Return try {
            val connection = URL("http://$host").openConnection() as HttpURLConnection
            Connection.connectTimeout = 5000
            Connection.readTimeout = 5000
            Connection.instanceFollowRedirects = true
            Connection.useCaches = false
            Connection.setRequestProperty("User-Agent", "HAI-Manager/1.0")
            Connection.setRequestProperty("Accept", "text/html")

            If (Connection.responseCode!in 200..299) {
                Connection.disconnect()
                Return@runCatching null
            }

            // Extract session cookie from response headers
            val cookies = Connection.headerFields["Set-Cookie"]
            Connection.disconnect()

            cookies?.firstOrNull()?.let { cookie ->
                // Extract session ID from cookie
                Regex("sessionId=([^;]+)").find(cookie)?.groupValues?.get(1) ?:
                Regex("JSESSIONID=([^;]+)").find(cookie)?.groupValues?.get(1) ?:
                cookie.takeBefore(";")
            }
        } catch (e: Exception) {
            null
        }
    }

    Private suspend fun authenticateZte(host: String, sessionCookie: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        Return try {
            val connection = URL("http://$host/goform/login").openConnection() as HttpURLConnection
            Connection.connectTimeout = 5000
            Connection.readTimeout = 5000
            Connection.instanceFollowRedirects = true
            Connection.useCaches = false
            Connection.requestMethod = "POST"
            Connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            Connection.setRequestProperty("User-Agent", "HAI-Manager/1.0")
            Connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            Connection.setRequestProperty("Cookie", sessionCookie)
            Connection.setRequestProperty("Accept", "application/json")

            val postData = "username=$username&password=$password"
            Connection.doOutput = true
            Connection.outputStream.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }

            val responseCode = Connection.responseCode
            val responseBody = Connection.inputStream.bufferedReader().use { it.readText() }
            Connection.disconnect()

            If (responseCode in 200..299) {
                // Parse session ID from response
                Regex("\"sessionId\":\s*\"([^\"]+)\"").find(responseBody)?.groupValues?.get(1) ?:
                Regex("sessionId=([^&"]+)").find(responseBody)?.groupValues?.get(1) ?:
                "authenticated"
            } Else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the current authentication token if available.
     */
    Fun getAuthToken(): String? = authToken

    /**
     * Returns the current session ID if available.
     */
    Fun getSessionId(): String? = sessionId

    /**
     * Checks if the user is currently authenticated.
     */
    Fun isAuthenticated(): Boolean = (authToken != null || sessionId != null)

    /**
     * Clears the current authentication state.
     */
    Fun logout() {
        authToken = null
        sessionId = null
    }
}