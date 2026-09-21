package com.triplethreats.masteria.db

import com.mongodb.ConnectionString
import com.mongodb.ErrorCategory
import com.mongodb.MongoClientSettings
import com.mongodb.MongoWriteException
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.triplethreats.masteria.AppJson
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * MongoDB-backed store (used when MONGODB_URI is set, e.g. a MongoDB Atlas cluster for Render).
 * Records are converted to BSON documents through their kotlinx JSON form, with a deterministic `_id`
 * per record so saves are idempotent upserts. Several server instances can share one database.
 */
class MongoStore(uri: String) : Store {
    override val name = "mongodb"
    private val log = LoggerFactory.getLogger(MongoStore::class.java)
    private val connection = ConnectionString(uri)
    private val client = MongoClient.create(
        MongoClientSettings.builder()
            .applyConnectionString(connection)
            .applyToClusterSettings { it.serverSelectionTimeout(15, TimeUnit.SECONDS) }
            .applyToSocketSettings { it.connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS) }
            .applyToConnectionPoolSettings { it.maxConnectionIdleTime(5, TimeUnit.MINUTES) }
            .apply { if (connection.isSrvProtocol) serverApi(ServerApi.builder().version(ServerApiVersion.V1).build()) }
            .applicationName("masteria-backend")
            .retryWrites(true)
            .build()
    )
    private val db = client.getDatabase(connection.database ?: "masteria")

    private val users = db.getCollection<Document>("users")
    private val mastery = db.getCollection<Document>("mastery")
    private val attempts = db.getCollection<Document>("attempts")
    private val questions = db.getCollection<Document>("questions")
    private val questionStats = db.getCollection<Document>("question_stats")
    private val quests = db.getCollection<Document>("quests")
    private val badges = db.getCollection<Document>("badges")
    private val activity = db.getCollection<Document>("activity")

    private val relaxed = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()
    private val upsert = ReplaceOptions().upsert(true)

    override suspend fun init() {
        // Fail fast with a clear message when the cluster is unreachable (wrong URI, IP not allow-listed).
        db.runCommand(Document("ping", 1))
        suspend fun index(col: MongoCollection<Document>, keys: Bson, options: IndexOptions = IndexOptions()) {
            runCatching { col.createIndex(keys, options) }
                .onFailure { log.warn("Could not create index {} on {}: {}", keys, col.namespace.collectionName, it.message) }
        }
        // Emails are stored lowercase, so an exact unique index enforces one account per email.
        index(users, Indexes.ascending("email"), IndexOptions().unique(true).name("email_unique"))
        index(users, Indexes.ascending("firebaseUid"), IndexOptions().unique(true).sparse(true).name("firebaseUid_unique"))
        index(mastery, Indexes.ascending("userId"))
        index(attempts, Indexes.ascending("userId", "at"))
        index(quests, Indexes.ascending("userId"))
        index(badges, Indexes.ascending("userId"))
        index(activity, Indexes.ascending("userId"))
        index(questions, Indexes.ascending("ownerUserId"))
        log.info("Connected to MongoDB database {}", db.name)
    }

    override suspend fun close() = client.close()

    private fun <T> toDoc(serializer: KSerializer<T>, value: T, id: String): Document =
        Document.parse(AppJson.encodeToString(serializer, value)).append("_id", id)

    private fun <T> fromDoc(serializer: KSerializer<T>, doc: Document): T {
        doc.remove("_id")
        val obj: JsonObject = AppJson.parseToJsonElement(doc.toJson(relaxed)).jsonObject
        return AppJson.decodeFromJsonElement(serializer, obj)
    }

    private suspend fun <T> put(col: MongoCollection<Document>, serializer: KSerializer<T>, value: T, id: String) {
        col.replaceOne(Filters.eq("_id", id), toDoc(serializer, value, id), upsert)
    }

    private suspend fun <T> one(col: MongoCollection<Document>, serializer: KSerializer<T>, filter: Bson): T? =
        col.find(filter).limit(1).firstOrNull()?.let { fromDoc(serializer, it) }

    private suspend fun <T> many(col: MongoCollection<Document>, serializer: KSerializer<T>, filter: Bson, sort: Bson? = null): List<T> {
        val flow = col.find(filter).let { if (sort != null) it.sort(sort) else it }
        return flow.map { fromDoc(serializer, it) }.toList()
    }

    override suspend fun findUser(id: String) = one(users, UserRecord.serializer(), Filters.eq("_id", id))
    // Exact match on the stored lowercase email: uses the unique index (the old case-insensitive regex scanned every user).
    override suspend fun findUserByEmail(email: String) =
        one(users, UserRecord.serializer(), Filters.eq("email", email.trim().lowercase()))
    override suspend fun findUserByFirebaseUid(uid: String) =
        one(users, UserRecord.serializer(), Filters.eq("firebaseUid", uid))
    override suspend fun saveUser(user: UserRecord) {
        try {
            put(users, UserRecord.serializer(), user.copy(email = user.email.lowercase()), user.id)
        } catch (e: MongoWriteException) {
            if (e.error.category == ErrorCategory.DUPLICATE_KEY) throw DuplicateUserException("A user with this email or Firebase account already exists.")
            throw e
        }
    }

    override suspend fun deleteUserCascade(userId: String) {
        users.deleteOne(Filters.eq("_id", userId))
        mastery.deleteMany(Filters.eq("userId", userId))
        attempts.deleteMany(Filters.eq("userId", userId))
        quests.deleteMany(Filters.eq("userId", userId))
        badges.deleteMany(Filters.eq("userId", userId))
        activity.deleteMany(Filters.eq("userId", userId))
        val owned = many(questions, StoredQuestion.serializer(), Filters.eq("ownerUserId", userId)).map { it.id }
        if (owned.isNotEmpty()) deleteGeneratedQuestions(owned)
        // Same as the JSON store: drop the user's report marks but keep the aggregate flag counts.
        questionStats.updateMany(Filters.eq("reporters", userId), Updates.pull("reporters", userId))
    }

    override suspend fun listMastery(userId: String) = many(mastery, TopicMasteryRecord.serializer(), Filters.eq("userId", userId))
    override suspend fun findMastery(userId: String, topicId: String) =
        one(mastery, TopicMasteryRecord.serializer(), Filters.eq("_id", "$userId|$topicId"))
    override suspend fun saveMastery(record: TopicMasteryRecord) =
        put(mastery, TopicMasteryRecord.serializer(), record, "${record.userId}|${record.topicId}")

    override suspend fun addAttempt(attempt: AttemptRecord) {
        attempts.insertOne(Document.parse(AppJson.encodeToString(AttemptRecord.serializer(), attempt)))
    }
    override suspend fun listAttempts(userId: String) =
        many(attempts, AttemptRecord.serializer(), Filters.eq("userId", userId), Sorts.ascending("at"))

    override suspend fun listGeneratedQuestions() = many(questions, StoredQuestion.serializer(), Filters.empty())
    override suspend fun saveGeneratedQuestion(question: StoredQuestion) = put(questions, StoredQuestion.serializer(), question, question.id)
    override suspend fun deleteGeneratedQuestions(ids: Collection<String>) {
        questions.deleteMany(Filters.`in`("_id", ids.toList()))
        questionStats.deleteMany(Filters.`in`("_id", ids.toList()))
    }
    override suspend fun listQuestionStats() = many(questionStats, QuestionStat.serializer(), Filters.empty())
    override suspend fun saveQuestionStat(stat: QuestionStat) = put(questionStats, QuestionStat.serializer(), stat, stat.questionId)

    override suspend fun findQuest(id: String) = one(quests, QuestSession.serializer(), Filters.eq("_id", id))
    override suspend fun saveQuest(quest: QuestSession) = put(quests, QuestSession.serializer(), quest, quest.id)
    override suspend fun listQuests(userId: String) = many(quests, QuestSession.serializer(), Filters.eq("userId", userId))

    override suspend fun listBadges(userId: String) = many(badges, BadgeRecord.serializer(), Filters.eq("userId", userId))
    override suspend fun addBadge(badge: BadgeRecord) =
        put(badges, BadgeRecord.serializer(), badge, "${badge.userId}|${badge.badgeId}")

    override suspend fun listActivity(userId: String) = many(activity, DailyActivity.serializer(), Filters.eq("userId", userId))
    override suspend fun findActivity(userId: String, day: String) =
        one(activity, DailyActivity.serializer(), Filters.eq("_id", "$userId|$day"))
    override suspend fun saveActivity(activity: DailyActivity) =
        put(this.activity, DailyActivity.serializer(), activity, "${activity.userId}|${activity.day}")
}
