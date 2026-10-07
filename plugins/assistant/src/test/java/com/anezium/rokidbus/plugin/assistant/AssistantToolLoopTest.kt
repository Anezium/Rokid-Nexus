package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.skills.SkillLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolLoopTest {
    /** A model that answers from a script, recording what it was offered and shown. */
    private class ScriptedModel(
        private val script: (round: Int, offered: List<String>, transcript: List<String>) -> AssistantLoopPass,
        override val maxToolRounds: Int = Int.MAX_VALUE,
    ) : AssistantLoopAdapter {
        val offered = mutableListOf<List<String>>()
        val transcript = mutableListOf<String>()
        var resets = 0

        override suspend fun pass(tools: List<AssistantToolDefinition>, round: Int): AssistantLoopPass {
            offered += tools.map { it.name }
            return script(round, tools.map { it.name }, transcript.toList())
        }

        override fun appendToolResults(pass: AssistantLoopPass, results: List<Pair<AssistantToolCall, AssistantToolResult>>) {
            results.forEach { (call, result) ->
                transcript += "${call.name}:" + when (result) {
                    is AssistantToolResult.Json -> result.text
                    is AssistantToolResult.Error -> "error:${result.code}"
                    is AssistantToolResult.Image -> "image"
                }
            }
        }

        override suspend fun resetVisibleText() {
            resets += 1
        }
    }

    private fun call(name: String, arguments: String = "{}", id: String = "call-$name-${arguments.hashCode()}") =
        AssistantToolCall(id, name, arguments)

    private fun tool(
        name: String,
        sideEffecting: Boolean = false,
        executor: suspend (AssistantToolCall, JSONObject) -> AssistantToolResult = { _, _ -> AssistantToolResult.Json("{}") },
    ) = TestAssistantTool(
        name = name,
        sideEffecting = sideEffecting,
        validator = { AssistantToolValidation.Valid(JSONObject(it.ifBlank { "{}" })) },
        executor = executor,
    )

    private fun phase(vararg tools: AssistantToolDefinition) =
        AssistantToolRegistry(tools.toList()).newExecutionPhase(AssistantProviderFeatures(supportsTools = true, supportsVision = true))

    @Test
    fun `a dependent call uses the reference the previous round returned`() = runTest {
        val departuresArguments = mutableListOf<String>()
        val favorites = tool(name = "list_favorites_fake", executor = { _, _ ->
            AssistantToolResult.Json("""{"stops":[{"stop":"r_AAAAAAAAAAAAAAAAAAAAAA","name":"Central"}]}""")
        })
        val departures = tool(name = "get_departures_fake", executor = { call, _ ->
            departuresArguments += call.argumentsJson
            AssistantToolResult.Json("""{"departures":[{"time_local":"10:04"}]}""")
        })
        val model = ScriptedModel({ round, _, transcript ->
            when (round) {
                0 -> AssistantLoopPass("", listOf(call("list_favorites_fake")))
                1 -> {
                    val ref = JSONObject(transcript.last().substringAfter(':'))
                        .getJSONArray("stops").getJSONObject(0).getString("stop")
                    AssistantLoopPass("", listOf(call("get_departures_fake", """{"stop":"$ref"}""")))
                }
                else -> AssistantLoopPass("Next tram at 10:04 from Central.")
            }
        })

        val answer = AssistantToolLoop(phase(favorites, departures)).run(model)

        assertEquals("Next tram at 10:04 from Central.", answer)
        assertEquals(listOf("""{"stop":"r_AAAAAAAAAAAAAAAAAAAAAA"}"""), departuresArguments)
        assertEquals(3, model.offered.size)
        assertTrue(model.offered.all { it.isNotEmpty() })
        assertEquals(2, model.resets)
    }

    @Test
    fun `tool rounds stop at the budget and the synthesis offers no tools`() = runTest {
        val lookup = tool(name = "lookup_fake")
        var round = 0
        val model = ScriptedModel({ r, offered, _ ->
            round = r
            if (offered.isEmpty()) AssistantLoopPass("Best effort.") else AssistantLoopPass("", listOf(call("lookup_fake", "{\"n\":$r}")))
        })

        val answer = AssistantToolLoop(phase(lookup)).run(model)

        assertEquals("Best effort.", answer)
        assertEquals(SkillLimits.ASSISTANT_MAX_TOOL_ROUNDS + 1, model.offered.size)
        assertTrue(model.offered.last().isEmpty())
        assertEquals(SkillLimits.ASSISTANT_MAX_TOOL_ROUNDS, round)
    }

    @Test
    fun `an exhausted call budget ends the rounds early`() = runTest {
        val lookup = tool(name = "lookup_fake")
        val model = ScriptedModel({ r, offered, _ ->
            if (offered.isEmpty()) {
                AssistantLoopPass("Done.")
            } else {
                AssistantLoopPass("", (0 until SkillLimits.ASSISTANT_MAX_EXECUTED_CALLS).map { call("lookup_fake", "{\"r\":$r,\"i\":$it}") })
            }
        })

        AssistantToolLoop(phase(lookup)).run(model)

        assertEquals(listOf(true, false), model.offered.map { it.isNotEmpty() })
    }

    @Test
    fun `repeated rounds of invalid calls end the loop`() = runTest {
        val model = ScriptedModel({ _, offered, _ ->
            if (offered.isEmpty()) AssistantLoopPass("I could not do that.") else AssistantLoopPass("", listOf(call("no_such_tool")))
        })

        val answer = AssistantToolLoop(phase(tool(name = "real_tool"))).run(model)

        assertEquals("I could not do that.", answer)
        assertEquals(AssistantToolLoop.MAX_INVALID_ROUNDS + 1, model.offered.size)
    }

    @Test
    fun `workspace search and built-in guards survive plugin operation rounds`() = runTest {
        var deletes = 0
        var pluginCalls = 0
        val workspace = FakeWorkspaceSearchAccess()
        val delete = tool(name = "delete_calendar_event_fake", sideEffecting = true, executor = { _, _ ->
            deletes += 1
            AssistantToolResult.Json("""{"ok":true}""")
        })
        val pluginAction = object : AssistantToolDefinition by tool(name = "sk_transit__stop_journey", sideEffecting = true) {
            override val oncePerTurn: Boolean = false
            override suspend fun execute(call: AssistantToolCall, arguments: JSONObject): AssistantToolResult {
                pluginCalls += 1
                return AssistantToolResult.Json("""{"status":"completed"}""")
            }
        }
        val registry = AssistantToolRegistry(listOf(delete, SearchWorkspaceTool { workspace }),
            dynamicDefinitions = { listOf(pluginAction) })
        val phase = registry.newExecutionPhase(AssistantProviderFeatures(supportsTools = true, supportsVision = false),
            workspace.searchVersion())
        val model = ScriptedModel({ r, offered, _ ->
            when {
                offered.isEmpty() || r >= 2 -> AssistantLoopPass("Done.")
                else -> AssistantLoopPass(
                    "",
                    listOf(call("delete_calendar_event_fake", "{\"r\":$r}"), call("sk_transit__stop_journey", "{\"r\":$r}"),
                        call(SEARCH_WORKSPACE_TOOL_NAME, "{\"query\":\"notice\"}")),
                )
            }
        })

        AssistantToolLoop(phase).run(model)

        assertEquals(1, deletes)
        assertEquals(2, pluginCalls)
        assertEquals(1, workspace.executions)
        assertTrue(model.transcript.contains("delete_calendar_event_fake:error:already_used"))
        assertTrue(model.transcript.contains("search_workspace:error:already_used"))
    }

    @Test
    fun `a spent tool budget ends the rounds and the answer still follows`() = runTest {
        var clock = 0L
        val lookup = tool(name = "lookup_fake", executor = { _, _ ->
            clock += 45_000L
            AssistantToolResult.Json("{}")
        })
        val model = ScriptedModel({ r, offered, _ ->
            if (offered.isEmpty()) AssistantLoopPass("Best effort after a slow lookup.") else AssistantLoopPass("", listOf(call("lookup_fake", "{\"r\":$r}")))
        })

        val answer = AssistantToolLoop(phase(lookup), toolBudgetMs = 60_000L, monotonicMs = { clock }).run(model)

        assertEquals("Best effort after a slow lookup.", answer)
        assertEquals(listOf(true, true, false), model.offered.map { it.isNotEmpty() })
    }

    @Test
    fun `a slow answer that needs no tool is never cut`() = runTest {
        var clock = 0L
        val model = ScriptedModel({ _, _, _ ->
            clock += 120_000L
            AssistantLoopPass("A long answer, streamed past the tool budget.")
        })

        val answer = AssistantToolLoop(phase(tool(name = "lookup_fake")), toolBudgetMs = 60_000L, monotonicMs = { clock }).run(model)

        assertEquals("A long answer, streamed past the tool budget.", answer)
        assertEquals(listOf(true), model.offered.map { it.isNotEmpty() })
        assertEquals(0, model.resets)
    }

    @Test
    fun `a backend that completes one exchange gets one round`() = runTest {
        val lookup = tool(name = "lookup_fake")
        val model = ScriptedModel(
            { _, offered, _ -> if (offered.isEmpty()) AssistantLoopPass("Final.") else AssistantLoopPass("", listOf(call("lookup_fake"))) },
            maxToolRounds = 1,
        )

        assertEquals("Final.", AssistantToolLoop(phase(lookup)).run(model))
        assertEquals(listOf(true, false), model.offered.map { it.isNotEmpty() })
    }

    @Test(expected = CancellationException::class)
    fun `outer cancellation is never mistaken for a timeout`() = runTest {
        val model = ScriptedModel({ _, _, _ -> throw CancellationException("cancelled by the wearer") })
        AssistantToolLoop(phase()).run(model)
    }
}
