package dev.javapad.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.javapad.project.ProjectSummary
import dev.javapad.project.ProjectUiState

private sealed interface ProjectDialog {
    data object Create : ProjectDialog
    data class Rename(val id: String, val name: String) : ProjectDialog
    data class Delete(val id: String, val name: String) : ProjectDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsScreen(
    state: ProjectUiState,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: (String, Uri) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var dialog by remember { mutableStateOf<ProjectDialog?>(null) }
    var name by remember { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) onExport(target, uri)
        exportTarget = null
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Projects") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Import ZIP") }
                Button(onClick = { name = ""; dialog = ProjectDialog.Create }) { Text("New project") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        HorizontalDivider()
        if (state.projects.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No projects yet", style = MaterialTheme.typography.titleLarge)
                Text("Create a project or import a .zip archive.", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        active = state.workspace?.id == project.id,
                        onOpen = { onOpen(project.id) },
                        onExport = {
                            exportTarget = project.id
                            exportLauncher.launch("${safeFilename(project.name)}.zip")
                        },
                        onRename = { name = project.name; dialog = ProjectDialog.Rename(project.id, project.name) },
                        onDelete = { dialog = ProjectDialog.Delete(project.id, project.name) },
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        ProjectDialog.Create -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("New project") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onCreate(name); dialog = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is ProjectDialog.Rename -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(current.id, name); dialog = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is ProjectDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${current.name}?") },
            text = { Text("This permanently removes the project and its source files from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(current.id); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Project operation") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${project.fileCount} Java file${if (project.fileCount == 1) "" else "s"} · ${project.libraryCount} JAR${if (project.libraryCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, enabled = !active) { Text(if (active) "Active" else "Open") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onExport) { Text("Export ZIP") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun safeFilename(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "JavaPadProject" }
