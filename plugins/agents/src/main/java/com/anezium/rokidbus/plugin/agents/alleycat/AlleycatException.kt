package com.anezium.rokidbus.plugin.agents.alleycat

/**
 * Fail-closed Alleycat / Codex wire error. Messages must never include a
 * pairing or handshake token.
 */
class AlleycatException(message: String, cause: Throwable? = null) : Exception(message, cause)
