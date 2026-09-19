package com.hai.manager.router

/**
 * Data class representing the result of a router authentication attempt.
 *
 * @property success Whether the authentication was successful.
 * @property brand The detected/confirmed router brand after authentication.
 * @property message A status or error message describing the authentication result.
 * @property cookie Optional cookie received from the router during authentication.
 * @property sessionToken Optional session token received from the router during authentication.
 */
data class RouterAuthResult(
    val success: Boolean,
    val brand: NativeRouterAuthBrand,
    val message: String,
    val cookie: String? = null,
    val sessionToken: String? = null
)
