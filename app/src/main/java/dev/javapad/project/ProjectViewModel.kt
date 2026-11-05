package dev.javapad.project

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = mutableState.asStateFlow()
    private val pendingSave = MutableStateFlow<ProjectWorkspace?>(null)

    init {
        viewModelScope.launch {
            pendingSave.collectLatest { workspace ->
                if (workspace != null) {
                    delay(1_500)
                    runCatching { repository.saveWorkspace(workspace) }.onFailure { error ->
                        mutableState.value = mutableState.value.copy(message = error.message ?: "Could not save project")
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                val workspace = repository.ensureDefaultProject()
                mutableState.value = ProjectUiState(repository.listProjects(), workspace, isLoading = false)
            }.onFailure { error ->
                mutableState.value = ProjectUiState(isLoading = false, message = error.message ?: "Could not open project")
            }
        }
    }

    fun updateSource(path: String, source: String) = updateWorkspace { workspace ->
        workspace.copy(files = workspace.files.map { if (it.path == path) it.copy(source = source) else it })
    }

    fun setCursor(path: String, line: Int, column: Int) = updateWorkspace { workspace ->
        workspace.copy(cursorPositions = workspace.cursorPositions + (path to (line.coerceAtLeast(0) to column.coerceAtLeast(0))))
    }

    fun switchFile(path: String) = updateWorkspace { it.copy(activePath = path) }

    fun setEntryPoint(entryPoint: String) = updateWorkspace { it.copy(entryPoint = entryPoint) }

    fun createProject(name: String) {
        launchAction {
            flushPending()
            val workspace = repository.createProject(name)
            mutableState.value = mutableState.value.copy(projects = repository.listProjects(), workspace = workspace, message = null)
        }
    }

    fun openProject(id: String) {
        launchAction {
            flushPending()
            val workspace = repository.loadProject(id)
            mutableState.value = mutableState.value.copy(projects = repository.listProjects(), workspace = workspace, message = null)
        }
    }

    fun renameProject(name: String) {
        mutableState.value.workspace?.let { renameProject(it.id, name) }
    }

    fun renameProject(id: String, name: String) {
        launchAction {
            repository.renameProject(id, name)
            val projects = repository.listProjects()
            val updated = if (mutableState.value.workspace?.id == id) repository.loadProject(id) else mutableState.value.workspace
            mutableState.value = mutableState.value.copy(projects = projects, workspace = updated, message = null)
        }
    }

    fun deleteProject(id: String) {
        launchAction {
            if (mutableState.value.workspace?.id == id) flushPending()
            val activeId = mutableState.value.workspace?.id
            repository.deleteProject(id)
            val remaining = repository.listProjects()
            val next = when {
                activeId != id -> mutableState.value.workspace
                remaining.isNotEmpty() -> repository.loadProject(remaining.first().id)
                else -> repository.ensureDefaultProject()
            }
            mutableState.value = mutableState.value.copy(projects = repository.listProjects(), workspace = next, message = null)
        }
    }

    fun createFile(path: String) = mutateProject { workspace -> repository.createFile(workspace, path) }
    fun renameFile(path: String, newPath: String) = mutateProject { workspace -> repository.renameFile(workspace, path, newPath) }
    fun duplicateFile(path: String) = mutateProject { workspace -> repository.duplicateFile(workspace, path) }
    fun deleteFile(path: String) = mutateProject { workspace -> repository.deleteFile(workspace, path) }

    fun addLibrary(uri: Uri) {
        val current = mutableState.value.workspace ?: return
        launchAction {
            flushPending()
            val updated = repository.addLibrary(current.id, uri)
            mutableState.value = mutableState.value.copy(workspace = updated, projects = repository.listProjects(), message = null)
        }
    }

    fun removeLibrary(name: String) {
        val current = mutableState.value.workspace ?: return
        launchAction {
            flushPending()
            val updated = repository.removeLibrary(current.id, name)
            mutableState.value = mutableState.value.copy(workspace = updated, projects = repository.listProjects(), message = null)
        }
    }

    fun importProject(uri: Uri) {
        launchAction {
            flushPending()
            val workspace = repository.importProject(uri)
            mutableState.value = mutableState.value.copy(projects = repository.listProjects(), workspace = workspace, message = null)
        }
    }

    fun exportProject(uri: Uri) {
        val workspace = mutableState.value.workspace ?: return
        exportProject(workspace.id, uri)
    }

    fun exportProject(id: String, uri: Uri) {
        launchAction { repository.exportProject(id, uri) }
    }

    fun flush() {
        val workspace = mutableState.value.workspace ?: return
        viewModelScope.launch(Dispatchers.IO) { repository.saveWorkspace(workspace) }
    }

    fun dismissMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun updateWorkspace(transform: (ProjectWorkspace) -> ProjectWorkspace) {
        val current = mutableState.value.workspace ?: return
        val updated = transform(current)
        mutableState.value = mutableState.value.copy(workspace = updated)
        pendingSave.value = updated
    }

    private fun mutateProject(action: suspend (ProjectWorkspace) -> ProjectWorkspace) {
        launchAction {
            flushPending()
            val current = mutableState.value.workspace ?: return@launchAction
            val updated = action(current)
            mutableState.value = mutableState.value.copy(workspace = updated, projects = repository.listProjects(), message = null)
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }.onFailure { error ->
                mutableState.value = mutableState.value.copy(message = error.message ?: "Project operation failed")
            }
        }
    }

    private suspend fun flushPending() {
        pendingSave.value?.let { workspace ->
            withContext(Dispatchers.IO) { repository.saveWorkspace(workspace) }
            pendingSave.value = null
        }
    }
}
