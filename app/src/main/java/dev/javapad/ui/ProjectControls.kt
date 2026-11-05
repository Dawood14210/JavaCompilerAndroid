package dev.javapad.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.javapad.project.ProjectWorkspace

private enum class FileAction { CREATE, RENAME, DELETE }

@Composable
internal fun ProjectFilesBar(
    workspace: ProjectWorkspace,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var action by remember { mutableStateOf<FileAction?>(null) }
    var path by remember(workspace.activePath, action) {
        mutableStateOf(if (action == FileAction.RENAME) workspace.activePath else "NewFile.java")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workspace.files.forEach { file ->
                Surface(
                    onClick = { onSwitch(file.path) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (file.path == workspace.activePath) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(end = 5.dp),
                ) {
                    Text(
                        file.path,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (file.path == workspace.activePath) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { action = FileAction.CREATE }) { Text("+ File") }
        TextButton(onClick = { onDuplicate(workspace.activePath) }) { Text("Copy") }
        TextButton(onClick = { action = FileAction.RENAME }) { Text("Rename") }
        TextButton(onClick = { action = FileAction.DELETE }) { Text("Delete") }
    }

    when (action) {
        FileAction.CREATE, FileAction.RENAME -> {
            val currentAction = action ?: return
            AlertDialog(
                onDismissRequest = { action = null },
                title = { Text(if (currentAction == FileAction.CREATE) "Create Java file" else "Rename Java file") },
                text = {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Path (packages use folders)") },
                        placeholder = { Text("com/example/Helper.java") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (currentAction == FileAction.CREATE) onCreate(path) else onRename(workspace.activePath, path)
                        action = null
                    }) { Text(if (currentAction == FileAction.CREATE) "Create" else "Rename") }
                },
                dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
            )
        }
        FileAction.DELETE -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Delete ${workspace.activePath}?") },
            text = { Text("The file will be removed from this project. A project must keep at least one Java file.") },
            confirmButton = {
                TextButton(onClick = { onDelete(workspace.activePath); action = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
internal fun EntryPointMenu(workspace: ProjectWorkspace, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = entryPointChoices(workspace)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { expanded = true }) { Text("Entry: ${workspace.entryPoint.substringAfterLast('.')}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (choices.isEmpty()) {
                DropdownMenuItem(text = { Text("No main method found") }, onClick = { expanded = false }, enabled = false)
            }
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = { onChoose(choice); expanded = false },
                )
            }
        }
    }
}

@Composable
internal fun ProjectLibrariesDialog(
    workspace: ProjectWorkspace,
    onAdd: (Uri) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onAdd) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project JAR libraries") },
        text = {
            Column {
                Text("Local libraries are included in project exports. Total JAR storage is limited to 40 MB.", style = MaterialTheme.typography.bodySmall)
                if (workspace.libraries.isEmpty()) {
                    Text("No JARs added", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 8.dp)) {
                        items(workspace.libraries, key = { it }) { library ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(library, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onRemove(library) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { launcher.launch(arrayOf("application/java-archive", "application/zip", "*/*")) }) { Text("Add JAR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun entryPointChoices(workspace: ProjectWorkspace): List<String> = workspace.files.flatMap { file ->
    val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;")
        .find(file.source)?.groupValues?.get(1)
    val hasMain = Regex("\\bstatic\\s+void\\s+main\\s*\\(").containsMatchIn(file.source)
    if (!hasMain) emptyList() else Regex("\\b(?:class|record|enum)\\s+([A-Za-z_$][\\w$]*)")
        .findAll(file.source)
        .map { listOfNotNull(packageName, it.groupValues[1]).joinToString(".") }
        .toList()
}.distinct().ifEmpty { listOf(workspace.entryPoint) }
