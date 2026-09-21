package com.triplethreats.masteria.ai

import com.triplethreats.masteria.AppJson
import com.triplethreats.masteria.learner.Answers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/** A generated question after lenient parsing, before validation (context.md §9.4). */
data class GeneratedQuestion(
    val topic: String?,
    val difficulty: Int,
    val type: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int?,
    val answerText: String?,
    val explanation: String,
    val hint: String,
    val code: String? = null,
)

object AiJson {
    private val fence = Regex("(?s)```(?:json|JSON)?\\s*(.*?)```")

    /** Pulls a JSON value out of model text: a ```json fence, else first `{` … last `}` (or `[` … `]`). */
    fun extract(text: String): JsonElement? {
        val cleaned = NimClient.stripThink(text)
        val candidates = mutableListOf<String>()
        fence.findAll(cleaned).forEach { candidates += it.groupValues[1].trim() }
        val o1 = cleaned.indexOf('{'); val o2 = cleaned.lastIndexOf('}')
        if (o1 >= 0 && o2 > o1) candidates += cleaned.substring(o1, o2 + 1)
        val a1 = cleaned.indexOf('['); val a2 = cleaned.lastIndexOf(']')
        if (a1 >= 0 && a2 > a1) candidates += cleaned.substring(a1, a2 + 1)
        for (c in candidates) {
            val parsed = runCatching { AppJson.parseToJsonElement(c) }.getOrNull()
            if (parsed is JsonObject || parsed is JsonArray) return parsed
        }
        return null
    }

    private fun JsonElement?.string(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private fun JsonElement?.int(): Int? = when (this) {
        is JsonPrimitive -> intOrNull ?: contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun parseQuestion(obj: JsonObject): GeneratedQuestion? = runCatching {
        val options = (obj["options"] as? JsonArray)?.mapNotNull { it.string()?.trim() } ?: emptyList()
        GeneratedQuestion(
            topic = obj["topic"].string(),
            difficulty = obj["difficulty"].int() ?: 1,
            type = obj["type"].string()?.lowercase() ?: "mcq",
            question = obj["question"].string()?.trim() ?: return null,
            options = options,
            answerIndex = obj["answerIndex"].int(),
            answerText = obj["answerText"].string()?.trim(),
            explanation = obj["explanation"].string()?.trim() ?: "",
            hint = obj["hint"].string()?.trim() ?: "",
            code = obj["code"].string()?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Accepts {"questions":[...]}, a bare array, or a single question object. */
    fun parseQuestions(text: String): List<GeneratedQuestion> {
        val el = extract(text) ?: return emptyList()
        val items: List<JsonElement> = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["questions"] as? JsonArray) ?: listOf(el)
            else -> emptyList()
        }
        return items.mapNotNull { (it as? JsonObject)?.let(::parseQuestion) }
    }

    fun topicName(text: String): String? =
        ((extract(text) as? JsonObject)?.get("topicName") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

object QuestionValidator {
    /** Step 1: schema check. Returns null when OK, else the reason. */
    fun schemaProblem(q: GeneratedQuestion): String? {
        if (q.difficulty !in 1..3) return "difficulty out of range"
        if (q.question.length !in 8..500) return "question length"
        if (q.explanation.isBlank() || q.explanation.length > 700) return "explanation length"
        if (q.hint.isBlank() || q.hint.length > 300) return "hint length"
        return when (q.type) {
            "mcq" -> {
                if (q.options.size != 4) return "needs exactly 4 options"
                if (q.options.any { it.isBlank() || it.length > 160 }) return "option length"
                val normalized = q.options.map { it.lowercase().replace(Regex("\\s+"), " ").trim() }
                if (normalized.toSet().size != 4) return "options not distinct"
                val numeric = q.options.map { Answers.parseNumber(it) }
                if (numeric.all { it != null } && numeric.map { Math.round(it!! * 1e6) }.toSet().size != 4) return "numerically equal options"
                if (q.answerIndex == null || q.answerIndex !in 0..3) return "answerIndex out of range"
                if (normalized.any { it.contains("all of the above") || it.contains("none of the above") }) return "all/none of the above"
                null
            }
            "numeric" -> if (Answers.parseNumber(q.answerText) == null) "numeric answer not a number" else null
            else -> "unknown type"
        }
    }

    /** Parses the verifier's reply into a 0-based option index. */
    fun parseVerifierChoice(reply: String): Int? {
        val t = NimClient.stripThink(reply).trim()
        Regex("^\\(?([1-4])\\)?[.)]?$").find(t)?.let { return it.groupValues[1].toInt() - 1 }
        Regex("^\\(?([A-Da-d])\\)?[.)]?$").find(t)?.let { return it.groupValues[1].uppercase()[0] - 'A' }
        Regex("(?i)(?:answer|option)\\s*(?:is|:)?\\s*\\(?([1-4])\\b").findAll(t).lastOrNull()?.let { return it.groupValues[1].toInt() - 1 }
        Regex("\\b([1-4])\\b").find(t)?.let { return it.groupValues[1].toInt() - 1 }
        return null
    }

    /** Parses the verifier's reply to a numeric question. */
    fun parseVerifierNumber(reply: String): Double? {
        val t = NimClient.stripThink(reply).trim()
        Answers.parseNumber(t)?.let { return it }
        return Regex("-?\\d+(?:\\.\\d+)?(?:/\\d+)?").findAll(t).lastOrNull()?.value?.let { Answers.parseNumber(it) }
    }

    enum class MathCheck { PASS, FAIL, NOT_APPLICABLE }

    private val asksForX = Regex("(?i)(solve( for x)?\\b|value of x\\b|find x\\b|find the value of x\\b|x\\s*=\\s*\\?|what is x\\b|then x\\s*=)")
    private val asksForExpression = Regex("(?i)value of\\s*(\\d|x\\s*[-+*/^²]|\\(|\\d*x\\s*[-+])")
    private val equation = Regex("(?<![A-Za-z])[0-9x(][0-9x+\\-*/().\\s×÷−]*=[\\s0-9x+\\-*/().×÷−]*[0-9x)](?![A-Za-z])")

    /** Step 3: if the question is a linear equation in x, solve it in Kotlin and compare with the keyed option. */
    fun linearCheck(q: GeneratedQuestion): MathCheck {
        if (!asksForX.containsMatchIn(q.question) || asksForExpression.containsMatchIn(q.question)) return MathCheck.NOT_APPLICABLE
        val keyed = when (q.type) {
            "mcq" -> q.answerIndex?.let { q.options.getOrNull(it) }
            else -> q.answerText
        } ?: return MathCheck.NOT_APPLICABLE
        val expectedValue = Answers.parseNumber(keyed) ?: return MathCheck.NOT_APPLICABLE
        val eq = equation.findAll(q.question).map { it.value.trim() }.firstOrNull { it.contains('x') } ?: return MathCheck.NOT_APPLICABLE
        val solved = LinearSolver.solve(eq) ?: return MathCheck.NOT_APPLICABLE
        return if (abs(solved - expectedValue) <= 1e-6) MathCheck.PASS else MathCheck.FAIL
    }
}

/** Tiny recursive-descent solver for linear equations in x, e.g. "3x + 5 = 20", "2(x - 3) = x/2 + 1". */
object LinearSolver {
    private data class Lin(val a: Double, val b: Double)
    private class NonLinear : RuntimeException()

    fun solve(equation: String): Double? {
        val parts = equation.split('=')
        if (parts.size != 2) return null
        return try {
            val l = Parser(parts[0]).parseAll()
            val r = Parser(parts[1]).parseAll()
            val a = l.a - r.a
            val b = r.b - l.b
            if (abs(a) < 1e-12) null else b / a
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(src: String) {
        private val s = src.replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "")
        private var i = 0

        fun parseAll(): Lin {
            if (s.isEmpty()) throw IllegalArgumentException()
            val v = expr()
            if (i != s.length) throw IllegalArgumentException()
            return v
        }

        private fun peek() = if (i < s.length) s[i] else '\u0000'

        private fun expr(): Lin {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val t = term()
                v = if (op == '+') Lin(v.a + t.a, v.b + t.b) else Lin(v.a - t.a, v.b - t.b)
            }
            return v
        }

        private fun term(): Lin {
            var v = factor()
            while (true) {
                val c = peek()
                v = when {
                    c == '*' -> { i++; mul(v, factor()) }
                    c == '/' -> { i++; div(v, factor()) }
                    c == 'x' || c == '(' || c.isDigit() || c == '.' -> mul(v, factor()) // implicit: 3x, 2(x+1)
                    else -> return v
                }
            }
        }

        private fun factor(): Lin {
            val c = peek()
            return when {
                c == '+' -> { i++; factor() }
                c == '-' -> { i++; val f = factor(); Lin(-f.a, -f.b) }
                c == 'x' -> { i++; Lin(1.0, 0.0) }
                c == '(' -> {
                    i++
                    val v = expr()
                    if (peek() != ')') throw IllegalArgumentException()
                    i++
                    v
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    Lin(0.0, s.substring(start, i).toDouble())
                }
                else -> throw IllegalArgumentException()
            }
        }

        private fun mul(x: Lin, y: Lin): Lin {
            if (x.a != 0.0 && y.a != 0.0) throw NonLinear()
            return Lin(x.a * y.b + y.a * x.b, x.b * y.b)
        }

        private fun div(x: Lin, y: Lin): Lin {
            if (y.a != 0.0 || y.b == 0.0) throw NonLinear()
            return Lin(x.a / y.b, x.b / y.b)
        }
    }
}
