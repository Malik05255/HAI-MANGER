package com.hai.manager.unlock

enum class ConnectedLockState(val displayName: String) {
    UNLOCKED("غير مقفل"),
    LOCKED("مقفل"),
    UNKNOWN("غير معروف")
}

data class ConnectedUnlockContext(
    val state: ConnectedLockState,
    val attemptsRemaining: String? = null,
    val firmware: String? = null,
    val currentOperator: String? = null,
    val source: String? = null
) {
    val attemptsAsInt: Int?
        get() = attemptsRemaining
            ?.trim()
            ?.takeIf { it.all(Char::isDigit) }
            ?.toIntOrNull()

    val attemptsExhausted: Boolean
        get() = attemptsAsInt == 0
}
