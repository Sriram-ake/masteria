package com.triplethreats.masteria.learner

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Elo-style learner model (context.md §10). Pure functions, no I/O. */
object Elo {
    const val MIN_RATING = 800.0
    const val MAX_RATING = 1600.0
    const val QUESTION_K = 8.0
    const val NEW_TOPIC_K = 128.0
    const val SETTLED_K = 72.0
    const val NEW_TOPIC_ANSWERS = 15
    const val DEFAULT_TARGET = 0.775
    const val HARDER_TARGET = 0.65
    const val EASIER_TARGET = 0.88
    const val START_MASTERY = 15

    fun expected(learner: Double, question: Double): Double =
        1.0 / (1.0 + 10.0.pow((question - learner) / 400.0))

    fun updateRating(learner: Double, question: Double, correct: Boolean, k: Double = 32.0): Double =
        learner + k * ((if (correct) 1.0 else 0.0) - expected(learner, question))

    /** Mastery 0–100 shown in the UI (rating 800 → 0, 1600 → 100). */
    fun mastery(rating: Double): Int = ((rating - 800) / 8).roundToInt().coerceIn(0, 100)

    fun ratingForMastery(mastery: Int): Double = 800.0 + 8.0 * mastery

    fun baseQuestionRating(difficulty: Int): Double = when (difficulty) {
        1 -> 1000.0
        2 -> 1200.0
        else -> 1400.0
    }

    fun learnerK(answerCount: Int): Double = if (answerCount < NEW_TOPIC_ANSWERS) NEW_TOPIC_K else SETTLED_K

    /** Learner rating after an answer, kept inside the 0–100 mastery band so gains show immediately. */
    fun updateLearner(learner: Double, question: Double, correct: Boolean, answerCount: Int): Double =
        updateRating(learner, question, correct, learnerK(answerCount)).coerceIn(MIN_RATING, MAX_RATING)

    /** The question gets the opposite update with a small K, so ratings drift towards real difficulty. */
    fun updateQuestion(question: Double, learner: Double, correct: Boolean): Double =
        updateRating(question, learner, !correct, QUESTION_K).coerceIn(600.0, 2000.0)

    /** Difficulty (1..3) whose base rating gives a predicted success closest to [target]. */
    fun difficultyFor(learner: Double, target: Double = DEFAULT_TARGET): Int =
        (1..3).minBy { abs(expected(learner, baseQuestionRating(it)) - target) }
}
