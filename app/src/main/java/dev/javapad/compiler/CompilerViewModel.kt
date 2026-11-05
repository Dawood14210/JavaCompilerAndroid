package dev.javapad.compiler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.lifecycle.ViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@HiltViewModel
class CompilerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RunnerUiState())
    val state: StateFlow<RunnerUiState> = mutableState.asStateFlow()

    private val jumpFlow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val jumpRequests = jumpFlow.asSharedFlow()

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                RunnerProtocol.OUTPUT -> {
                    val text = message.data.getString(RunnerProtocol.KEY_TEXT).orEmpty()
                    val stderr = message.data.getBoolean(RunnerProtocol.KEY_STDERR)
                    update { copy(output = (output + ConsoleEntry(text, stderr)).takeLast(MAX_CONSOLE_ENTRIES)) }
                }
                RunnerProtocol.PROBLEMS -> {
                    val json = message.data.getString(RunnerProtocol.KEY_PROBLEMS).orEmpty()
                    val parsedProblems = parseProblems(json)
                    update {
                        copy(
                            problems = parsedProblems,
                            selectedTab = if (parsedProblems.isNotEmpty()) OutputTab.PROBLEMS else selectedTab,
                        )
                    }
                }
                RunnerProtocol.FINISHED -> {
                    val summary = message.data.getString(RunnerProtocol.KEY_SUMMARY).orEmpty()
                    update {
                        copy(
                            isRunning = false,
                            executionSummary = summary,
                            output = if (summary.isBlank()) output else (output + ConsoleEntry("\n$summary\n")).takeLast(MAX_CONSOLE_ENTRIES),
                        )
                    }
                }
                RunnerProtocol.TIMED_OUT, RunnerProtocol.STOPPED -> {
                    val summary = message.data.getString(RunnerProtocol.KEY_SUMMARY).orEmpty()
                    update {
                        copy(
                            isRunning = false,
                            executionSummary = summary,
                            output = (output + ConsoleEntry("\n$summary\n", isStderr = true)).takeLast(MAX_CONSOLE_ENTRIES),
                        )
                    }
                }
                else -> super.handleMessage(message)
            }
        }
    })

    private var serviceMessenger: Messenger? = null
    private data class PendingRun(val sources: Map<String, String>, val entryPoint: String, val timeout: Int, val projectId: String)
    private var pendingRun: PendingRun? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = binder?.let { Messenger(it) }
            pendingRun?.also { run ->
                pendingRun = null
                sendRun(run.sources, run.entryPoint, run.timeout, run.projectId)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            update { copy(isRunning = false, executionSummary = "Runner process disconnected") }
        }
    }

    init {
        isBound = context.bindService(
            Intent(context, JavaRunnerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun run(source: String, timeoutSeconds: Int) = run(mapOf("Main.java" to source), "Main", timeoutSeconds)

    fun run(sources: Map<String, String>, entryPoint: String, timeoutSeconds: Int, projectId: String = "") {
        update {
            copy(
                output = emptyList(),
                problems = emptyList(),
                isRunning = true,
                panelVisible = true,
                selectedTab = OutputTab.OUTPUT,
                executionSummary = "",
            )
        }
        if (serviceMessenger == null) {
            if (!isBound) {
                update {
                    copy(
                        isRunning = false,
                        output = output + ConsoleEntry("Could not start the isolated runner service.\n", isStderr = true),
                    )
                }
            } else {
                pendingRun = PendingRun(sources, entryPoint, timeoutSeconds, projectId)
            }
        } else {
            sendRun(sources, entryPoint, timeoutSeconds, projectId)
        }
    }

    fun stop() {
        sendMessage(RunnerProtocol.STOP)
    }

    fun sendStdin(line: String) {
        sendMessage(RunnerProtocol.STDIN, Bundle().apply { putString(RunnerProtocol.KEY_TEXT, line) })
    }

    fun selectTab(tab: OutputTab) = update { copy(selectedTab = tab, panelVisible = true) }

    fun hidePanel() = update { copy(panelVisible = false) }

    fun showPanel() = update { copy(panelVisible = true) }

    fun jumpTo(problem: CompileProblem) {
        update { copy(selectedTab = OutputTab.OUTPUT, panelVisible = false) }
        jumpFlow.tryEmit(problem.line.coerceAtLeast(1))
    }

    private fun sendRun(sources: Map<String, String>, entryPoint: String, timeoutSeconds: Int, projectId: String) {
        val serializedSources = org.json.JSONObject().apply {
            sources.forEach { (path, source) -> put(path, source) }
        }.toString()
        sendMessage(
            RunnerProtocol.RUN,
            Bundle().apply {
                putString(RunnerProtocol.KEY_SOURCES, serializedSources)
                putString(RunnerProtocol.KEY_ENTRY_POINT, entryPoint)
                putString(RunnerProtocol.KEY_PROJECT_ID, projectId)
                putInt(RunnerProtocol.KEY_TIMEOUT, timeoutSeconds.coerceIn(5, 300))
            },
        )
    }

    private fun sendMessage(what: Int, data: Bundle = Bundle()) {
        val message = Message.obtain(null, what).apply {
            this.data = data
            replyTo = replyMessenger
        }
        try {
            serviceMessenger?.send(message)
        } catch (error: Exception) {
            update { copy(isRunning = false, output = output + ConsoleEntry("Could not contact the runner service: ${error.message}\n", true)) }
        }
    }

    private fun update(transform: RunnerUiState.() -> RunnerUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun parseProblems(json: String): List<CompileProblem> = try {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CompileProblem(
                file = item.optString("file", "Main.java"),
                line = item.optInt("line", 1).coerceAtLeast(1),
                column = item.optInt("column", 1).coerceAtLeast(1),
                message = item.optString("message", "Java compilation diagnostic"),
                isWarning = item.optBoolean("warning", false),
                length = item.optInt("length", 1).coerceAtLeast(1),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun onCleared() {
        if (isBound) {
            runCatching { context.unbindService(connection) }
        }
        super.onCleared()
    }

    private companion object {
        const val MAX_CONSOLE_ENTRIES = 2_000
    }
}
