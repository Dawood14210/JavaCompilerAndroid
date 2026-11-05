package dev.javapad.completion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

data class CompletionUiState(
    val suggestions: List<CompletionSuggestion> = emptyList(),
    val prefix: String = "",
    val visible: Boolean = false,
    val parameterHint: String? = null,
)

@HiltViewModel
class CompletionViewModel @Inject constructor(
    private val engine: CompletionEngine,
    private val classIndex: AndroidClassIndex,
) : ViewModel() {
    private data class Request(val source: String, val cursor: Int, val force: Boolean, val projectSources: List<String>)
    private val request = MutableStateFlow<Request?>(null)
    private val mutableState = MutableStateFlow(CompletionUiState())
    val state: StateFlow<CompletionUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            request.collectLatest { current ->
                if (current == null) return@collectLatest
                delay(if (current.force) 0 else 150)
                val before = current.source.substring(0, current.cursor.coerceIn(0, current.source.length))
                val prefix = Regex("[A-Za-z_$][\\w$]*$").find(before)?.value.orEmpty()
                val hint = parameterHint(before)
                val shouldShow = current.force || prefix.isNotEmpty() || before.trimEnd().endsWith('.') || hint != null
                if (!shouldShow) {
                    mutableState.value = CompletionUiState()
                } else {
                    val localCandidates = if (hint != null && prefix.isEmpty() && !current.force) emptyList() else
                        withContext(Dispatchers.Default) { engine.suggest(current.source, current.cursor, current.projectSources) }
                    val indexedClasses = if (prefix.length >= 2 && !before.trimEnd().endsWith('.')) {
                        classIndex.find(prefix)
                    } else emptyList()
                    val candidates = (localCandidates + indexedClasses)
                        .distinctBy { "${it.kind}:${it.label}" }
                        .take(40)
                    mutableState.value = CompletionUiState(candidates, prefix, candidates.isNotEmpty() || hint != null, hint)
                }
            }
        }
    }

    fun update(source: String, cursor: Int, force: Boolean = false, projectSources: Collection<String> = emptyList()) {
        request.value = Request(source, cursor, force, projectSources.toList())
    }

    fun dismiss() {
        request.value = null
        mutableState.value = CompletionUiState()
    }

    fun requestImportQuickFix(source: String, lineNumber: Int, column: Int) {
        val line = source.lines().getOrNull(lineNumber - 1).orEmpty()
        val offset = (column - 1).coerceIn(0, line.length)
        val token = Regex("[A-Za-z_$][\\w$]*").findAll(line)
            .firstOrNull { offset in it.range || offset == it.range.last + 1 }?.value
            ?: line.substringAfterLast(' ').trim().takeIf { it.matches(Regex("[A-Za-z_$][\\w$]*")) }
            ?: return
        viewModelScope.launch {
            val matches = classIndex.find(token, limit = 60)
                .filter { it.label == token }
                .map { it.copy(insertion = "", cursorOffset = 0, tabStops = emptyList()) }
            mutableState.value = CompletionUiState(matches, "", matches.isNotEmpty())
        }
    }

    private fun parameterHint(before: String): String? {
        val method = Regex("([A-Za-z_$][\\w$]*)\\([^()]*$").find(before)?.groupValues?.get(1) ?: return null
        return when (method) {
            "println", "print" -> "$method(Object value)"
            "valueOf" -> "valueOf(String value)"
            "parseInt" -> "parseInt(String value)"
            else -> null
        }
    }
}
