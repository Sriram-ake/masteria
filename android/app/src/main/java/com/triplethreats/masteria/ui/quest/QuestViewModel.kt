package com.triplethreats.masteria.ui.quest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.AnswerDto
import com.triplethreats.masteria.data.AnswerResult
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.ExplainRequest
import com.triplethreats.masteria.data.QuestSessionDto
import com.triplethreats.masteria.data.QuestSummaryDto
import com.triplethreats.masteria.data.QuestionDto
import com.triplethreats.masteria.ui.mentor.MentorChat
import kotlinx.coroutines.launch

sealed interface QuestPhase {
    data object Loading : QuestPhase
    data object BossIntro : QuestPhase
    data object Playing : QuestPhase
    data object Completing : QuestPhase
    data class Summary(val summary: QuestSummaryDto) : QuestPhase
    data class Failed(val message: String, val offline: Boolean) : QuestPhase
}

class QuestViewModel(
    private val container: AppContainer,
    private val topicId: String?,
    private val boss: Boolean,
    private val pending: Boolean,
) : ViewModel() {
    var phase by mutableStateOf<QuestPhase>(QuestPhase.Loading)
        private set
    var session by mutableStateOf<QuestSessionDto?>(null)
        private set
    var question by mutableStateOf<QuestionDto?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    var hintFirst by mutableStateOf(false)
        private set
    var mastery by mutableIntStateOf(0)
        private set
    var correctCount by mutableIntStateOf(0)
        private set

    // Per-question state
    var selected by mutableStateOf<Int?>(null)
    var numeric by mutableStateOf("")
    var hintShown by mutableStateOf(false)
        private set
    var usedHint by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var result by mutableStateOf<AnswerResult?>(null)
        private set
    var answerError by mutableStateOf<String?>(null)
        private set
    var analysis by mutableStateOf<String?>(null)
        private set
    var analysisLoading by mutableStateOf(false)
        private set
    var reported by mutableStateOf(false)
        private set
    private var shownAt by mutableLongStateOf(System.currentTimeMillis())

    /** Mentor conversation scoped to the current question ("Ask mentor" in the feedback panel). */
    var mentor by mutableStateOf<MentorChat?>(null)
        private set
    var mentorOpen by mutableStateOf(false)

    init {
        // After every property above: start() can finish synchronously for a pending quest.
        start()
    }

    fun start() {
        phase = QuestPhase.Loading
        viewModelScope.launch {
            try {
                val s = if (pending) {
                    container.pendingQuest?.also { container.pendingQuest = null }
                        ?: throw ApiException("That scanned quest has expired. Scan the page again.")
                } else {
                    container.api.startQuest(topicId, boss)
                }
                session = s
                question = s.question
                index = s.index
                hintFirst = s.hintFirst
                mastery = s.masteryBefore
                resetQuestionState()
                phase = if (s.isBoss) QuestPhase.BossIntro else QuestPhase.Playing
            } catch (e: ApiException) {
                phase = QuestPhase.Failed(e.message.orEmpty(), e.offline)
            }
        }
    }

    fun beginBoss() {
        shownAt = System.currentTimeMillis()
        phase = QuestPhase.Playing
    }

    private fun resetQuestionState() {
        selected = null; numeric = ""; result = null; answerError = null
        analysis = null; analysisLoading = false; reported = false
        hintShown = hintFirst
        usedHint = hintFirst
        mentor?.cancel(); mentor = null; mentorOpen = false
        shownAt = System.currentTimeMillis()
    }

    fun showHint() {
        hintShown = true; usedHint = true
    }

    fun canCheck(): Boolean {
        val q = question ?: return false
        if (result != null || checking) return false
        return if (q.type == "numeric") numeric.isNotBlank() && numeric != "-" && !numeric.endsWith("/") else selected != null
    }

    fun check(onVerdict: (Boolean) -> Unit) {
        val s = session ?: return
        val q = question ?: return
        if (!canCheck()) return
        checking = true; answerError = null
        viewModelScope.launch {
            try {
                val r = container.api.answer(
                    s.id,
                    AnswerDto(
                        questionId = q.id,
                        answerIndex = if (q.type == "numeric") null else selected,
                        answerText = if (q.type == "numeric") numeric else null,
                        timeMs = System.currentTimeMillis() - shownAt,
                        usedHint = usedHint,
                    ),
                )
                result = r
                mastery = r.masteryAfter
                correctCount = r.correctCount
                onVerdict(r.correct)
            } catch (e: ApiException) {
                answerError = e.message
            } finally {
                checking = false
            }
        }
    }

    fun next() {
        val r = result ?: return
        if (r.done || r.next == null) {
            complete()
            return
        }
        question = r.next
        index = r.nextIndex ?: (index + 1)
        hintFirst = r.hintFirst
        resetQuestionState()
    }

    fun complete() {
        val s = session ?: return
        phase = QuestPhase.Completing
        viewModelScope.launch {
            try {
                val summary = container.api.complete(s.id)
                container.invalidate()
                phase = QuestPhase.Summary(summary)
            } catch (e: ApiException) {
                phase = QuestPhase.Failed(e.message.orEmpty(), e.offline)
            }
        }
    }

    /** "Why was I wrong?": NIM mistake analysis, falling back to the stored explanation. */
    fun explain() {
        val q = question ?: return
        if (analysisLoading || analysis != null) return
        analysisLoading = true
        viewModelScope.launch {
            try {
                analysis = container.api.explain(
                    ExplainRequest(q.id, answerIndex = if (q.type == "numeric") null else selected, answerText = numeric.ifBlank { null })
                ).analysis
            } catch (e: ApiException) {
                analysis = result?.explanation
            } finally {
                analysisLoading = false
            }
        }
    }

    fun report() {
        val q = question ?: return
        if (reported) return
        reported = true
        viewModelScope.launch { runCatching { container.api.report(q.id, "Flagged from quest") } }
    }

    /** Opens the mentor sheet, keeping the same conversation while this question is on screen. */
    fun requestMentor() {
        if (mentor == null) {
            mentor = MentorChat(container.api, viewModelScope, topicId = question?.topicId, questionId = question?.id)
        }
        mentorOpen = true
    }

    override fun onCleared() {
        mentor?.cancel()
    }
}
