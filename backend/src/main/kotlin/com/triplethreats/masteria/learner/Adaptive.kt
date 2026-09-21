package com.triplethreats.masteria.learner

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** A question as the picker sees it. */
data class Candidate(val id: String, val difficulty: Int, val rating: Double, val flags: Int = 0)

/** What the learner model wants for the next question. */
data class Targeting(val target: Double, val hintFirst: Boolean, val rollingAccuracy: Int, val window: Int)

object Adaptive {
    const val WINDOW = 5
    const val FLAG_LIMIT = 2

    /** Accuracy (0–100) over the last [WINDOW] answers, oldest first input. */
    fun rollingAccuracy(recent: List<Boolean>): Int {
        val w = recent.takeLast(WINDOW)
        if (w.isEmpty()) return 0
        return (w.count { it } * 100.0 / w.size).roundToInt()
    }

    /**
     * Target success for the next pick: > 85 % rolling accuracy steps difficulty up (target 0.65);
     * < 70 % (with at least 2 answers) steps it down (target 0.88) and shows the hint first.
     */
    fun targeting(recent: List<Boolean>): Targeting {
        val w = recent.takeLast(WINDOW)
        val acc = rollingAccuracy(w)
        val ratio = if (w.isEmpty()) 0.0 else w.count { it }.toDouble() / w.size
        return when {
            w.isNotEmpty() && ratio > 0.85 -> Targeting(Elo.HARDER_TARGET, false, acc, w.size)
            w.size >= 2 && ratio < 0.70 -> Targeting(Elo.EASIER_TARGET, true, acc, w.size)
            else -> Targeting(Elo.DEFAULT_TARGET, false, acc, w.size)
        }
    }

    /**
     * Picks the question whose predicted success is closest to [target].
     * Excludes questions served in this session and questions with >= 2 flags; prefers questions the
     * learner has not seen recently; falls back to repeats when the pool is exhausted.
     * [minDifficulty] + [preferHardest] are used by boss battles.
     */
    fun pickNext(
        learner: Double,
        pool: List<Candidate>,
        target: Double = Elo.DEFAULT_TARGET,
        servedInSession: List<String> = emptyList(),
        recentlySeen: Set<String> = emptySet(),
        minDifficulty: Int = 1,
        preferHardest: Boolean = false,
        random: Random = Random.Default,
    ): Candidate? {
        val usableAll = pool.filter { it.flags < FLAG_LIMIT }
        if (usableAll.isEmpty()) return null
        val usable = usableAll.filter { it.difficulty >= minDifficulty }.ifEmpty { usableAll }
        val served = servedInSession.toSet()
        val notServed = usable.filter { it.id !in served }
        val fresh = notServed.filter { it.id !in recentlySeen }
        val lastServed = servedInSession.lastOrNull()
        val candidates = when {
            fresh.isNotEmpty() -> fresh
            notServed.isNotEmpty() -> notServed
            else -> usable.filter { it.id != lastServed }.ifEmpty { usable }
        }
        val scoped = if (preferHardest) {
            val top = candidates.maxOf { it.difficulty }
            candidates.filter { it.difficulty == top }
        } else candidates
        val scored = scoped.map { it to abs(Elo.expected(learner, it.rating) - target) }
        val best = scored.minOf { it.second }
        val ties = scored.filter { it.second <= best + 0.005 }.map { it.first }.sortedBy { it.id }
        return ties[random.nextInt(ties.size)]
    }

    fun difficultyChange(previous: Int?, next: Int?): String = when {
        previous == null || next == null -> "same"
        next > previous -> "up"
        next < previous -> "down"
        else -> "same"
    }
}
