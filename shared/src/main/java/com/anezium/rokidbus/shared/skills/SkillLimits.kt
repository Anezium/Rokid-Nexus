package com.anezium.rokidbus.shared.skills

/**
 * Every limit, budget, and deadline of the skills contract, in one place.
 *
 * The values are the first proposals of plan 024 and are expected to move after device
 * measurement. The hub, the SDK, and the first-party consumers read them from here, so a change
 * is made once; tests assert behavior against these names rather than repeating the numbers.
 */
object SkillLimits {
    /** Discovery: operations one provider may publish. */
    const val MAX_OPERATIONS_PER_PROVIDER = 32

    /** Discovery: UTF-8 size of one catalog resource. */
    const val MAX_CATALOG_BYTES = 64 * 1024

    /** Discovery: UTF-8 size of one operation description. */
    const val MAX_DESCRIPTION_BYTES = 2 * 1024
    const val MAX_LABEL_CHARS = 64
    const val MAX_EXAMPLES = 3
    const val MAX_EXAMPLE_CHARS = 160

    /** Schema subset bounds. Every string and array must also declare its own maximum. */
    const val MAX_SCHEMA_DEPTH = 6
    const val MAX_SCHEMA_NODES = 128
    const val MAX_OBJECT_PROPERTIES = 24
    const val MAX_ENUM_VALUES = 32
    const val MAX_STRING_LENGTH = 4_096
    const val MAX_ARRAY_ITEMS = 64
    const val MAX_SCHEMA_DESCRIPTION_CHARS = 300

    /** UTF-8 size of invocation arguments and of result data, enforced before forwarding. */
    const val MAX_ARGUMENTS_BYTES = 16 * 1024
    const val MAX_RESULT_BYTES = 16 * 1024

    /** One invocation, cold start and registration included. */
    const val INVOCATION_DEADLINE_MS = 15_000L

    /** The existing plugin registration ceiling, spent inside [INVOCATION_DEADLINE_MS]. */
    const val REGISTRATION_TIMEOUT_MS = 5_000L

    /** Live invocations across every caller; excess work is refused as busy, never queued. */
    const val MAX_LIVE_INVOCATIONS = 4

    /** Executing invocations per caller session: one skill per Assistant turn. */
    const val MAX_EXECUTING_PER_SESSION = 1

    /** Entity references: idle lifetime and how many one caller session may hold. */
    const val REFERENCE_IDLE_MS = 10L * 60L * 1000L
    const val MAX_REFERENCES_PER_SESSION = 256

    /** Duplicate ledger entries retained per caller session. */
    const val MAX_LEDGER_PER_SESSION = 128

    /** Caller sessions the hub keeps per caller; opening one more evicts the oldest. */
    const val MAX_SESSIONS_PER_CALLER = 4

    /** A caller session with no traffic for this long is closed by the hub. */
    const val SESSION_IDLE_MS = 30L * 60L * 1000L

    /** A needs-input result offers at most this many choices. */
    const val MAX_CHOICES = 8
    const val MAX_CHOICE_LABEL_CHARS = 60
    const val MAX_CHOICE_DETAIL_CHARS = 80
    const val MAX_PROMPT_CHARS = 160

    /** Operations one catalog reply exposes to a caller. */
    const val MAX_EXPOSED_OPERATIONS = 32

    /** Assistant's shared runner: tool rounds and executed calls per turn. */
    const val ASSISTANT_MAX_TOOL_ROUNDS = 4
    const val ASSISTANT_MAX_EXECUTED_CALLS = 8

    /**
     * Assistant's shared runner: wall-clock budget for chaining tool rounds, model latency
     * included. Once spent, no further tool round starts; a pass in progress is never cut.
     */
    const val ASSISTANT_TURN_DEADLINE_MS = 60_000L

    /** Assistant keeps at most this many remembered result contexts per conversation. */
    const val ASSISTANT_MAX_CONTEXT_SLOTS = 4
}
