package com.friday.app

import com.friday.app.ai.Action
import com.friday.app.ai.AiAction
import com.friday.app.ai.AiActions
import com.friday.app.ai.AiException
import com.friday.app.ai.OpenAiCompatClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OnlineAiTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test fun readsJsonEvenWhenWrappedInProseAndFences() {
        val r = AiActions.parse(
            "Sure!\n```json\n{\"reply\":\"Done\",\"actions\":[{\"type\":\"CREATE_TASK\",\"title\":\"Buy milk\",\"date\":\"2026-11-19\",\"time\":\"9:30\",\"extra\":1}]}\n```",
        )!!
        assertEquals("Done", r.reply)
        val a = AiActions.validate(r.actions[0], emptySet(), today) as Action.Create
        assertEquals("Buy milk", a.title)
        assertEquals(LocalDate.of(2026, 11, 19), a.date)
        assertEquals(LocalTime.of(9, 30), a.time)
        assertNull(AiActions.parse("no json here"))
    }

    @Test fun onlySafeValidatedActionsSurvive() {
        // Unknown task ids are ignored; known ones pass.
        assertNull(AiActions.validate(AiAction(type = "COMPLETE_TASK", taskId = 99), setOf(1), today))
        assertNotNull(AiActions.validate(AiAction(type = "COMPLETE_TASK", taskId = 1), setOf(1), today))
        // FRIDAY never deletes, whatever the AI asks for.
        assertNull(AiActions.validate(AiAction(type = "DELETE_TASK", taskId = 1), setOf(1), today))
        // A reminder needs a time; a task needs a title.
        assertNull(AiActions.validate(AiAction(type = "CREATE_REMINDER", title = "Call bank", date = "2026-11-19"), emptySet(), today))
        assertNull(AiActions.validate(AiAction(type = "CREATE_TASK", title = "   "), emptySet(), today))
        // Out-of-range values are rejected or dropped.
        assertNull(AiActions.validate(AiAction(type = "SNOOZE_REMINDER", taskId = 1, minutes = 0), setOf(1), today))
        assertNull(AiActions.time("25:00"))
        val old = AiActions.validate(AiAction(type = "CREATE_TASK", title = "x", date = "2020-01-01"), emptySet(), today) as Action.Create
        assertNull(old.date)
        // An update that changes nothing is not a proposal.
        assertNull(AiActions.validate(AiAction(type = "UPDATE_TASK", taskId = 1), setOf(1), today))
        val steps = AiActions.validate(AiAction(type = "CREATE_SUBTASKS", taskId = 1, steps = listOf(" Open laptop ", "", "Write intro")), setOf(1), today) as Action.Steps
        assertEquals(listOf("Open laptop", "Write intro"), steps.steps)
    }

    @Test fun clientSendsOpenAiCompatibleRequest() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"hi\"}"}}]}"""))
        server.start()
        try {
            val out = OpenAiCompatClient().chat(server.url("/v1beta/openai/").toString(), "k123", "gemini-flash-latest", listOf("user" to "hello"))
            assertEquals("{\"reply\":\"hi\"}", out)
            val req = server.takeRequest()
            assertEquals("/v1beta/openai/chat/completions", req.path)
            assertEquals("Bearer k123", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"model\":\"gemini-flash-latest\""))
            assertTrue(body.contains("\"json_object\""))
            assertTrue(body.contains("\"content\":\"hello\""))
        } finally {
            server.shutdown()
        }
    }

    @Test fun clientExplainsFailures() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"model not found\"}"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"API key not valid. Please pass a valid API key.\"}}"))
        server.start()
        val base = server.url("/").toString()
        try {
            for (expected in listOf(AiException.Reason.AUTH, AiException.Reason.RATE_LIMIT, AiException.Reason.MODEL, AiException.Reason.AUTH)) {
                try {
                    OpenAiCompatClient().chat(base, "k", "m", listOf("user" to "x"))
                    fail("expected $expected")
                } catch (e: AiException) {
                    assertEquals(expected, e.reason)
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun reminderWithoutTimeRejectedEvenWithSpaces() {
        assertNull(AiActions.validate(AiAction(type = " CREATE_REMINDER ", title = "x"), emptySet(), java.time.LocalDate.of(2026, 11, 20)))
    }

    @Test fun rememberValidated() {
        assertEquals(Action.Remember("Gym after 7 PM"), AiActions.validate(AiAction(type = "REMEMBER", text = "  Gym after 7 PM "), emptySet(), today))
        assertNull(AiActions.validate(AiAction(type = "REMEMBER", text = "  "), emptySet(), today))
    }

    @Test fun prepareLinesIgnoresUnknownIdsAndTrims() {
        val long = "x".repeat(200)
        val raw = "Sure {\"lines\":[{\"cueId\":1,\"text\":\"  Short and warm  \"},{\"cueId\":99,\"text\":\"no\"},{\"cueId\":2,\"text\":\"$long\"},{\"cueId\":3,\"text\":\"   \"}]}"
        val m = AiActions.parseLines(raw, setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L), m.keys)
        assertEquals("Short and warm", m[1L])
        assertEquals(90, m[2L]!!.length)
    }

    @Test fun thinkIsParsedButNotReply() {
        val r = AiActions.parse("""{"think":"user is tired","reply":"Rest first.","actions":[]}""")!!
        assertEquals("Rest first.", r.reply)
        assertEquals("user is tired", r.think)
    }
}
