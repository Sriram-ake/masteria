package com.triplethreats.masteria

import com.triplethreats.masteria.ai.AiJson
import com.triplethreats.masteria.ai.GeneratedQuestion
import com.triplethreats.masteria.ai.LinearSolver
import com.triplethreats.masteria.ai.NimClient
import com.triplethreats.masteria.ai.QuestionValidator
import com.triplethreats.masteria.ai.QuestionValidator.MathCheck
import com.triplethreats.masteria.ai.ThinkFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationTest {
    private val good = GeneratedQuestion(
        topic = "math.linear_equations", difficulty = 2, type = "mcq",
        question = "Solve for x: 3x + 5 = 20", options = listOf("3", "5", "15", "25/3"), answerIndex = 1,
        answerText = null, explanation = "Subtract 5 from both sides to get 3x = 15, then divide by 3.",
        hint = "First isolate the term with x.",
    )

    @Test fun schemaAcceptsGoodQuestion() = assertNull(QuestionValidator.schemaProblem(good))

    @Test fun schemaRejectsBadShapes() {
        assertNotNull(QuestionValidator.schemaProblem(good.copy(options = listOf("1", "2", "3"))))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(options = listOf("3", "5", "5", "7"))))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(options = listOf("3", "5", "5.0", "7")))) // numerically equal
        assertNotNull(QuestionValidator.schemaProblem(good.copy(answerIndex = 4)))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(answerIndex = null)))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(difficulty = 5)))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(hint = "")))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(explanation = "")))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(question = "x?")))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(options = listOf("a", "b", "c", "All of the above"))))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(type = "essay")))
        assertNull(QuestionValidator.schemaProblem(good.copy(type = "numeric", options = emptyList(), answerIndex = null, answerText = "5")))
        assertNotNull(QuestionValidator.schemaProblem(good.copy(type = "numeric", options = emptyList(), answerIndex = null, answerText = "five")))
    }

    @Test fun linearSolver() {
        assertEquals(5.0, LinearSolver.solve("3x + 5 = 20")!!, 1e-9)
        assertEquals(8.0, LinearSolver.solve("2(x - 3) = 10")!!, 1e-9)
        assertEquals(-2.0, LinearSolver.solve("4x + 7 = 2x + 3")!!, 1e-9)
        assertEquals(12.0, LinearSolver.solve("x/3 + 1 = 5")!!, 1e-9)
        assertEquals(2.5, LinearSolver.solve("-2x + 10 = 5")!!, 1e-9)
        assertNull(LinearSolver.solve("x*x = 4"))
        assertNull(LinearSolver.solve("2x = 2x"))
        assertNull(LinearSolver.solve("3 + = 4"))
    }

    @Test fun linearCheckInQuestion() {
        assertEquals(MathCheck.PASS, QuestionValidator.linearCheck(good))
        assertEquals(MathCheck.FAIL, QuestionValidator.linearCheck(good.copy(answerIndex = 2)))
        assertEquals(MathCheck.PASS, QuestionValidator.linearCheck(good.copy(
            question = "If 2(x - 3) = 10, find the value of x.", options = listOf("5", "8", "6.5", "13"), answerIndex = 1)))
        // asks for an expression, not x → not applicable
        assertEquals(MathCheck.NOT_APPLICABLE, QuestionValidator.linearCheck(good.copy(
            question = "If 3x + 5 = 20, what is the value of 2x + 1?", options = listOf("11", "9", "10", "12"), answerIndex = 0)))
        // non-numeric option → not applicable
        assertEquals(MathCheck.NOT_APPLICABLE, QuestionValidator.linearCheck(good.copy(options = listOf("a", "b", "c", "d"))))
        // not an equation question
        assertEquals(MathCheck.NOT_APPLICABLE, QuestionValidator.linearCheck(good.copy(question = "What is 15% of 200?")))
    }

    @Test fun verifierReplyParsing() {
        assertEquals(1, QuestionValidator.parseVerifierChoice("2"))
        assertEquals(2, QuestionValidator.parseVerifierChoice("<think>maybe 1</think>3"))
        assertEquals(0, QuestionValidator.parseVerifierChoice("A"))
        assertEquals(3, QuestionValidator.parseVerifierChoice("The answer is option 4."))
        assertNull(QuestionValidator.parseVerifierChoice("I don't know"))
        assertEquals(2.5, QuestionValidator.parseVerifierNumber("x = 5/2"))
        assertEquals(42.0, QuestionValidator.parseVerifierNumber("So the answer is 42"))
    }

    @Test fun jsonExtraction() {
        val fenced = "Sure!\n```json\n{\"questions\":[{\"difficulty\":\"2\",\"type\":\"mcq\",\"question\":\"Solve for x: 3x + 5 = 20\"," +
            "\"options\":[3,5,15,\"25/3\"],\"answerIndex\":\"1\",\"explanation\":\"e\",\"hint\":\"h\"}]}\n```"
        val qs = AiJson.parseQuestions(fenced)
        assertEquals(1, qs.size)
        assertEquals(2, qs[0].difficulty)
        assertEquals(1, qs[0].answerIndex)
        assertEquals(listOf("3", "5", "15", "25/3"), qs[0].options)

        val bare = "<think>hmm</think> Here: {\"topicName\":\"Newton's First Law\",\"questions\":[]} done"
        assertEquals("Newton's First Law", AiJson.topicName(bare))
        assertTrue(AiJson.parseQuestions("no json here").isEmpty())
    }

    @Test fun thinkStripping() {
        assertEquals("answer", NimClient.stripThink("<think>long reasoning</think>\nanswer"))
        assertEquals("answer", NimClient.stripThink("reasoning without open tag</think>answer"))
        val f = ThinkFilter()
        val out = listOf("<thi", "nk>secret", " stuff</th", "ink>Hello", " world").joinToString("") { f.accept(it) }
        assertEquals("Hello world", out)
        val g = ThinkFilter()
        assertEquals("Hi there", listOf("Hi", " there").joinToString("") { g.accept(it) })
    }

    @Test fun deltaAndContentExtraction() {
        assertEquals("Hi", NimClient.extractDelta("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertNull(NimClient.extractDelta("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals("ok", NimClient.extractContent("""{"choices":[{"message":{"role":"assistant","content":"<think>x</think>ok"}}]}"""))
        assertNull(NimClient.extractContent("""{"choices":[{"message":{"content":null,"reasoning_content":"..."}}]}"""))
    }

    @Test fun dotEnvParsing() {
        val env = DotEnv.parse("""
            # comment
            PORT=9090
            export NIM_BASE_URL="https://x/v1"
            EMPTY=
            QUOTED='a b'
            TRAILING=value # note
        """.trimIndent())
        assertEquals("9090", env["PORT"])
        assertEquals("https://x/v1", env["NIM_BASE_URL"])
        assertEquals("", env["EMPTY"])
        assertEquals("a b", env["QUOTED"])
        assertEquals("value", env["TRAILING"])
    }
}
