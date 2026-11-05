package dev.javapad.compiler

data class CompileProblem(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val isWarning: Boolean = false,
    val length: Int = 1,
)

data class ConsoleEntry(val text: String, val isStderr: Boolean = false)

enum class OutputTab { OUTPUT, PROBLEMS }

data class RunnerUiState(
    val output: List<ConsoleEntry> = emptyList(),
    val problems: List<CompileProblem> = emptyList(),
    val isRunning: Boolean = false,
    val panelVisible: Boolean = false,
    val selectedTab: OutputTab = OutputTab.OUTPUT,
    val executionSummary: String = "",
)
