package com.triplethreats.masteria.db

import com.triplethreats.masteria.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class DbSnapshot(
    val version: Int = 1,
    val users: List<UserRecord> = emptyList(),
    val mastery: List<TopicMasteryRecord> = emptyList(),
    val attempts: List<AttemptRecord> = emptyList(),
    val questions: List<StoredQuestion> = emptyList(),
    val questionStats: List<QuestionStat> = emptyList(),
    val quests: List<QuestSession> = emptyList(),
    val badges: List<BadgeRecord> = emptyList(),
    val activity: List<DailyActivity> = emptyList(),
)

/**
 * All collections live in memory behind one Mutex; every mutation schedules a debounced,
 * atomic write (temp file + move) of the whole database to DATA_DIR/masteria-db.json.
 * Records are immutable data classes, so a snapshot can be serialised outside the lock.
 */
class JsonFileStore(
    private val file: Path,
    private val debounceMs: Long = 400,
) : Store {
    override val name = "json-file"
    private val log = LoggerFactory.getLogger(JsonFileStore::class.java)
    private val lock = Mutex()
    private val writeLock = Mutex()

    private val users = LinkedHashMap<String, UserRecord>()
    private val mastery = LinkedHashMap<String, TopicMasteryRecord>() // "$userId|$topicId"
    private val attempts = LinkedHashMap<String, MutableList<AttemptRecord>>() // userId -> oldest first
    private val questions = LinkedHashMap<String, StoredQuestion>()
    private val stats = LinkedHashMap<String, QuestionStat>()
    private val quests = LinkedHashMap<String, QuestSession>()
    private val badges = LinkedHashMap<String, MutableList<BadgeRecord>>()
    private val activity = LinkedHashMap<String, DailyActivity>() // "$userId|$day"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private var writer: Job? = null

    override suspend fun init() {
        withContext(Dispatchers.IO) {
            Files.createDirectories(file.toAbsolutePath().parent)
            if (Files.exists(file)) {
                try {
                    val snap = AppJson.decodeFromString(DbSnapshot.serializer(), Files.readString(file))
                    load(snap)
                    log.info("Loaded database from {} ({} users, {} attempts)", file, users.size, attempts.values.sumOf { it.size })
                } catch (e: Exception) {
                    val backup = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
                    Files.move(file, backup)
                    log.error("Database file was unreadable ({}); moved it to {} and started fresh", e.message, backup)
                }
            } else {
                log.info("No database yet; will create {}", file)
            }
        }
        writer = scope.launch {
            for (signal in dirty) {
                delay(debounceMs)
                runCatching { writeNow() }.onFailure { log.error("Failed to persist database: {}", it.message) }
            }
        }
    }

    private fun load(s: DbSnapshot) {
        s.users.forEach { users[it.id] = it }
        s.mastery.forEach { mastery["${it.userId}|${it.topicId}"] = it }
        s.attempts.forEach { attempts.getOrPut(it.userId) { mutableListOf() }.add(it) }
        attempts.values.forEach { list -> list.sortBy { it.at } }
        s.questions.forEach { questions[it.id] = it }
        s.questionStats.forEach { stats[it.questionId] = it }
        s.quests.forEach { quests[it.id] = it }
        s.badges.forEach { badges.getOrPut(it.userId) { mutableListOf() }.add(it) }
        s.activity.forEach { activity["${it.userId}|${it.day}"] = it }
    }

    private suspend fun snapshot(): DbSnapshot = lock.withLock {
        DbSnapshot(
            users = users.values.toList(),
            mastery = mastery.values.toList(),
            attempts = attempts.values.flatMap { it.toList() },
            questions = questions.values.toList(),
            questionStats = stats.values.toList(),
            quests = quests.values.toList(),
            badges = badges.values.flatMap { it.toList() },
            activity = activity.values.toList(),
        )
    }

    /** Serialises and atomically replaces the database file. */
    suspend fun writeNow() {
        writeLock.withLock {
            val text = AppJson.encodeToString(DbSnapshot.serializer(), snapshot())
            withContext(Dispatchers.IO) {
                val tmp = file.resolveSibling("${file.fileName}.tmp")
                Files.writeString(tmp, text)
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override suspend fun close() {
        dirty.close()
        writer?.cancelAndJoin()
        runCatching { writeNow() }.onFailure { log.error("Final database write failed: {}", it.message) }
    }

    private fun changed() {
        dirty.trySend(Unit)
    }

    private suspend inline fun <T> mutate(block: () -> T): T {
        val r = lock.withLock { block() }
        changed()
        return r
    }

    // ---------------- users ----------------
    override suspend fun findUser(id: String) = lock.withLock { users[id] }
    override suspend fun findUserByEmail(email: String) = lock.withLock {
        users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }
    }
    override suspend fun findUserByFirebaseUid(uid: String) = lock.withLock {
        users.values.firstOrNull { it.firebaseUid == uid }
    }
    override suspend fun saveUser(user: UserRecord) = mutate {
        // Uniqueness is checked under the same lock as the write, so concurrent sign-ups can't both win.
        val clash = users.values.firstOrNull { other ->
            other.id != user.id && (other.email.equals(user.email, ignoreCase = true) ||
                (user.firebaseUid != null && other.firebaseUid == user.firebaseUid))
        }
        if (clash != null) throw DuplicateUserException("A user with this email or Firebase account already exists.")
        users[user.id] = user
    }

    override suspend fun deleteUserCascade(userId: String) = mutate {
        users.remove(userId)
        mastery.keys.removeIf { it.startsWith("$userId|") }
        attempts.remove(userId)
        quests.values.removeIf { it.userId == userId }
        badges.remove(userId)
        activity.keys.removeIf { it.startsWith("$userId|") }
        val owned = questions.values.filter { it.ownerUserId == userId }.map { it.id }
        owned.forEach { questions.remove(it); stats.remove(it) }
        // Drop the user's report marks but keep the aggregate flag counts.
        stats.replaceAll { _, s -> if (userId in s.reporters) s.copy(reporters = s.reporters - userId) else s }
    }

    // ---------------- mastery ----------------
    override suspend fun listMastery(userId: String) = lock.withLock { mastery.values.filter { it.userId == userId } }
    override suspend fun findMastery(userId: String, topicId: String) = lock.withLock { mastery["$userId|$topicId"] }
    override suspend fun saveMastery(record: TopicMasteryRecord) = mutate { mastery["${record.userId}|${record.topicId}"] = record }

    // ---------------- attempts ----------------
    override suspend fun addAttempt(attempt: AttemptRecord) = mutate {
        attempts.getOrPut(attempt.userId) { mutableListOf() }.add(attempt)
        Unit
    }
    override suspend fun listAttempts(userId: String) = lock.withLock { attempts[userId]?.toList() ?: emptyList() }

    // ---------------- questions ----------------
    override suspend fun listGeneratedQuestions() = lock.withLock { questions.values.toList() }
    override suspend fun saveGeneratedQuestion(question: StoredQuestion) = mutate { questions[question.id] = question }
    override suspend fun deleteGeneratedQuestions(ids: Collection<String>) = mutate {
        ids.forEach { questions.remove(it); stats.remove(it) }
    }
    override suspend fun listQuestionStats() = lock.withLock { stats.values.toList() }
    override suspend fun saveQuestionStat(stat: QuestionStat) = mutate { stats[stat.questionId] = stat }

    // ---------------- quests ----------------
    override suspend fun findQuest(id: String) = lock.withLock { quests[id] }
    override suspend fun saveQuest(quest: QuestSession) = mutate { quests[quest.id] = quest }
    override suspend fun listQuests(userId: String) = lock.withLock { quests.values.filter { it.userId == userId } }

    // ---------------- badges ----------------
    override suspend fun listBadges(userId: String) = lock.withLock { badges[userId]?.toList() ?: emptyList() }
    override suspend fun addBadge(badge: BadgeRecord) = mutate {
        val list = badges.getOrPut(badge.userId) { mutableListOf() }
        if (list.none { it.badgeId == badge.badgeId }) list.add(badge)
        Unit
    }

    // ---------------- activity ----------------
    override suspend fun listActivity(userId: String) = lock.withLock { activity.values.filter { it.userId == userId } }
    override suspend fun findActivity(userId: String, day: String) = lock.withLock { activity["$userId|$day"] }
    override suspend fun saveActivity(activity: DailyActivity) = mutate { this.activity["${activity.userId}|${activity.day}"] = activity }
}
