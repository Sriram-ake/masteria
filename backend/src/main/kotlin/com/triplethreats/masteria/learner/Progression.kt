package com.triplethreats.masteria.learner

import java.time.LocalDate
import kotlin.math.roundToInt

/** Diagnostic scoring: weights 1/2/3 by difficulty, mastery = 15 + round(75 × score). */
object Diagnostic {
    const val TEST_OUT_MASTERY = 85

    fun weight(difficulty: Int) = difficulty.coerceIn(1, 3)

    /** [answers] = (difficulty, correct) for one topic. */
    fun score(answers: List<Pair<Int, Boolean>>): Double {
        val total = answers.sumOf { weight(it.first) }
        if (total == 0) return 0.0
        return answers.filter { it.second }.sumOf { weight(it.first) }.toDouble() / total
    }

    fun mastery(score: Double): Int = (15 + (75 * score).roundToInt()).coerceIn(0, 100)

    fun testedOut(mastery: Int) = mastery >= TEST_OUT_MASTERY
}

data class XpInput(
    val isBoss: Boolean,
    val bossDefeated: Boolean,
    val avgDifficulty: Double,
    val masteryGain: Int,
    val wasMastered: Boolean,
    val perfect: Boolean,
    val firstQuestToday: Boolean,
)

data class XpLine(val label: String, val xp: Int)
data class XpResult(val lines: List<XpLine>, val total: Int) {
    val coins get() = total / 10
}

/** XP rules (context.md F-22/F-23). */
object Rewards {
    const val BOSS_WIN = 500
    const val BOSS_ATTEMPT = 50
    const val PERFECT = 100
    const val DAILY_STREAK = 25
    const val SKILL_MASTERY = 250

    fun difficultyLabel(d: Int) = when (d) { 1 -> "easy"; 2 -> "medium"; else -> "hard" }

    fun baseXp(avgDifficulty: Double): Int = when (avgDifficulty.roundToInt().coerceIn(1, 3)) {
        1 -> 50
        2 -> 100
        else -> 200
    }

    fun questXp(input: XpInput): XpResult {
        val lines = mutableListOf<XpLine>()
        val base = when {
            input.isBoss && input.bossDefeated -> BOSS_WIN
            input.isBoss -> BOSS_ATTEMPT
            else -> baseXp(input.avgDifficulty)
        }
        val baseLabel = when {
            input.isBoss && input.bossDefeated -> "Boss defeated"
            input.isBoss -> "Boss attempt"
            else -> "Quest reward (${difficultyLabel(input.avgDifficulty.roundToInt().coerceIn(1, 3))})"
        }
        lines += XpLine(baseLabel, base)
        val gain = input.masteryGain.coerceAtLeast(0)
        val boosted = (base * (1 + gain / 20.0)).roundToInt()
        if (boosted > base) lines += XpLine("Mastery gain +$gain", boosted - base)
        if (input.wasMastered) {
            val halved = (boosted * 0.5).roundToInt()
            lines += XpLine("Mastered topic replay ×0.5", halved - boosted)
        }
        if (input.perfect) lines += XpLine("Perfect score", PERFECT)
        if (input.firstQuestToday) lines += XpLine("Daily streak", DAILY_STREAK)
        if (input.isBoss && input.bossDefeated) lines += XpLine("Skill mastery", SKILL_MASTERY)
        return XpResult(lines, lines.sumOf { it.xp })
    }
}

data class LevelInfo(
    val level: Int,
    val xp: Int,
    val levelStartXp: Int,
    val nextLevelXp: Int,
    val cappedByBoss: Boolean,
    val capMessage: String?,
    val xpLevel: Int,
)

/** Levels need XP and boss wins: effective level = min(xpLevel, 2 + bossesDefeated). */
object Levels {
    private val THRESHOLDS = listOf(0, 500, 1000, 2000, 3500, 5500, 8000, 11000, 14500, 18500, 23000)

    /** XP needed to reach [level] (level 1 = 0 XP). */
    fun threshold(level: Int): Int {
        require(level >= 1)
        return if (level <= THRESHOLDS.size) THRESHOLDS[level - 1] else THRESHOLDS.last() + 5000 * (level - THRESHOLDS.size)
    }

    fun xpLevel(xp: Int): Int {
        var level = 1
        while (threshold(level + 1) <= xp) level++
        return level
    }

    fun info(xp: Int, bossesDefeated: Int): LevelInfo {
        val byXp = xpLevel(xp)
        val effective = minOf(byXp, 2 + bossesDefeated)
        val capped = byXp > effective
        return LevelInfo(
            level = effective,
            xp = xp,
            levelStartXp = threshold(effective),
            nextLevelXp = threshold(effective + 1),
            cappedByBoss = capped,
            capMessage = if (capped) "Defeat a boss to reach Level ${effective + 1}" else null,
            xpLevel = byXp,
        )
    }
}

/** Streak = consecutive active days counting back from today (or yesterday if nothing today yet). */
object Streaks {
    fun streak(activeDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in activeDays) today else today.minusDays(1)
        var count = 0
        while (day in activeDays) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}

data class ReviewState(val stage: Int, val nextReviewAt: Long)

/** Spaced review after mastery: 1, 3, 7, 21 days. */
object Review {
    val INTERVAL_DAYS = listOf(1, 3, 7, 21)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun afterBossDefeat(now: Long) = ReviewState(0, now + INTERVAL_DAYS[0] * DAY_MS)

    fun afterReview(stage: Int?, passed: Boolean, now: Long): ReviewState {
        if (!passed) return ReviewState(0, now + INTERVAL_DAYS[0] * DAY_MS)
        val next = ((stage ?: 0) + 1).coerceAtMost(INTERVAL_DAYS.lastIndex)
        return ReviewState(next, now + INTERVAL_DAYS[next] * DAY_MS)
    }

    fun isDue(nextReviewAt: Long?, now: Long) = nextReviewAt != null && nextReviewAt <= now
}

/** Numeric answer parsing: "5", "5.0", "+5", "10/4", "1,000", "x = 5", "20%" (tolerance 1e-6). */
object Answers {
    private val prefix = Regex("^[a-zA-Z]\\s*=\\s*")

    fun parseNumber(raw: String?): Double? {
        if (raw == null) return null
        var s = raw.trim().replace(",", "").replace(" ", "").replace('−', '-')
        s = s.replace(prefix, "")
        s = s.removeSuffix("%")
        if (s.startsWith("+")) s = s.substring(1)
        if (s.isEmpty()) return null
        val slash = s.indexOf('/')
        if (slash > 0) {
            val num = s.substring(0, slash).removePrefix("+").toDoubleOrNull() ?: return null
            val den = s.substring(slash + 1).removePrefix("+").toDoubleOrNull() ?: return null
            if (den == 0.0) return null
            return num / den
        }
        if (!Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$").matches(s)) return null
        return s.toDoubleOrNull()
    }

    fun numericMatches(given: String?, canonical: String): Boolean {
        val a = parseNumber(given)
        val b = parseNumber(canonical)
        if (a != null && b != null) return kotlin.math.abs(a - b) <= 1e-6
        if (given == null) return false
        return given.trim().equals(canonical.trim(), ignoreCase = true)
    }
}
