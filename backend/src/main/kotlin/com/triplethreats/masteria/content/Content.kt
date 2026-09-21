package com.triplethreats.masteria.content

import com.triplethreats.masteria.AppJson
import com.triplethreats.masteria.routes.TrackDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class TopicDef(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "book",
    val tier: Int = 0,
    val prereqs: List<String> = emptyList(),
    val diagnostic: Boolean = false,
)

@Serializable
data class TrackDef(
    val id: String,
    val name: String,
    val world: String,
    val subject: String,
    val description: String,
    val icon: String,
    val recommendedFor: List<String> = emptyList(),
    val topics: List<TopicDef>,
) {
    fun toDto() = TrackDto(id, name, world, subject, description, icon, recommendedFor, topics.size)
    fun topic(id: String) = topics.firstOrNull { it.id == id }
}

/** One question as written in content/questions/<track>.json. */
@Serializable
data class BankQuestion(
    val id: String,
    val topicId: String,
    val type: String = "mcq",
    val difficulty: Int = 1,
    val question: String,
    val options: List<String> = emptyList(),
    val answerIndex: Int? = null,
    val answerText: String? = null,
    val explanation: String = "",
    val hint: String = "",
    val code: String? = null,
)

/**
 * Vetted content loaded from the classpath (`content/` is bundled into the jar by processResources),
 * or from CONTENT_DIR on disk when set (handy while editing content).
 */
class ContentRepository(val tracks: List<TrackDef>, val vetted: List<BankQuestion>) {
    private val topicIndex: Map<String, Pair<TrackDef, TopicDef>> =
        tracks.flatMap { t -> t.topics.map { it.id to (t to it) } }.toMap()
    val vettedById: Map<String, BankQuestion> = vetted.associateBy { it.id }
    val vettedByTopic: Map<String, List<BankQuestion>> = vetted.groupBy { it.topicId }

    fun track(id: String?): TrackDef? = tracks.firstOrNull { it.id == id }
    fun topic(id: String): TopicDef? = topicIndex[id]?.second
    fun trackOfTopic(id: String): TrackDef? = topicIndex[id]?.first

    companion object {
        private val log = LoggerFactory.getLogger(ContentRepository::class.java)

        fun load(contentDir: String? = null): ContentRepository {
            fun read(path: String): String? {
                if (contentDir != null) {
                    val f = File(contentDir, path)
                    return if (f.isFile) f.readText() else null
                }
                val stream = ContentRepository::class.java.classLoader.getResourceAsStream("content/$path") ?: return null
                return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val tracksText = read("tracks.json") ?: error("content/tracks.json not found on the classpath (did processResources run?)")
            val tracks = AppJson.decodeFromString(ListSerializer(TrackDef.serializer()), tracksText)
            val questions = mutableListOf<BankQuestion>()
            for (t in tracks) {
                val text = read("questions/${t.id}.json")
                if (text == null) {
                    log.warn("No vetted question file for track {}", t.id)
                    continue
                }
                val parsed = runCatching { AppJson.decodeFromString(ListSerializer(BankQuestion.serializer()), text) }
                    .onFailure { log.error("Could not parse questions/{}.json: {}", t.id, it.message) }
                    .getOrDefault(emptyList())
                val topicIds = t.topics.map { it.id }.toSet()
                var skipped = 0
                for (q in parsed) {
                    val problem = validateBankQuestion(q, topicIds)
                    if (problem != null) {
                        skipped++
                        log.warn("Skipping vetted question {}: {}", q.id, problem)
                    } else questions += q
                }
                log.info("Loaded {} vetted questions for {} ({} skipped)", parsed.size - skipped, t.id, skipped)
            }
            val dupes = questions.groupBy { it.id }.filter { it.value.size > 1 }.keys
            if (dupes.isNotEmpty()) log.warn("Duplicate vetted question ids (first wins): {}", dupes)
            return ContentRepository(tracks, questions.distinctBy { it.id })
        }

        fun validateBankQuestion(q: BankQuestion, topicIds: Set<String>): String? {
            if (q.topicId !in topicIds) return "unknown topic ${q.topicId}"
            if (q.difficulty !in 1..3) return "difficulty out of range"
            if (q.question.isBlank()) return "blank question"
            return when (q.type) {
                "mcq" -> when {
                    q.options.size != 4 -> "mcq needs 4 options"
                    q.answerIndex == null || q.answerIndex !in 0..3 -> "answerIndex out of range"
                    else -> null
                }
                "numeric" -> if (q.answerText.isNullOrBlank()) "numeric needs answerText" else null
                else -> "unknown type ${q.type}"
            }
        }
    }
}
