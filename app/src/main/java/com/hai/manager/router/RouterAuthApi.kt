package com.hai.manager.router

import com.hai.manager.router.NativeRouterAuthBrand

/**
 * Interface for router authentication operations.
 * Provides suspend functions for brand detection and login.
 */
interface RouterAuthApi {

    /**
     * Detects the router brand without authentication.
     * @return Detected brand or UNKNOWN if detection fails
     */
    suspend fun detectBrand(): NativeRouterAuthBrand

    /**
     * Authenticates with the router using credentials.
     * @param username Router admin username
     * @param password Router admin password
     * @return Authentication result containing success status, brand, message, and session token
     */
    suspend fun login(username: String, password: String): RouterAuthResult
}
