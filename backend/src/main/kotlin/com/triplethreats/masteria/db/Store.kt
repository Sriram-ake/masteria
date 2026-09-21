package com.triplethreats.masteria.db

/** Thrown by [Store.saveUser] when another user already has the same email or Firebase uid. */
class DuplicateUserException(message: String) : RuntimeException(message)

/** Persistence boundary. JsonFileStore is the default; MongoStore is used when MONGODB_URI is set. */
interface Store {
    val name: String

    suspend fun init() {}
    suspend fun close() {}

    // users
    suspend fun findUser(id: String): UserRecord?
    /** [email] is matched case-insensitively (emails are stored lowercase). */
    suspend fun findUserByEmail(email: String): UserRecord?
    suspend fun findUserByFirebaseUid(uid: String): UserRecord?
    /** Inserts or replaces; throws [DuplicateUserException] if the email or Firebase uid belongs to another user. */
    suspend fun saveUser(user: UserRecord)
    /** Removes the user and every record tied to them (DPDP deletion). */
    suspend fun deleteUserCascade(userId: String)

    // topic mastery
    suspend fun listMastery(userId: String): List<TopicMasteryRecord>
    suspend fun findMastery(userId: String, topicId: String): TopicMasteryRecord?
    suspend fun saveMastery(record: TopicMasteryRecord)

    // attempts (oldest first)
    suspend fun addAttempt(attempt: AttemptRecord)
    suspend fun listAttempts(userId: String): List<AttemptRecord>

    // generated questions + per-question stats
    suspend fun listGeneratedQuestions(): List<StoredQuestion>
    suspend fun saveGeneratedQuestion(question: StoredQuestion)
    suspend fun deleteGeneratedQuestions(ids: Collection<String>)
    suspend fun listQuestionStats(): List<QuestionStat>
    suspend fun saveQuestionStat(stat: QuestionStat)

    // quest sessions
    suspend fun findQuest(id: String): QuestSession?
    suspend fun saveQuest(quest: QuestSession)
    suspend fun listQuests(userId: String): List<QuestSession>

    // badges
    suspend fun listBadges(userId: String): List<BadgeRecord>
    suspend fun addBadge(badge: BadgeRecord)

    // daily activity
    suspend fun listActivity(userId: String): List<DailyActivity>
    suspend fun findActivity(userId: String, day: String): DailyActivity?
    suspend fun saveActivity(activity: DailyActivity)
}
