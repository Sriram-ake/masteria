package com.triplethreats.masteria

import com.triplethreats.masteria.content.TopicDef
import com.triplethreats.masteria.learner.Adaptive
import com.triplethreats.masteria.learner.Answers
import com.triplethreats.masteria.learner.BadgeContext
import com.triplethreats.masteria.learner.Badges
import com.triplethreats.masteria.learner.Candidate
import com.triplethreats.masteria.learner.Diagnostic
import com.triplethreats.masteria.learner.Elo
import com.triplethreats.masteria.learner.Levels
import com.triplethreats.masteria.learner.QuestTitles
import com.triplethreats.masteria.learner.Review
import com.triplethreats.masteria.learner.Rewards
import com.triplethreats.masteria.learner.SkillTree
import com.triplethreats.masteria.learner.Streaks
import com.triplethreats.masteria.learner.TopicState
import com.triplethreats.masteria.learner.XpInput
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EloTest {
    @Test fun expectedIsHalfForEqualRatings() = assertEquals(0.5, Elo.expected(1200.0, 1200.0), 1e-9)

    @Test fun expectedFollowsEloCurve() {
        assertEquals(1 / (1 + Math.pow(10.0, 0.5)), Elo.expected(1000.0, 1200.0), 1e-9)
        assertTrue(Elo.expected(1400.0, 1000.0) > 0.9)
    }

    @Test fun updateRatingMovesTowardsResult() {
        assertEquals(1016.0, Elo.updateRating(1000.0, 1000.0, true), 1e-9)
        assertEquals(984.0, Elo.updateRating(1000.0, 1000.0, false), 1e-9)
    }

    @Test fun masteryMapping() {
        assertEquals(0, Elo.mastery(800.0))
        assertEquals(100, Elo.mastery(1600.0))
        assertEquals(50, Elo.mastery(1200.0))
        assertEquals(0, Elo.mastery(500.0))
        assertEquals(100, Elo.mastery(2000.0))
        for (m in 0..100) assertEquals(m, Elo.mastery(Elo.ratingForMastery(m)))
    }

    @Test fun baseRatingsByDifficulty() {
        assertEquals(1000.0, Elo.baseQuestionRating(1))
        assertEquals(1200.0, Elo.baseQuestionRating(2))
        assertEquals(1400.0, Elo.baseQuestionRating(3))
    }

    @Test fun learnerKIsHighForNewTopics() {
        assertEquals(128.0, Elo.learnerK(0))
        assertEquals(128.0, Elo.learnerK(14))
        assertEquals(72.0, Elo.learnerK(15))
    }

    @Test fun masteryVisiblyMovesInOneQuest() {
        var r = Elo.ratingForMastery(40)
        repeat(5) { r = Elo.updateLearner(r, 1200.0, true, it) }
        assertTrue(Elo.mastery(r) - 40 >= 25, "5 correct answers should move mastery a lot, got ${Elo.mastery(r)}")
    }

    @Test fun questionGetsOppositeSmallUpdate() {
        val q = Elo.updateQuestion(1200.0, 1200.0, correct = true)
        assertEquals(1196.0, q, 1e-9)
        assertEquals(1204.0, Elo.updateQuestion(1200.0, 1200.0, correct = false), 1e-9)
    }

    @Test fun learnerRatingClampedToMasteryBand() {
        assertEquals(800.0, Elo.updateLearner(801.0, 1400.0, false, 0))
        assertEquals(1600.0, Elo.updateLearner(1599.5, 800.0, true, 0))
    }
}

class AdaptiveTest {
    private val pool = listOf(
        Candidate("e1", 1, 1000.0), Candidate("e2", 1, 1000.0),
        Candidate("m1", 2, 1200.0), Candidate("m2", 2, 1200.0),
        Candidate("h1", 3, 1400.0), Candidate("h2", 3, 1400.0),
    )

    @Test fun targetingDefault() {
        val t = Adaptive.targeting(listOf(true, false, true, true))
        assertEquals(0.775, t.target)
        assertFalse(t.hintFirst)
        assertEquals(75, t.rollingAccuracy)
    }

    @Test fun targetingStepsUpAbove85() {
        val t = Adaptive.targeting(listOf(true, true, true, true, true))
        assertEquals(0.65, t.target)
        assertFalse(t.hintFirst)
    }

    @Test fun targetingStepsDownBelow70WithHint() {
        val t = Adaptive.targeting(listOf(true, false, false))
        assertEquals(0.88, t.target)
        assertTrue(t.hintFirst)
        assertEquals(33, t.rollingAccuracy)
    }

    @Test fun singleWrongAnswerDoesNotStepDown() {
        val t = Adaptive.targeting(listOf(false))
        assertEquals(0.775, t.target)
        assertFalse(t.hintFirst)
    }

    @Test fun rollingWindowIsLastFive() {
        val t = Adaptive.targeting(listOf(false, false, false, true, true, true, true, true))
        assertEquals(100, t.rollingAccuracy)
        assertEquals(0.65, t.target)
    }

    @Test fun pickNextChoosesClosestToTarget() {
        // learner at 1200: d1 → 0.76, d2 → 0.5, d3 → 0.24
        val q = Adaptive.pickNext(1200.0, pool, 0.775, random = Random(1))!!
        assertEquals(1, q.difficulty)
        val harder = Adaptive.pickNext(1400.0, pool, 0.65, random = Random(1))!!
        assertEquals(2, harder.difficulty) // 1400 vs 1200 → 0.76 closest to 0.65
    }

    @Test fun pickNextSkipsServedAndFlagged() {
        val flagged = pool.map { if (it.id == "e2") it.copy(flags = 2) else it }
        val q = Adaptive.pickNext(1200.0, flagged, 0.775, servedInSession = listOf("e1"), random = Random(1))!!
        assertFalse(q.id == "e1" || q.id == "e2")
    }

    @Test fun pickNextPrefersUnseenThenRepeats() {
        val q = Adaptive.pickNext(1200.0, pool, 0.775, recentlySeen = setOf("e1", "e2"), random = Random(1))!!
        assertEquals(2, q.difficulty) // d1 all seen recently → next best unseen
        val all = pool.map { it.id }.toSet()
        val repeat = Adaptive.pickNext(1200.0, pool, 0.775, recentlySeen = all, random = Random(1))
        assertNotNull(repeat) // falls back to repeats
        val exhausted = Adaptive.pickNext(1200.0, pool.take(2), 0.775, servedInSession = listOf("e1", "e2"), random = Random(1))
        assertEquals("e1", exhausted!!.id) // everything served: repeat, but not the last one
    }

    @Test fun pickNextBossPrefersHardest() {
        val q = Adaptive.pickNext(1000.0, pool, 0.775, minDifficulty = 2, preferHardest = true, random = Random(1))!!
        assertEquals(3, q.difficulty)
    }

    @Test fun pickNextEmptyPool() = assertNull(Adaptive.pickNext(1000.0, emptyList()))

    @Test fun difficultyChange() {
        assertEquals("up", Adaptive.difficultyChange(1, 2))
        assertEquals("down", Adaptive.difficultyChange(3, 1))
        assertEquals("same", Adaptive.difficultyChange(2, 2))
        assertEquals("same", Adaptive.difficultyChange(2, null))
    }
}

class RewardsTest {
    private fun base(avg: Double) = XpInput(false, false, avg, 0, false, false, false)

    @Test fun baseByRoundedDifficulty() {
        assertEquals(50, Rewards.questXp(base(1.2)).total)
        assertEquals(100, Rewards.questXp(base(1.6)).total)
        assertEquals(200, Rewards.questXp(base(2.6)).total)
    }

    @Test fun masteryGainMultiplier() {
        val r = Rewards.questXp(base(2.0).copy(masteryGain = 10)) // 100 × 1.5
        assertEquals(150, r.total)
        assertEquals(listOf("Quest reward (medium)", "Mastery gain +10"), r.lines.map { it.label })
        assertEquals(100, Rewards.questXp(base(2.0).copy(masteryGain = -8)).total) // negative gain → no penalty
    }

    @Test fun masteredReplayHalved() {
        val r = Rewards.questXp(base(2.0).copy(masteryGain = 4, wasMastered = true)) // 100 × 1.2 × 0.5
        assertEquals(60, r.total)
        assertEquals(r.total, r.lines.sumOf { it.xp })
    }

    @Test fun bonusesAndBoss() {
        val r = Rewards.questXp(XpInput(true, true, 3.0, 6, false, perfect = true, firstQuestToday = true))
        // 500 × 1.3 = 650 + 100 perfect + 25 streak + 250 skill mastery
        assertEquals(1025, r.total)
        assertEquals(102, r.coins)
        assertEquals(listOf("Boss defeated", "Mastery gain +6", "Perfect score", "Daily streak", "Skill mastery"), r.lines.map { it.label })
        assertEquals(50, Rewards.questXp(XpInput(true, false, 3.0, 0, false, false, false)).total)
    }
}

class LevelsTest {
    @Test fun thresholds() {
        assertEquals(0, Levels.threshold(1))
        assertEquals(500, Levels.threshold(2))
        assertEquals(23000, Levels.threshold(11))
        assertEquals(28000, Levels.threshold(12))
        assertEquals(33000, Levels.threshold(13))
    }

    @Test fun xpLevel() {
        assertEquals(1, Levels.xpLevel(0))
        assertEquals(1, Levels.xpLevel(499))
        assertEquals(2, Levels.xpLevel(500))
        assertEquals(4, Levels.xpLevel(2500))
        assertEquals(12, Levels.xpLevel(30000))
    }

    @Test fun bossCap() {
        val capped = Levels.info(2500, bossesDefeated = 0)
        assertEquals(2, capped.level)
        assertTrue(capped.cappedByBoss)
        assertEquals("Defeat a boss to reach Level 3", capped.capMessage)
        assertEquals(500, capped.levelStartXp)
        assertEquals(1000, capped.nextLevelXp)

        val free = Levels.info(2500, bossesDefeated = 2)
        assertEquals(4, free.level)
        assertFalse(free.cappedByBoss)
        assertNull(free.capMessage)
        assertEquals(2000, free.levelStartXp)
        assertEquals(3500, free.nextLevelXp)
    }
}

class StreakTest {
    private val today = LocalDate.of(2026, 9, 19)

    @Test fun countsBackFromToday() =
        assertEquals(3, Streaks.streak(setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4)), today))

    @Test fun countsFromYesterdayIfNothingToday() =
        assertEquals(2, Streaks.streak(setOf(today.minusDays(1), today.minusDays(2)), today))

    @Test fun brokenStreak() = assertEquals(0, Streaks.streak(setOf(today.minusDays(2)), today))
}

class AnswersTest {
    @Test fun numericForms() {
        for (s in listOf("5", "5.0", "+5", " 5 ", "10/2", "x = 5", "5%")) assertTrue(Answers.numericMatches(s, "5"), s)
        assertTrue(Answers.numericMatches("10/4", "2.5"))
        assertTrue(Answers.numericMatches("1,000", "1000"))
        assertTrue(Answers.numericMatches("-3", "-3"))
        assertTrue(Answers.numericMatches("−3", "-3"))
        assertTrue(Answers.numericMatches(".5", "0.5"))
        assertFalse(Answers.numericMatches("6", "5"))
        assertFalse(Answers.numericMatches("", "5"))
        assertFalse(Answers.numericMatches(null, "5"))
        assertFalse(Answers.numericMatches("5/0", "5"))
        assertFalse(Answers.numericMatches("abc", "5"))
        assertFalse(Answers.numericMatches("0.3333", "1/3")) // outside 1e-6
        assertTrue(Answers.numericMatches("0.333333333", "1/3"))
    }
}

class DiagnosticTest {
    @Test fun weightedScore() {
        assertEquals(1.0, Diagnostic.score(listOf(1 to true, 2 to true, 3 to true)))
        assertEquals(0.5, Diagnostic.score(listOf(1 to true, 2 to true, 3 to false)))
        assertEquals(0.5, Diagnostic.score(listOf(1 to false, 2 to false, 3 to true)))
        assertEquals(0.0, Diagnostic.score(emptyList()))
    }

    @Test fun masteryFromScore() {
        assertEquals(15, Diagnostic.mastery(0.0))
        assertEquals(90, Diagnostic.mastery(1.0))
        assertEquals(53, Diagnostic.mastery(0.5))
        assertEquals(28, Diagnostic.mastery(1.0 / 6))
        assertTrue(Diagnostic.testedOut(90))
        assertFalse(Diagnostic.testedOut(Diagnostic.mastery(5.0 / 6))) // 78
    }
}

class SkillTreeTest {
    private val a = TopicDef("a", "A", prereqs = emptyList())
    private val b = TopicDef("b", "B", prereqs = emptyList())
    private val c = TopicDef("c", "C", tier = 1, prereqs = listOf("a"))
    private val d = TopicDef("d", "D", tier = 1, prereqs = listOf("a", "b"))

    @Test fun statusAndWeak() {
        val views = SkillTree.view(listOf(
            TopicState(a, 30, false, null), TopicState(b, 82, false, null),
            TopicState(c, 15, false, null), TopicState(d, 15, false, null),
        ), now = 0)
        assertEquals(listOf("available", "available", "locked", "locked"), views.map { it.status })
        assertEquals(listOf(true, false, false, false), views.map { it.weak })
        assertEquals(listOf(false, true, false, false), views.map { it.bossReady })
    }

    @Test fun masteredAndReviewDue() {
        val views = SkillTree.view(listOf(
            TopicState(a, 85, true, 1000), TopicState(b, 40, false, null),
            TopicState(c, 15, false, null), TopicState(d, 15, false, null),
        ), now = 2000)
        assertEquals(listOf("mastered", "available", "available", "locked"), views.map { it.status })
        assertTrue(views[0].reviewDue)
        assertEquals(listOf(false, false, true, false), views.map { it.weak })
    }

    @Test fun newlyUnlocked() {
        val unlocked = SkillTree.newlyUnlocked(listOf(a, b, c, d), emptySet(), "a")
        assertEquals(listOf("c"), unlocked.map { it.id })
        assertEquals(listOf("d"), SkillTree.newlyUnlocked(listOf(a, b, c, d), setOf("a"), "b").map { it.id })
    }

    @Test fun titles() {
        assertEquals("The Balance Trial", QuestTitles.title("math.linear_equations", "Algebra", 1))
        assertEquals("Warden of Equations", QuestTitles.title("math.linear_equations", "Algebra", 3, isBoss = true))
        assertEquals("Trial of Optics", QuestTitles.title("x.optics", "Optics", 2))
    }
}

class ReviewAndBadgesTest {
    private val day = 86_400_000L

    @Test fun reviewIntervals() {
        assertEquals(Review.afterBossDefeat(0), com.triplethreats.masteria.learner.ReviewState(0, day))
        assertEquals(3 * day, Review.afterReview(0, true, 0).nextReviewAt)
        assertEquals(7 * day, Review.afterReview(1, true, 0).nextReviewAt)
        assertEquals(21 * day, Review.afterReview(2, true, 0).nextReviewAt)
        assertEquals(21 * day, Review.afterReview(3, true, 0).nextReviewAt)
        assertEquals(Review.afterReview(2, false, 0), com.triplethreats.masteria.learner.ReviewState(0, day))
    }

    @Test fun badgeConditions() {
        val ctx = BadgeContext(1, 7, true, 91, true, 15, true, 20, 15)
        assertEquals(Badges.ALL.map { it.id }.toSet(), Badges.qualifying(ctx).toSet())
        val none = BadgeContext(0, 1, false, 50, false, 3, false, 2, 15)
        assertTrue(Badges.qualifying(none).isEmpty())
    }
}
