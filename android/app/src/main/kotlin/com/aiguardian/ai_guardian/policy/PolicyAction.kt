package com.aiguardian.ai_guardian.policy

/**
 * Deterministic action for a policy rule.
 *
 * This is a simple enum — no AI, no network, no database.
 * The engine uses this to make an allow/block decision.
 */
enum class PolicyAction {
    ALLOW,
    BLOCK;

    override fun toString(): String = name
}
