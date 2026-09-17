package com.aiguardian.ai_guardian.policy

/**
 * Result of evaluating a package name against the PolicyEngine.
 *
 * This is the output of [PolicyEngine.evaluate]. It contains:
 * - The determined action (ALLOW or BLOCK)
 * - Whether a matching policy was found
 * - The package name that was evaluated
 * - The reason for the decision (for debugging)
 *
 * The result is always deterministic for the same inputs.
 */
data class PolicyResult(
    /** The package name that was evaluated. */
    val packageName: String,

    /** The determined action. Defaults to ALLOW for safety. */
    val action: PolicyAction = PolicyAction.ALLOW,

    /** Whether a matching enabled policy was found for this package. */
    val matched: Boolean = false,

    /** Why this decision was made (for debugging). */
    val reason: String = "",
) {
    companion object {
        /** Safe default — unknown packages are always allowed. */
        fun allow(packageName: String, reason: String = "default") = PolicyResult(
            packageName = packageName,
            action = PolicyAction.ALLOW,
            matched = false,
            reason = reason,
        )

        /** Block result with a specific reason. */
        fun block(packageName: String, reason: String) = PolicyResult(
            packageName = packageName,
            action = PolicyAction.BLOCK,
            matched = true,
            reason = reason,
        )
    }
}
