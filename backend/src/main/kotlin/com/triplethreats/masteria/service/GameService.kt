package com.triplethreats.masteria.service

import com.triplethreats.masteria.ApiException
import com.triplethreats.masteria.auth.FirebaseIdentity
import com.triplethreats.masteria.AppConfig
import com.triplethreats.masteria.badRequest
import com.triplethreats.masteria.conflict
import com.triplethreats.masteria.content.ContentRepository
import com.triplethreats.masteria.content.QView
import com.triplethreats.masteria.content.QuestionBank
import com.triplethreats.masteria.content.TrackDef
import com.triplethreats.masteria.db.AttemptRecord
import com.triplethreats.masteria.db.BadgeRecord
import com.triplethreats.masteria.db.DailyActivity
import com.triplethreats.masteria.db.DuplicateUserException
import com.triplethreats.masteria.db.QuestSession
import com.triplethreats.masteria.db.SessionAnswer
import com.triplethreats.masteria.db.Store
import com.triplethreats.masteria.db.TopicMasteryRecord
import com.triplethreats.masteria.db.UserRecord
import com.triplethreats.masteria.forbidden
import com.triplethreats.masteria.learner.Adaptive
import com.triplethreats.masteria.learner.Answers
import com.triplethreats.masteria.learner.BadgeContext
import com.triplethreats.masteria.learner.Badges
import com.triplethreats.masteria.learner.Diagnostic
import com.triplethreats.masteria.learner.Elo
import com.triplethreats.masteria.learner.LevelInfo
import com.triplethreats.masteria.learner.Levels
import com.triplethreats.masteria.learner.QuestTitles
import com.triplethreats.masteria.learner.Review
import com.triplethreats.masteria.learner.Rewards
import com.triplethreats.masteria.learner.SkillTree
import com.triplethreats.masteria.learner.Streaks
import com.triplethreats.masteria.learner.TopicState
import com.triplethreats.masteria.learner.TopicView
import com.triplethreats.masteria.learner.XpInput
import com.triplethreats.masteria.notFound
import com.triplethreats.masteria.routes.AnswerDto
import com.triplethreats.masteria.routes.AnswerResult
import com.triplethreats.masteria.routes.BadgeDto
import com.triplethreats.masteria.routes.DiagnosticDto
import com.triplethreats.masteria.routes.DiagnosticResult
import com.triplethreats.masteria.routes.DiagnosticSubmit
import com.triplethreats.masteria.routes.HomeDto
import com.triplethreats.masteria.routes.LevelDto
import com.triplethreats.masteria.routes.MapDto
import com.triplethreats.masteria.routes.MapTopicDto
import com.triplethreats.masteria.routes.OnboardingRequest
import com.triplethreats.masteria.routes.ProfileDto
import com.triplethreats.masteria.routes.ProgressDto
import com.triplethreats.masteria.routes.QuestSessionDto
import com.triplethreats.masteria.routes.QuestSummaryDto
import com.triplethreats.masteria.routes.RecommendationDto
import com.triplethreats.masteria.routes.StartQuestRequest
import com.triplethreats.masteria.routes.StreakDto
import com.triplethreats.masteria.routes.TopicMasteryDto
import com.triplethreats.masteria.routes.TopicProgressDto
import com.triplethreats.masteria.routes.UpdateProfileRequest
import com.triplethreats.masteria.routes.UserDto
import com.triplethreats.masteria.routes.XpLineDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything complete() needs, read once in parallel. */
private class CompleteSnapshot(
    val user: UserRecord,
    val masteries: Map<String, TopicMasteryRecord>,
    val activity: List<DailyActivity>,
    val quests: List<QuestSession>,
    val attempts: List<AttemptRecord>,
    val badges: List<BadgeRecord>,
)

/**
 * Orchestrates the deterministic learner model over the store. No AI calls happen here, so
 * /answer stays well under 200 ms; AI top-ups are fired off through [topUp] and never awaited.
 */
class GameService(
    private val config: AppConfig,
    val content: ContentRepository,
    private val store: Store,
    val bank: QuestionBank,
) {
    /** Hook for background question generation: (topicId, learnerRating). */
    var topUp: (String, Double) -> Unit = { _, _ -> }

    private val zone = config.zone
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun now() = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now(zone)
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private suspend fun <T> locked(userId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(userId) { Mutex() }.withLock { block() }

    private fun newId(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        /** Compared against when the account doesn't exist, so login timing is the same either way. */
        val DUMMY_HASH: String = BCrypt.hashpw("masteria-dummy-password", BCrypt.gensalt(10))
    }

    // ================================================================= users & auth

    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    suspend fun register(name: String, email: String, password: String): UserRecord {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        if (cleanName.isEmpty()) badRequest("Please tell us your name.")
        if (cleanName.length > 60) badRequest("That name is a little long. Please use 60 characters or fewer.")
        if (!emailRegex.matches(cleanEmail)) badRequest("Please enter a valid email address.")
        if (password.length < 6) badRequest("Password must be at least 6 characters.")
        if (password.length > 72) badRequest("Password must be 72 characters or fewer.") // bcrypt ignores bytes past 72
        if (store.findUserByEmail(cleanEmail) != null) conflict("An account with this email already exists.")
        val user = UserRecord(id = newId("u"), name = cleanName, email = cleanEmail, passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(10)))
        saveNewUser(user)
        return user
    }

    /** Saves a new account, turning a lost uniqueness race into a clean 409. */
    private suspend fun saveNewUser(user: UserRecord) {
        try {
            store.saveUser(user)
        } catch (e: DuplicateUserException) {
            conflict("An account with this email already exists.")
        }
    }

    suspend fun login(email: String, password: String): UserRecord {
        val user = store.findUserByEmail(email.trim().lowercase())
        val hash = user?.passwordHash?.takeIf { it.startsWith("$2") } ?: DUMMY_HASH
        // Always run bcrypt so response time doesn't reveal whether the email exists.
        val matches = runCatching { BCrypt.checkpw(password, hash) }.getOrDefault(false)
        val ok = user != null && !user.isGuest && hash !== DUMMY_HASH && matches
        if (!ok) {
            if (user != null && user.authProvider == "firebase" && hash === DUMMY_HASH)
                throw ApiException(HttpStatusCode.Unauthorized, "This account signs in with Firebase. Use the app's sign-in screen.")
            throw ApiException(HttpStatusCode.Unauthorized, "Incorrect email or password.")
        }
        return user!!
    }

    suspend fun guest(name: String): UserRecord {
        val cleanName = name.trim().ifEmpty { "Guest" }.take(60)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val user = UserRecord(id = newId("u"), name = cleanName, email = "guest-$suffix@masteria.local", passwordHash = "!", isGuest = true)
        saveNewUser(user)
        return user
    }

    /**
     * Signs in with a verified Firebase identity. Finds the account by Firebase uid; otherwise links an
     * existing account with the same (verified) email, upgrades the calling guest (keeping their progress),
     * or creates a new account.
     */
    suspend fun firebaseSignIn(identity: FirebaseIdentity, requestedName: String?, guest: UserRecord?): UserRecord {
        store.findUserByFirebaseUid(identity.uid)?.let { return it }
        val email = identity.email?.trim()?.lowercase()?.takeIf { emailRegex.matches(it) }
            ?: badRequest("Your Firebase account has no email address. Sign in with email or Google.")
        val name = (requestedName?.trim()?.takeIf { it.isNotEmpty() } ?: identity.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: email.substringBefore('@')).take(60)

        val byEmail = store.findUserByEmail(email)
        if (byEmail != null) {
            // Only link to an existing account when Firebase has proved the email belongs to this person.
            if (!identity.emailVerified) conflict("An account with this email already exists. Verify your email, then sign in again.")
            if (byEmail.firebaseUid != null && byEmail.firebaseUid != identity.uid) conflict("This email is linked to a different sign-in.")
            return locked(byEmail.id) {
                val fresh = store.findUser(byEmail.id) ?: notFound("User not found")
                fresh.copy(firebaseUid = identity.uid, authProvider = "firebase").also { saveNewUser(it) }
            }
        }
        if (guest != null && guest.isGuest) {
            return locked(guest.id) {
                val fresh = store.findUser(guest.id) ?: notFound("User not found")
                fresh.copy(name = name, email = email, isGuest = false, firebaseUid = identity.uid, authProvider = "firebase")
                    .also { saveNewUser(it) }
            }
        }
        val user = UserRecord(id = newId("u"), name = name, email = email, passwordHash = "!", firebaseUid = identity.uid, authProvider = "firebase")
        saveNewUser(user)
        return user
    }

    suspend fun findUser(id: String) = store.findUser(id)
    suspend fun findUserByFirebaseUid(uid: String) = store.findUserByFirebaseUid(uid)

    suspend fun deleteUser(user: UserRecord) {
        locked(user.id) {
            store.deleteUserCascade(user.id)
            bank.forgetOwnedBy(user.id)
            bank.forgetReporter(user.id)
        }
        locks.remove(user.id)
    }

    suspend fun userDto(user: UserRecord, masteries: Collection<TopicMasteryRecord>? = null, streak: Int? = null): UserDto {
        val ms = masteries ?: store.listMastery(user.id)
        val lvl = levelInfo(user, ms)
        return UserDto(
            id = user.id, name = user.name, email = user.email,
            onboarded = user.onboarded,
            diagnosed = user.trackId != null && user.trackId in user.diagnosedTracks,
            learnerType = user.learnerType, goal = user.goal, selfLevel = user.selfLevel,
            dailyMinutes = user.dailyMinutes, trackId = user.trackId,
            level = lvl.level, xp = user.xp, coins = user.coins,
            streakDays = streak ?: streakDays(user.id),
            isGuest = user.isGuest,
        )
    }

    private fun bossesDefeated(ms: Collection<TopicMasteryRecord>) = ms.count { it.bossDefeated && !it.testedOut }
    private fun levelInfo(user: UserRecord, ms: Collection<TopicMasteryRecord>): LevelInfo = Levels.info(user.xp, bossesDefeated(ms))
    private fun LevelInfo.toDto() = LevelDto(level, xp, levelStartXp, nextLevelXp, cappedByBoss, capMessage)

    suspend fun streakDays(userId: String): Int {
        val days = store.listActivity(userId).filter { it.quests > 0 }.map { LocalDate.parse(it.day) }.toSet()
        return Streaks.streak(days, today())
    }

    // ================================================================= topics

    fun trackFor(user: UserRecord): TrackDef = content.track(user.trackId) ?: content.tracks.first()

    fun topicName(topicId: String): String =
        content.topic(topicId)?.name ?: bank.generatedTopicName(topicId) ?: "Scanned notes"

    private fun defaultMastery(userId: String, topicId: String) =
        TopicMasteryRecord(userId, topicId, Elo.ratingForMastery(Elo.START_MASTERY), Elo.START_MASTERY)

    private suspend fun masteryMap(userId: String) = store.listMastery(userId).associateBy { it.topicId }

    fun views(track: TrackDef, masteries: Map<String, TopicMasteryRecord>): List<TopicView> =
        SkillTree.view(track.topics.map { t ->
            val m = masteries[t.id]
            TopicState(t, m?.mastery ?: Elo.START_MASTERY, m?.bossDefeated ?: false, m?.nextReviewAt)
        }, now())

    private fun TopicView.toMasteryDto() = TopicMasteryDto(topic.id, topic.name, mastery, status, weak)

    /** Real mastery per topic for the mentor prompt. */
    suspend fun topicSnapshot(user: UserRecord): List<TopicView> = views(trackFor(user), masteryMap(user.id))

    private fun recommendation(v: TopicView, rating: Double, reason: String, isBoss: Boolean, isReview: Boolean): RecommendationDto {
        val difficulty = if (isBoss) 3 else Elo.difficultyFor(rating)
        val xp = if (isBoss) Rewards.BOSS_WIN else Rewards.baseXp(difficulty.toDouble())
        return RecommendationDto(
            topicId = v.topic.id, topicName = v.topic.name, mastery = v.mastery, reason = reason,
            questTitle = QuestTitles.title(v.topic.id, v.topic.name, difficulty, isBoss, isReview),
            difficulty = difficulty, xpReward = xp, isBoss = isBoss, isReview = isReview,
        )
    }

    private fun ratingOf(masteries: Map<String, TopicMasteryRecord>, topicId: String) =
        masteries[topicId]?.rating ?: Elo.ratingForMastery(Elo.START_MASTERY)

    /** Weakest available not-mastered topic; else a due review; else the lowest-mastery topic. */
    private fun recommend(views: List<TopicView>, masteries: Map<String, TopicMasteryRecord>): Pair<TopicView, RecommendationDto>? {
        val order = views.withIndex().associate { it.value.topic.id to it.index }
        val open = views.filter { it.status == SkillTree.AVAILABLE }
        if (open.isNotEmpty()) {
            val weakest = open.minWith(compareBy<TopicView>({ it.mastery }, { it.topic.tier }, { order[it.topic.id] }))
            val rating = ratingOf(masteries, weakest.topic.id)
            return weakest to if (weakest.bossReady)
                recommendation(weakest, rating, "Boss battle ready: ${weakest.topic.name} is at ${weakest.mastery}%", isBoss = true, isReview = false)
            else recommendation(weakest, rating, "Weakest topic on your path", isBoss = false, isReview = false)
        }
        views.filter { it.reviewDue }.minByOrNull { masteries[it.topic.id]?.nextReviewAt ?: 0L }?.let {
            return it to recommendation(it, ratingOf(masteries, it.topic.id), "Due for review: keep it sharp", isBoss = false, isReview = true)
        }
        val lowest = views.minByOrNull { it.mastery } ?: return null
        return lowest to recommendation(lowest, ratingOf(masteries, lowest.topic.id), "Keep your skills sharp",
            isBoss = false, isReview = lowest.status == SkillTree.MASTERED)
    }

    // ================================================================= onboarding + diagnostic

    private val learnerTypes = setOf("school", "college", "exam", "coding", "self", "career")
    private val goals = setOf("grades", "exam", "skill", "job", "career", "explore")
    private val selfLevels = setOf("beginner", "intermediate", "advanced")

    suspend fun onboard(user: UserRecord, req: OnboardingRequest): UserDto = locked(user.id) {
        if (req.isMinor && !req.parentConsent) badRequest("A parent or guardian needs to agree before you can continue.")
        if (content.track(req.trackId) == null) badRequest("Unknown track: ${req.trackId}")
        val type = req.learnerType.trim().lowercase()
        val goal = req.goal.trim().lowercase()
        val level = req.selfLevel.trim().lowercase()
        if (type !in learnerTypes) badRequest("Unknown learner type: ${req.learnerType}")
        if (goal !in goals) badRequest("Unknown goal: ${req.goal}")
        if (level !in selfLevels) badRequest("Unknown level: ${req.selfLevel}")
        if (req.dailyMinutes !in 5..600) badRequest("Daily minutes should be between 5 and 600.")
        val fresh = store.findUser(user.id) ?: notFound("User not found")
        val updated = fresh.copy(
            onboarded = true, learnerType = type, goal = goal, selfLevel = level,
            dailyMinutes = req.dailyMinutes, trackId = req.trackId, isMinor = req.isMinor,
            parentConsentAt = if (req.isMinor) (fresh.parentConsentAt ?: now()) else fresh.parentConsentAt,
        )
        store.saveUser(updated)
        userDto(updated)
    }

    private fun diagnosticQuestions(track: TrackDef): List<QView> =
        track.topics.filter { it.diagnostic }.flatMap { topic ->
            val pool = bank.usableForTopic(topic.id).sortedWith(compareBy({ it.source != "vetted" }, { it.id }))
            val picked = mutableListOf<QView>()
            for (d in 1..3) pool.firstOrNull { it.difficulty == d && it !in picked }?.let { picked += it }
            for (q in pool) if (picked.size < 3 && q !in picked) picked += q
            picked.sortedBy { it.difficulty }
        }

    fun diagnostic(user: UserRecord, trackId: String?): DiagnosticDto {
        val id = trackId?.takeIf { it.isNotBlank() } ?: user.trackId ?: badRequest("Choose a track first.")
        val track = content.track(id) ?: notFound("Unknown track: $id")
        return DiagnosticDto(track.id, diagnosticQuestions(track).map { it.toDto(topicName(it.topicId)) })
    }

    fun isCorrect(q: QView, answer: AnswerDto): Boolean = when (q.type) {
        "mcq" -> answer.answerIndex != null && answer.answerIndex == q.answerIndex
        else -> q.answerText != null && Answers.numericMatches(answer.answerText, q.answerText)
    }

    suspend fun submitDiagnostic(user: UserRecord, submit: DiagnosticSubmit): DiagnosticResult = locked(user.id) {
        val track = content.track(submit.trackId) ?: notFound("Unknown track: ${submit.trackId}")
        val diagTopics = track.topics.filter { it.diagnostic }.map { it.id }.toSet()
        val now = now()
        val graded = submit.answers.distinctBy { it.questionId }.mapNotNull { a ->
            val q = bank.get(a.questionId) ?: return@mapNotNull null
            if (q.topicId !in diagTopics) return@mapNotNull null
            Triple(q, a, isCorrect(q, a))
        }
        if (graded.isEmpty()) badRequest("No valid diagnostic answers for ${track.name}.")
        val existing = masteryMap(user.id)
        val newMastery = mutableMapOf<String, Int>()
        for (topicId in diagTopics) {
            val answers = graded.filter { it.first.topicId == topicId }
            val mastery = if (answers.isEmpty()) Elo.START_MASTERY
            else Diagnostic.mastery(Diagnostic.score(answers.map { it.first.difficulty to it.third }))
            newMastery[topicId] = mastery
            val old = existing[topicId]
            val testedOut = Diagnostic.testedOut(mastery)
            val record = (old ?: defaultMastery(user.id, topicId)).copy(
                rating = Elo.ratingForMastery(mastery),
                mastery = mastery,
                testedOut = (old?.testedOut ?: false) || (testedOut && old?.bossDefeated != true),
                bossDefeated = (old?.bossDefeated ?: false) || testedOut,
                reviewStage = if (testedOut && old?.bossDefeated != true) 1 else old?.reviewStage,
                nextReviewAt = if (testedOut && old?.bossDefeated != true) now + Review.INTERVAL_DAYS[1] * 86_400_000L else old?.nextReviewAt,
                lastPracticedAt = now,
            )
            store.saveMastery(record)
        }
        // Non-diagnostic topics start at mastery 15.
        for (t in track.topics) if (t.id !in diagTopics && existing[t.id] == null) store.saveMastery(defaultMastery(user.id, t.id))
        graded.forEachIndexed { i, (q, a, correct) ->
            store.addAttempt(AttemptRecord(user.id, q.id, "diagnostic", q.topicId, correct, a.timeMs.coerceIn(0, 600_000),
                a.usedHint, now + i, newMastery[q.topicId] ?: Elo.START_MASTERY, predicted = null))
        }
        val fresh = store.findUser(user.id) ?: notFound("User not found")
        store.saveUser(fresh.copy(diagnosedTracks = (fresh.diagnosedTracks + track.id).distinct()))

        val views = views(track, masteryMap(user.id))
        val weakest = views.filter { it.weak }.minWithOrNull(compareBy({ it.topic.tier }, { views.indexOf(it) }))
        val summary = when {
            weakest != null && weakest.topic.diagnostic ->
                "${weakest.topic.name} needs work — your first quest is built around it."
            weakest != null ->
                "You tested out of the basics! ${weakest.topic.name} is next — your first quest is built around it."
            else -> "Impressive — you tested out of everything on this path. Try another world!"
        }
        DiagnosticResult(
            trackId = track.id,
            topics = views.map { it.toMasteryDto() },
            weakestTopicId = weakest?.topic?.id,
            correct = graded.count { it.third },
            total = graded.size,
            summary = summary,
        )
    }

    // ================================================================= home + map

    private fun greeting(name: String): String {
        val hour = LocalTime.now(zone).hour
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, ${name.trim().split(" ").first()}"
    }

    private fun minutesToday(attempts: List<AttemptRecord>): Int {
        val today = today()
        val ms = attempts.filter { dayOf(it.at) == today }.sumOf { it.timeMs }
        return if (ms <= 0) 0 else ceil(ms / 60_000.0).toInt()
    }

    suspend fun home(user: UserRecord): HomeDto {
        val track = trackFor(user)
        val masteries = masteryMap(user.id)
        val views = views(track, masteries)
        val streak = streakDays(user.id)
        val attempts = store.listAttempts(user.id)
        val activity = store.findActivity(user.id, today().toString())
        return HomeDto(
            user = userDto(user, masteries.values, streak),
            track = track.toDto(),
            level = levelInfo(user, masteries.values).toDto(),
            recommended = recommend(views, masteries)?.second,
            bossReady = views.filter { it.bossReady }.map {
                recommendation(it, ratingOf(masteries, it.topic.id), "Boss battle ready: ${it.topic.name} is at ${it.mastery}%", isBoss = true, isReview = false)
            },
            reviewsDue = views.filter { it.reviewDue }.map {
                recommendation(it, ratingOf(masteries, it.topic.id), "Spaced review due", isBoss = false, isReview = true)
            },
            minutesToday = minutesToday(attempts),
            dailyMinutes = user.dailyMinutes ?: 15,
            questsToday = activity?.quests ?: 0,
            greeting = greeting(user.name),
        )
    }

    suspend fun map(user: UserRecord): MapDto {
        val track = trackFor(user)
        val views = views(track, masteryMap(user.id))
        return MapDto(track.toDto(), views.map { v ->
            MapTopicDto(
                id = v.topic.id, name = v.topic.name, description = v.topic.description, icon = v.topic.icon,
                tier = v.topic.tier, prereqs = v.topic.prereqs, mastery = v.mastery, status = v.status,
                weak = v.weak, bossReady = v.bossReady, bossDefeated = v.bossDefeated, reviewDue = v.reviewDue,
                questionCount = bank.usableForTopic(v.topic.id).size,
            )
        })
    }

    // ================================================================= quests

    private fun pick(topicId: String, rating: Double, served: List<String>, attempts: List<AttemptRecord>, target: Double, boss: Boolean): QView {
        val pool = bank.forTopic(topicId)
        val recent = attempts.takeLast(30).map { it.questionId }.toSet()
        val c = Adaptive.pickNext(rating, pool.map { it.toCandidate() }, target, served, recent,
            minDifficulty = if (boss) 2 else 1, preferHardest = boss)
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "No questions are ready for this topic yet. Try another topic.")
        return pool.first { it.id == c.id }
    }

    private fun question(id: String): QView = bank.get(id) ?: notFound("Question $id is no longer available.")

    private fun sessionDto(s: QuestSession): QuestSessionDto {
        val index = s.servedIds.size - 1
        val q = question(s.servedIds[index])
        return QuestSessionDto(
            id = s.id, title = s.title, topicId = s.topicId, topicName = s.topicName,
            isBoss = s.isBoss, isReview = s.isReview, isScan = s.isScan,
            total = s.total, index = index, question = q.toDto(s.topicName),
            hintFirst = s.hintFirst, masteryBefore = s.masteryBefore, xpBase = s.xpBase,
        )
    }

    private suspend fun ownedQuest(user: UserRecord, id: String): QuestSession {
        val s = store.findQuest(id)
        if (s == null || s.userId != user.id) notFound("Quest not found.")
        return s
    }

    suspend fun startQuest(user: UserRecord, req: StartQuestRequest): QuestSessionDto = locked(user.id) {
        val track = trackFor(user)
        val masteries = masteryMap(user.id)
        val trackViews = views(track, masteries)
        val topicId = req.topicId?.takeIf { it.isNotBlank() }
            ?: recommend(trackViews, masteries)?.first?.topic?.id
            ?: notFound("No topics are available on this track.")
        if (topicId.startsWith("scan.")) badRequest("Scan quests are created from /ai/scan.")
        val topicTrack = content.trackOfTopic(topicId) ?: notFound("Unknown topic: $topicId")
        val v = (if (topicTrack.id == track.id) trackViews else views(topicTrack, masteries)).first { it.topic.id == topicId }
        if (v.status == SkillTree.LOCKED) {
            val missing = v.topic.prereqs.filter { masteries[it]?.bossDefeated != true }.map { topicName(it) }
            forbidden("${v.topic.name} is locked. Defeat the ${missing.joinToString(" and ")} boss to unlock it.")
        }
        if (req.boss && !v.bossReady) {
            conflict(if (v.bossDefeated) "You've already defeated this boss. Replay ${v.topic.name} as a review quest."
            else "The boss opens at ${SkillTree.BOSS_THRESHOLD}% mastery. ${v.topic.name} is at ${v.mastery}%.")
        }
        val m = masteries[topicId] ?: defaultMastery(user.id, topicId)
        val attempts = store.listAttempts(user.id)
        val targeting = Adaptive.targeting(attempts.filter { it.topicId == topicId }.map { it.correct })
        val target = if (req.boss) Elo.DEFAULT_TARGET else targeting.target
        val first = pick(topicId, m.rating, emptyList(), attempts, target, req.boss)
        val isReview = v.bossDefeated
        val session = QuestSession(
            id = newId("quest"), userId = user.id, trackId = topicTrack.id, topicId = topicId, topicName = v.topic.name,
            title = QuestTitles.title(topicId, v.topic.name, if (req.boss) 3 else first.difficulty, req.boss, isReview),
            isBoss = req.boss, isReview = isReview, isScan = false, total = 5,
            servedIds = listOf(first.id), hintFirst = !req.boss && targeting.hintFirst,
            masteryBefore = m.mastery,
            xpBase = if (req.boss) Rewards.BOSS_WIN else Rewards.baseXp(first.difficulty.toDouble()),
            wasMasteredBefore = isReview, createdAt = now(),
        )
        store.saveQuest(session)
        // Background top-up when the learner is running out of unseen approved questions.
        val seen = attempts.map { it.questionId }.toSet()
        val unseen = bank.usableForTopic(topicId).count { it.id !in seen }
        if (unseen < 4) topUp(topicId, m.rating)
        sessionDto(session)
    }

    suspend fun startScanQuest(user: UserRecord, topicId: String, topicName: String, questionIds: List<String>): QuestSessionDto = locked(user.id) {
        require(questionIds.isNotEmpty())
        val m = store.findMastery(user.id, topicId) ?: defaultMastery(user.id, topicId).also { store.saveMastery(it) }
        val first = question(questionIds.first())
        val session = QuestSession(
            id = newId("quest"), userId = user.id, trackId = null, topicId = topicId, topicName = topicName,
            title = QuestTitles.scanTitle(topicName), isBoss = false, isReview = false, isScan = true,
            total = questionIds.size, servedIds = listOf(first.id), plannedIds = questionIds, hintFirst = false,
            masteryBefore = m.mastery, xpBase = Rewards.baseXp(first.difficulty.toDouble()), createdAt = now(),
        )
        store.saveQuest(session)
        sessionDto(session)
    }

    suspend fun getQuest(user: UserRecord, id: String): QuestSessionDto = sessionDto(ownedQuest(user, id))

    suspend fun answer(user: UserRecord, questId: String, dto: AnswerDto): AnswerResult = locked(user.id) {
        val s = ownedQuest(user, questId)
        s.answers.firstOrNull { it.questionId == dto.questionId }?.let { return@locked it.result } // idempotent retry
        if (s.completedAt != null) conflict("This quest is already complete.")
        if (s.answers.size >= s.total) conflict("All questions in this quest are answered. Complete the quest to collect your XP.")
        val currentId = s.servedIds.last()
        if (dto.questionId != currentId) conflict("That isn't the current question in this quest.")
        val q = question(currentId)
        val correct = isCorrect(q, dto)
        val now = now()

        // Independent reads in parallel: one database round trip instead of three (matters on a remote MongoDB).
        val day = today().toString()
        val (mRead, pastAttempts, actRead) = coroutineScope {
            val a = async { store.findMastery(user.id, s.topicId) }
            val b = async { store.listAttempts(user.id) }
            val c = async { store.findActivity(user.id, day) }
            Triple(a.await(), b.await(), c.await())
        }
        val m = mRead ?: defaultMastery(user.id, s.topicId)
        val predicted = Elo.expected(m.rating, q.rating)
        val newRating = Elo.updateLearner(m.rating, q.rating, correct, m.answerCount)
        val newMastery = Elo.mastery(newRating)
        val timeMs = dto.timeMs.coerceIn(0, 600_000)
        val attempt = AttemptRecord(user.id, q.id, s.id, s.topicId, correct, timeMs, dto.usedHint, now, newMastery, predicted)

        // Work out everything (including the next question, which can fail) before writing anything, so a
        // failed pick can't leave a half-recorded answer that a retry would count twice.
        val attempts = pastAttempts + attempt
        val recentOnTopic = attempts.filter { it.topicId == s.topicId }.map { it.correct }
        val answered = s.answers.size + 1
        val correctCount = s.answers.count { it.correct } + (if (correct) 1 else 0)
        val done = answered >= s.total

        var next: QView? = null
        var hintFirst = false
        if (!done) {
            val targeting = Adaptive.targeting(recentOnTopic)
            next = if (s.plannedIds.isNotEmpty()) question(s.plannedIds[answered])
            else pick(s.topicId, newRating, s.servedIds, attempts, if (s.isBoss) Elo.DEFAULT_TARGET else targeting.target, s.isBoss)
            hintFirst = !s.isBoss && targeting.hintFirst
        }

        val act = actRead ?: DailyActivity(user.id, day)
        coroutineScope {
            launch { bank.updateRating(q, Elo.updateQuestion(q.rating, m.rating, correct)) }
            launch { store.saveMastery(m.copy(rating = newRating, mastery = newMastery, lastPracticedAt = now, answerCount = m.answerCount + 1)) }
            launch { store.addAttempt(attempt) }
            launch { store.saveActivity(act.copy(activeMs = act.activeMs + timeMs)) }
        }

        val result = AnswerResult(
            correct = correct,
            correctIndex = if (q.type == "mcq") q.answerIndex else null,
            correctText = if (q.type == "mcq") q.correctText else q.answerText,
            explanation = q.explanation,
            masteryBefore = m.mastery, masteryAfter = newMastery,
            difficultyChange = Adaptive.difficultyChange(q.difficulty, next?.difficulty),
            rollingAccuracy = Adaptive.rollingAccuracy(recentOnTopic),
            done = done,
            next = next?.toDto(s.topicName),
            nextIndex = if (next != null) answered else null,
            hintFirst = hintFirst,
            correctCount = correctCount, answeredCount = answered,
        )
        store.saveQuest(s.copy(
            answers = s.answers + SessionAnswer(q.id, correct, result),
            servedIds = if (next != null) s.servedIds + next.id else s.servedIds,
            hintFirst = if (next != null) hintFirst else s.hintFirst,
        ))
        result
    }

    suspend fun complete(user: UserRecord, questId: String): QuestSummaryDto = locked(user.id) {
        val s = ownedQuest(user, questId)
        s.summary?.let { return@locked it } // idempotent
        if (s.answers.isEmpty()) conflict("Answer at least one question before completing the quest.")
        val now = now()
        val day = today().toString()
        val snap = coroutineScope {
            val u = async { store.findUser(user.id) }
            val ms = async { masteryMap(user.id) }
            val acts = async { store.listActivity(user.id) }
            val quests = async { store.listQuests(user.id) }
            val atts = async { store.listAttempts(user.id) }
            val bdg = async { store.listBadges(user.id) }
            CompleteSnapshot(u.await() ?: notFound("User not found"), ms.await(), acts.await(), quests.await(), atts.await(), bdg.await())
        }
        val fresh = snap.user
        val mastersBefore = snap.masteries
        val levelBefore = levelInfo(fresh, mastersBefore.values)

        val correct = s.answers.count { it.correct }
        val accuracy = (correct * 100.0 / s.total).roundToInt()
        val m = mastersBefore[s.topicId] ?: defaultMastery(user.id, s.topicId)
        val gain = m.mastery - s.masteryBefore
        val bossWon = s.isBoss && correct >= ceil(s.total * 0.8).toInt()

        var unlocked = emptyList<String>()
        var updatedMastery = m
        if (bossWon) {
            val track = content.trackOfTopic(s.topicId)
            val defeatedBefore = mastersBefore.values.filter { it.bossDefeated }.map { it.topicId }.toSet()
            if (track != null) unlocked = SkillTree.newlyUnlocked(track.topics, defeatedBefore, s.topicId).map { it.name }
            val review = Review.afterBossDefeat(now)
            updatedMastery = m.copy(bossDefeated = true, reviewStage = review.stage, nextReviewAt = review.nextReviewAt)
        } else if (s.isReview) {
            val review = Review.afterReview(m.reviewStage, accuracy >= 80, now)
            updatedMastery = m.copy(reviewStage = review.stage, nextReviewAt = review.nextReviewAt)
        }
        val act = snap.activity.firstOrNull { it.day == day } ?: DailyActivity(user.id, day)
        val served = s.answers.mapNotNull { bank.get(it.questionId)?.difficulty }
        val xp = Rewards.questXp(XpInput(
            isBoss = s.isBoss, bossDefeated = bossWon,
            avgDifficulty = if (served.isEmpty()) 1.0 else served.average(),
            masteryGain = gain, wasMastered = s.wasMasteredBefore,
            perfect = correct == s.total, firstQuestToday = act.quests == 0,
        ))
        val updatedUser = fresh.copy(xp = fresh.xp + xp.total, coins = fresh.coins + xp.coins)
        val updatedAct = act.copy(xp = act.xp + xp.total, quests = act.quests + 1)

        // Everything below is derived from the snapshot plus this quest's changes (no re-reads).
        val mastersAfter = if (updatedMastery != m) mastersBefore + (s.topicId to updatedMastery) else mastersBefore
        val levelAfter = levelInfo(updatedUser, mastersAfter.values)
        val activeDays = (snap.activity.filter { it.day != day } + updatedAct).filter { it.quests > 0 }.map { LocalDate.parse(it.day) }.toSet()
        val streak = Streaks.streak(activeDays, today())
        val completedBefore = snap.quests.count { it.completedAt != null }
        val attempts = snap.attempts

        val earned = snap.badges.map { it.badgeId }.toSet()
        val qualifying = Badges.qualifying(BadgeContext(
            questsCompleted = completedBefore + 1, streakDays = streak, bossDefeated = bossWon,
            bestTopicMastery = mastersAfter.values.maxOfOrNull { it.mastery } ?: 0,
            perfect = correct == s.total, masteryGain = gain, scanQuestCompleted = s.isScan,
            minutesToday = minutesToday(attempts), dailyMinutesGoal = fresh.dailyMinutes ?: 15,
        ))
        val newBadgeIds = qualifying.filter { it !in earned }
        coroutineScope {
            if (updatedMastery != m) launch { store.saveMastery(updatedMastery) }
            launch { store.saveUser(updatedUser) }
            launch { store.saveActivity(updatedAct) }
            newBadgeIds.forEach { id -> launch { store.addBadge(BadgeRecord(user.id, id, now)) } }
        }
        val newBadges = newBadgeIds.map { id ->
            val d = Badges.def(id)
            BadgeDto(d.id, d.name, d.description, d.icon, earned = true, earnedAt = now)
        }

        val summary = QuestSummaryDto(
            questId = s.id, title = s.title, topicName = s.topicName,
            isBoss = s.isBoss, bossDefeated = bossWon,
            correct = correct, total = s.total, accuracy = accuracy,
            masteryBefore = s.masteryBefore, masteryAfter = m.mastery,
            xpEarned = xp.total, coinsEarned = xp.coins,
            breakdown = xp.lines.map { XpLineDto(it.label, it.xp) },
            levelBefore = levelBefore.level, levelAfter = levelAfter.level, leveledUp = levelAfter.level > levelBefore.level,
            level = levelAfter.toDto(),
            newBadges = newBadges,
            unlockedTopics = unlocked,
            streakDays = streak,
            nextStep = nextStep(user, s, m.mastery, bossWon, correct, unlocked, updatedMastery, mastersAfter),
        )
        store.saveQuest(s.copy(completedAt = now, summary = summary))
        summary
    }

    private fun nextStep(
        user: UserRecord, s: QuestSession, mastery: Int, bossWon: Boolean, correct: Int,
        unlocked: List<String>, m: TopicMasteryRecord, masteries: Map<String, TopicMasteryRecord>,
    ): String {
        val name = s.topicName
        val threshold = SkillTree.BOSS_THRESHOLD
        return when {
            s.isScan -> "Nice work on your notes! Scan another page, or head back to your map."
            bossWon && unlocked.isNotEmpty() -> "Boss defeated! ${unlocked.joinToString(" and ")} ${if (unlocked.size == 1) "is" else "are"} now unlocked — start your first quest there."
            bossWon -> {
                val rec = recommend(views(trackFor(user), masteries), masteries)?.first?.topic?.name
                "Boss defeated! $name is mastered." + (rec?.let { " Next up: $it." } ?: "")
            }
            s.isBoss -> "So close — $correct/${s.total}. You need ${ceil(s.total * 0.8).toInt()} to win. One more practice quest on $name, then try the boss again."
            s.isReview -> {
                val days = Review.INTERVAL_DAYS[(m.reviewStage ?: 0).coerceIn(0, Review.INTERVAL_DAYS.lastIndex)]
                "Review done — $name stays sharp. Next review in $days day${if (days == 1) "" else "s"}."
            }
            mastery >= threshold -> "$name is at $mastery%. The boss battle is open — win it to master $name."
            threshold - mastery <= 20 -> "$name is at $mastery%. One more strong quest and the boss opens."
            else -> "$name is at $mastery%. ${threshold - mastery} more points and the boss opens — keep questing."
        }
    }

    // ================================================================= progress / profile

    suspend fun progress(user: UserRecord): ProgressDto {
        val track = trackFor(user)
        val masteries = masteryMap(user.id)
        val views = views(track, masteries)
        val attempts = store.listAttempts(user.id)
        val topics = views.map { v ->
            val onTopic = attempts.filter { it.topicId == v.topic.id }
            TopicProgressDto(
                topicId = v.topic.id, name = v.topic.name, mastery = v.mastery, status = v.status,
                trend = onTopic.takeLast(12).map { it.masteryAfter },
                accuracy = if (onTopic.isEmpty()) 0 else (onTopic.count { it.correct } * 100.0 / onTopic.size).roundToInt(),
                attempts = onTopic.size,
            )
        }
        val strongest = views.filter { it.mastery > Elo.START_MASTERY || it.status == SkillTree.MASTERED }
            .sortedByDescending { it.mastery }.take(2).map { it.topic.name }
        val weakPool = views.filter { it.status == SkillTree.AVAILABLE }.ifEmpty { views }
        val weakest = weakPool.sortedBy { it.mastery }.take(2).map { it.topic.name }
        val predicted = attempts.mapNotNull { it.predicted }
        val today = today()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val activity = store.listActivity(user.id).associateBy { it.day }
        return ProgressDto(
            trackId = track.id,
            overallMastery = if (views.isEmpty()) 0 else views.map { it.mastery }.average().roundToInt(),
            topics = topics,
            strongest = strongest,
            weakest = weakest,
            questsCompleted = store.listQuests(user.id).count { it.completedAt != null },
            totalAnswers = attempts.size,
            accuracy = if (attempts.isEmpty()) 0 else (attempts.count { it.correct } * 100.0 / attempts.size).roundToInt(),
            targetZoneShare = if (predicted.isEmpty()) 0 else (predicted.count { it in 0.70..0.85 } * 100.0 / predicted.size).roundToInt(),
            xpLast7Days = days.map { activity[it.toString()]?.xp ?: 0 },
            dayLabels = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) },
        )
    }

    suspend fun profile(user: UserRecord): ProfileDto {
        val track = trackFor(user)
        val masteries = masteryMap(user.id)
        val views = views(track, masteries)
        val earned = store.listBadges(user.id).associateBy { it.badgeId }
        return ProfileDto(
            user = userDto(user, masteries.values),
            level = levelInfo(user, masteries.values).toDto(),
            track = track.toDto(),
            badges = Badges.ALL.map { d ->
                val e = earned[d.id]
                BadgeDto(d.id, d.name, d.description, d.icon, earned = e != null, earnedAt = e?.earnedAt)
            },
            topSkills = views.sortedByDescending { it.mastery }.take(3).map { it.toMasteryDto() },
            questsCompleted = store.listQuests(user.id).count { it.completedAt != null },
            bossesDefeated = bossesDefeated(masteries.values),
            tracks = content.tracks.map { it.toDto() },
        )
    }

    suspend fun updateProfile(user: UserRecord, req: UpdateProfileRequest): ProfileDto {
        val updated = locked(user.id) {
            var u = store.findUser(user.id) ?: notFound("User not found")
            req.name?.let {
                val n = it.trim()
                if (n.isEmpty() || n.length > 60) badRequest("Name must be 1 to 60 characters.")
                u = u.copy(name = n)
            }
            req.trackId?.let {
                if (content.track(it) == null) badRequest("Unknown track: $it")
                u = u.copy(trackId = it)
            }
            req.dailyMinutes?.let {
                if (it !in 5..600) badRequest("Daily minutes should be between 5 and 600.")
                u = u.copy(dailyMinutes = it)
            }
            req.learnerType?.let {
                val t = it.trim().lowercase()
                if (t !in learnerTypes) badRequest("Unknown learner type: $it")
                u = u.copy(learnerType = t)
            }
            req.goal?.let {
                val g = it.trim().lowercase()
                if (g !in goals) badRequest("Unknown goal: $it")
                u = u.copy(goal = g)
            }
            store.saveUser(u)
            u
        }
        return profile(updated)
    }

    suspend fun streak(user: UserRecord): StreakDto {
        val active = (store.findActivity(user.id, today().toString())?.quests ?: 0) > 0
        return StreakDto(streakDays(user.id), active)
    }
}
