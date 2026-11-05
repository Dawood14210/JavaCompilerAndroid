package dev.javapad.project

data class ProjectFile(
    val path: String,
    val source: String,
)

data class ProjectWorkspace(
    val id: String,
    val name: String,
    val files: List<ProjectFile>,
    val activePath: String,
    val entryPoint: String,
    val cursorPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    val libraries: List<String> = emptyList(),
) {
    val activeSource: String get() = files.firstOrNull { it.path == activePath }?.source.orEmpty()
}

data class ProjectSummary(val id: String, val name: String, val fileCount: Int, val libraryCount: Int = 0)

data class ProjectUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val workspace: ProjectWorkspace? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
)
