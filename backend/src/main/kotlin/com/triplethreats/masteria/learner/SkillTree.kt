package com.triplethreats.masteria.learner

import com.triplethreats.masteria.content.TopicDef

/** Per-topic state needed to derive map status. */
data class TopicState(val topic: TopicDef, val mastery: Int, val bossDefeated: Boolean, val nextReviewAt: Long?)

data class TopicView(
    val topic: TopicDef,
    val mastery: Int,
    val status: String,
    val weak: Boolean,
    val bossReady: Boolean,
    val bossDefeated: Boolean,
    val reviewDue: Boolean,
)

object SkillTree {
    const val BOSS_THRESHOLD = 80
    const val LOCKED = "locked"
    const val AVAILABLE = "available"
    const val MASTERED = "mastered"

    fun status(topic: TopicDef, defeated: Set<String>): String = when {
        topic.id in defeated -> MASTERED
        topic.prereqs.all { it in defeated } -> AVAILABLE
        else -> LOCKED
    }

    fun view(states: List<TopicState>, now: Long): List<TopicView> {
        val defeated = states.filter { it.bossDefeated }.map { it.topic.id }.toSet()
        val statuses = states.associate { it.topic.id to status(it.topic, defeated) }
        val open = states.filter { statuses[it.topic.id] == AVAILABLE }
        val minMastery = open.minOfOrNull { it.mastery }
        return states.map { s ->
            val st = statuses.getValue(s.topic.id)
            TopicView(
                topic = s.topic,
                mastery = s.mastery,
                status = st,
                weak = st == AVAILABLE && minMastery != null && s.mastery == minMastery,
                bossReady = st == AVAILABLE && s.mastery >= BOSS_THRESHOLD && !s.bossDefeated,
                bossDefeated = s.bossDefeated,
                reviewDue = s.bossDefeated && Review.isDue(s.nextReviewAt, now),
            )
        }
    }

    /** Topics that are unlocked by defeating [topicId] (all their prereqs are now defeated). */
    fun newlyUnlocked(topics: List<TopicDef>, defeatedBefore: Set<String>, topicId: String): List<TopicDef> {
        val after = defeatedBefore + topicId
        return topics.filter { t ->
            t.id !in after && topicId in t.prereqs &&
                status(t, defeatedBefore) == LOCKED && status(t, after) == AVAILABLE
        }
    }
}

/** Deterministic RPG-voice quest names per topic and difficulty. */
object QuestTitles {
    private val table: Map<String, List<String>> = mapOf(
        // normal d1, d2, d3, boss
        "math.integers" to listOf("The Number Line Road", "Siege of Signs", "The Order of Operations", "Lord of Negatives"),
        "math.fractions" to listOf("The Broken Loaf", "Halls of Halves", "The Decimal Labyrinth", "The Fraction Hydra"),
        "math.linear_equations" to listOf("The Balance Trial", "Gate of Unknowns", "The Equation Gate", "Warden of Equations"),
        "math.geometry" to listOf("The Crossroads of Lines", "Temple of Angles", "The Parallel Pass", "Guardian of Angles"),
        "math.probability" to listOf("The Coin Toss Tavern", "Dice of Destiny", "The Gambler's Riddle", "Oracle of Chance"),
        "math.triangles" to listOf("The Three Towers", "Pythagoras' Path", "The Hypotenuse Heights", "The Triangle Titan"),
        "quant.percentages" to listOf("The Hundred Gates", "Market of Markups", "The Successive Storm", "Percent Overlord"),
        "quant.ratio" to listOf("The Scales of Trade", "Partners' Pact", "The Proportion Puzzle", "The Ratio Regent"),
        "quant.averages" to listOf("The Middle Road", "Weighted Wagons", "The Replacement Riddle", "Mean Machine"),
        "quant.profit_loss" to listOf("The Merchant's Ledger", "Bazaar of Discounts", "The Markup Maze", "The Profit Pharaoh"),
        "quant.time_work" to listOf("The Busy Workshop", "Pipes of the Citadel", "The Tandem Trial", "The Clockwork Colossus"),
        "quant.interest" to listOf("The Moneylender's Door", "Vault of Compounding", "The Interest Inferno", "The Compound Kraken"),
        "java.basics" to listOf("The First Compile", "Loop of Trials", "The Operator's Gauntlet", "The Syntax Sentinel"),
        "java.classes" to listOf("The Blueprint Forge", "Hall of Objects", "The Constructor's Keep", "The Object Overseer"),
        "java.encapsulation" to listOf("The Private Chamber", "Getters' Gate", "The Immutable Vault", "Keeper of Secrets"),
        "java.inheritance" to listOf("The Family Tree", "Super's Summons", "The Override Ordeal", "The Ancestral Knight"),
        "java.polymorphism" to listOf("Many Faces", "The Dispatch Dungeon", "The Casting Crucible", "The Shapeshifter"),
        "java.interfaces" to listOf("The Contract Hall", "Abstract Ascent", "The Default Descent", "The Abstract Archon"),
    )

    fun title(topicId: String, topicName: String, difficulty: Int, isBoss: Boolean = false, isReview: Boolean = false): String {
        val names = table[topicId]
        val base = when {
            names == null && isBoss -> "Boss of $topicName"
            names == null -> "Trial of $topicName"
            isBoss -> names[3]
            else -> names[(difficulty.coerceIn(1, 3)) - 1]
        }
        return if (isReview) "Revisit: $base" else base
    }

    fun scanTitle(topicName: String) = "Scroll of $topicName"
}

data class BadgeDef(val id: String, val name: String, val description: String, val icon: String)

data class BadgeContext(
    val questsCompleted: Int,
    val streakDays: Int,
    val bossDefeated: Boolean,
    val bestTopicMastery: Int,
    val perfect: Boolean,
    val masteryGain: Int,
    val scanQuestCompleted: Boolean,
    val minutesToday: Int,
    val dailyMinutesGoal: Int,
)

object Badges {
    val ALL = listOf(
        BadgeDef("first_quest", "First Quest", "Complete your first quest.", "trophy"),
        BadgeDef("streak_7", "7-Day Streak", "Learn on 7 days in a row.", "flame"),
        BadgeDef("boss_slayer", "Boss Slayer", "Defeat a boss battle.", "sword"),
        BadgeDef("concept_master", "Concept Master", "Reach 90% mastery in any topic.", "brain"),
        BadgeDef("perfect_quest", "Perfect Quest", "Answer every question in a quest correctly.", "star"),
        BadgeDef("fast_learner", "Fast Learner", "Gain 15 or more mastery in a single quest.", "rocket"),
        BadgeDef("scan_explorer", "Scan Explorer", "Complete your first Scan-to-Quest.", "scan"),
        BadgeDef("goal_getter", "Goal Getter", "Meet your daily minutes goal.", "target"),
    )

    fun def(id: String) = ALL.first { it.id == id }

    /** Badge ids whose condition holds in [ctx] (caller filters out ones already earned). */
    fun qualifying(ctx: BadgeContext): List<String> = buildList {
        if (ctx.questsCompleted >= 1) add("first_quest")
        if (ctx.streakDays >= 7) add("streak_7")
        if (ctx.bossDefeated) add("boss_slayer")
        if (ctx.bestTopicMastery >= 90) add("concept_master")
        if (ctx.perfect) add("perfect_quest")
        if (ctx.masteryGain >= 15) add("fast_learner")
        if (ctx.scanQuestCompleted) add("scan_explorer")
        if (ctx.dailyMinutesGoal > 0 && ctx.minutesToday >= ctx.dailyMinutesGoal) add("goal_getter")
    }
}
