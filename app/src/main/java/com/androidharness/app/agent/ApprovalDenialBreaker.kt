package com.androidharness.app.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * The denial breaker (Hermes' pattern): after N consecutive permission
 * denials in a session, the denial result itself carries a nudge telling the
 * model to stop re-requesting the same action. In-memory on purpose — this
 * is a session-scoped de-escalation signal, not durable state. Any granted
 * approval ends the streak.
 */
object ApprovalDenialBreaker {
    const val THRESHOLD = 3

    private class State(var count: Int = 0)
    private val states = ConcurrentHashMap<String, State>()

    /** Records a denial and returns the nudge text once the threshold is met. */
    fun recordDenial(sessionKey: String): String? {
        val state = states.getOrPut(sessionKey) { State() }
        state.count += 1
        return if (state.count >= THRESHOLD) addendum(state.count) else null
    }

    /** Any granted approval ends the denial streak. */
    fun reset(sessionKey: String) {
        states.remove(sessionKey)
    }

    fun addendum(count: Int): String =
        "\nApproval denial breaker: the user has denied $count consecutive permission requests in this session. " +
            "Do not request the same action again — change the approach, work within what was approved, " +
            "or ask a clarifying question instead of another permission request."
}
