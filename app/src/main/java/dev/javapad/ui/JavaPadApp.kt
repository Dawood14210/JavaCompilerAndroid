package dev.javapad.ui

import android.graphics.Typeface
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.javapad.compiler.CompileProblem
import dev.javapad.compiler.CompilerViewModel
import dev.javapad.compiler.OutputTab
import dev.javapad.compiler.RunnerUiState
import dev.javapad.completion.CompletionSuggestion
import dev.javapad.completion.CompletionUiState
import dev.javapad.completion.CompletionViewModel
import dev.javapad.project.ProjectFile
import dev.javapad.project.ProjectSummary
import dev.javapad.project.ProjectUiState
import dev.javapad.project.ProjectViewModel
import dev.javapad.project.ProjectWorkspace
import dev.javapad.editor.treesitter.JavaLanguageSpec
import dev.javapad.editor.treesitter.TsLanguageJava
import dev.javapad.settings.AppThemeMode
import dev.javapad.settings.AppearanceSettings
import dev.javapad.settings.SettingsViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun JavaPadApp(settings: AppearanceSettings, settingsViewModel: SettingsViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    val projectViewModel: ProjectViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val compilerViewModel: CompilerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val completionViewModel: CompletionViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val projectState = projectViewModel.state.collectAsState().value
    val runnerState = compilerViewModel.state.collectAsState().value
    val completionState = completionViewModel.state.collectAsState().value
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(enabled = showSettings || showProjects) {
        if (showSettings) showSettings = false else showProjects = false
    }
    DisposableEffect(lifecycleOwner, projectViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) projectViewModel.flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        showSettings -> SettingsScreen(
            settings = settings,
            onThemeSelected = settingsViewModel::chooseTheme,
            onFontSizeChanged = settingsViewModel::chooseFontSize,
            onTimeoutChanged = settingsViewModel::chooseTimeout,
            onBack = { showSettings = false },
        )
        showProjects -> ProjectsScreen(
            state = projectState,
            onBack = { showProjects = false },
            onCreate = projectViewModel::createProject,
            onOpen = { id -> projectViewModel.openProject(id); showProjects = false },
            onRename = projectViewModel::renameProject,
            onDelete = projectViewModel::deleteProject,
            onImport = projectViewModel::importProject,
            onExport = projectViewModel::exportProject,
            onDismissMessage = projectViewModel::dismissMessage,
        )
        projectState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        projectState.workspace == null -> ProjectsScreen(
            state = projectState,
            onBack = { },
            onCreate = projectViewModel::createProject,
            onOpen = projectViewModel::openProject,
            onRename = projectViewModel::renameProject,
            onDelete = projectViewModel::deleteProject,
            onImport = projectViewModel::importProject,
            onExport = projectViewModel::exportProject,
            onDismissMessage = projectViewModel::dismissMessage,
        )
        else -> {
            val workspace = projectState.workspace
            EditorScreen(
                workspace = workspace,
                onSourceChanged = { projectViewModel.updateSource(workspace.activePath, it) },
                onCursorChanged = { line, column -> projectViewModel.setCursor(workspace.activePath, line, column) },
                onSwitchFile = projectViewModel::switchFile,
                onSetEntryPoint = projectViewModel::setEntryPoint,
                onCreateFile = projectViewModel::createFile,
                onRenameFile = projectViewModel::renameFile,
                onDuplicateFile = projectViewModel::duplicateFile,
                onDeleteFile = projectViewModel::deleteFile,
                onAddLibrary = projectViewModel::addLibrary,
                onRemoveLibrary = projectViewModel::removeLibrary,
                settings = settings,
                runnerState = runnerState,
                runnerViewModel = compilerViewModel,
                completionState = completionState,
                completionViewModel = completionViewModel,
                projectName = workspace.name,
                projectMessage = projectState.message,
                onDismissProjectMessage = projectViewModel::dismissMessage,
                onOpenProjects = { showProjects = true },
                onOpenSettings = { showSettings = true },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    workspace: ProjectWorkspace,
    onSourceChanged: (String) -> Unit,
    onCursorChanged: (Int, Int) -> Unit,
    onSwitchFile: (String) -> Unit,
    onSetEntryPoint: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRenameFile: (String, String) -> Unit,
    onDuplicateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onAddLibrary: (Uri) -> Unit,
    onRemoveLibrary: (String) -> Unit,
    settings: AppearanceSettings,
    runnerState: RunnerUiState,
    runnerViewModel: CompilerViewModel,
    completionState: CompletionUiState,
    completionViewModel: CompletionViewModel,
    projectName: String,
    projectMessage: String?,
    onDismissProjectMessage: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showLibraries by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text("JavaPad", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    if (runnerState.isRunning) runnerViewModel.stop()
                    else runnerViewModel.run(
                        workspace.files.associate { it.path to it.source },
                        workspace.entryPoint,
                        settings.timeoutSeconds,
                        workspace.id,
                    )
                }) {
                    Icon(
                        imageVector = if (runnerState.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (runnerState.isRunning) "Stop program" else "Run project",
                        tint = if (runnerState.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                if (!runnerState.panelVisible && runnerState.output.isNotEmpty()) {
                    TextButton(onClick = runnerViewModel::showPanel) { Text("Output") }
                }
                EntryPointMenu(workspace, onSetEntryPoint)
                TextButton(onClick = { showLibraries = true }) { Text("JARs ${workspace.libraries.size}") }
                TextButton(onClick = onOpenProjects) { Text("Projects") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        ProjectFilesBar(
            workspace = workspace,
            onSwitch = onSwitchFile,
            onCreate = onCreateFile,
            onRename = onRenameFile,
            onDuplicate = onDuplicateFile,
            onDelete = onDeleteFile,
        )
        if (showLibraries) {
            ProjectLibrariesDialog(
                workspace = workspace,
                onAdd = onAddLibrary,
                onRemove = onRemoveLibrary,
                onDismiss = { showLibraries = false },
            )
        }
        projectMessage?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                    Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissProjectMessage) { Text("Dismiss") }
                }
            }
        }
        EditorPane(
            source = workspace.activeSource,
            projectSources = workspace.files.filter { it.path != workspace.activePath }.map { it.source },
            activePath = workspace.activePath,
            cursorPosition = workspace.cursorPositions[workspace.activePath] ?: (0 to 0),
            onSourceChanged = onSourceChanged,
            onCursorChanged = onCursorChanged,
            fontSizeSp = settings.fontSizeSp,
            themeMode = settings.theme,
            problems = runnerState.problems.filter { it.file == workspace.activePath },
            jumpRequests = runnerViewModel.jumpRequests,
            completionState = completionState,
            completionViewModel = completionViewModel,
            modifier = Modifier.weight(1f),
        )
        if (runnerState.panelVisible) {
            OutputPanel(
                state = runnerState,
                onTabSelected = runnerViewModel::selectTab,
                onClose = runnerViewModel::hidePanel,
                onSendInput = runnerViewModel::sendStdin,
                onProblemSelected = { problem ->
                    if (workspace.files.any { it.path == problem.file }) onSwitchFile(problem.file)
                    scope.launch {
                        delay(100)
                        runnerViewModel.jumpTo(problem)
                    }
                },
                onImportQuickFix = { problem ->
                    workspace.files.firstOrNull { it.path == problem.file }?.let { problemFile ->
                        if (problemFile.path != workspace.activePath) onSwitchFile(problemFile.path)
                        scope.launch {
                            delay(100)
                            completionViewModel.requestImportQuickFix(problemFile.source, problem.line, problem.column)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EditorPane(
    source: String,
    projectSources: List<String>,
    activePath: String,
    cursorPosition: Pair<Int, Int>,
    onSourceChanged: (String) -> Unit,
    onCursorChanged: (Int, Int) -> Unit,
    fontSizeSp: Float,
    themeMode: AppThemeMode,
    problems: List<CompileProblem>,
    jumpRequests: Flow<Int>,
    completionState: CompletionUiState,
    completionViewModel: CompletionViewModel,
    modifier: Modifier = Modifier,
) {
    val latestSourceChanged = rememberUpdatedState(onSourceChanged)
    val latestCursorChanged = rememberUpdatedState(onCursorChanged)
    val latestProjectSources = rememberUpdatedState(projectSources)
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var appliedDiagnostics by remember { mutableStateOf<List<CompileProblem>?>(null) }
    var appliedPath by remember { mutableStateOf<String?>(null) }
    var appliedTheme by remember { mutableStateOf<AppThemeMode?>(null) }
    var activeTabStops by remember { mutableStateOf<List<Int>>(emptyList()) }
    var nextTabStop by remember { mutableStateOf(0) }

    DisposableEffect(editorRef) {
        onDispose { editorRef?.release() }
    }
    LaunchedEffect(editorRef, jumpRequests) {
        val editor = editorRef ?: return@LaunchedEffect
        jumpRequests.collect { line ->
            val targetLine = (line - 1).coerceIn(0, editor.lineCount - 1)
            editor.setSelection(targetLine, 0)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("JAVA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("Tree-sitter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${source.lineSequence().count()} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { viewContext ->
                val editor = CodeEditor(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setText(source)
                    val initialLine = cursorPosition.first.coerceIn(0, lineCount - 1)
                    setSelection(initialLine, cursorPosition.second.coerceIn(0, text.getColumnCount(initialLine)))
                    appliedPath = activePath
                    setLineNumberEnabled(true)
                    setTabWidth(4)
                    setTextSize(fontSizeSp)
                    typefaceText = Typeface.createFromAsset(viewContext.assets, "fonts/JetBrainsMono-Regular.ttf")
                    props.autoIndent = true
                    props.symbolPairAutoCompletion = true
                    props.overrideSymbolPairs.putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
                    props.overrideSymbolPairs.putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
                    props.overrideSymbolPairs.putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
                    props.overrideSymbolPairs.putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
                    props.overrideSymbolPairs.putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
                    setColorScheme(editorColorScheme(themeMode))
                    appliedTheme = themeMode
                    setEditorLanguage(createJavaTreeSitterLanguage(viewContext))
                    subscribeAlways<ContentChangeEvent> {
                        val currentSource = text.toString()
                        latestSourceChanged.value(currentSource)
                        val cursor = text.cursor
                        latestCursorChanged.value(cursor.rightLine, cursor.rightColumn)
                        completionViewModel.update(currentSource, cursor.right, projectSources = latestProjectSources.value)
                    }
                    subscribeAlways<SelectionChangeEvent> {
                        val currentSource = text.toString()
                        val cursor = text.cursor
                        latestCursorChanged.value(cursor.rightLine, cursor.rightColumn)
                        completionViewModel.update(currentSource, cursor.right, projectSources = latestProjectSources.value)
                    }
                }
                editorRef = editor
                editor
            },
            update = { editor ->
                // The text comparison avoids resetting selection/cursor while typing.
                if (editor.text.toString() != source) editor.setText(source)
                if (appliedPath != activePath) {
                    val restoreLine = cursorPosition.first.coerceIn(0, editor.lineCount - 1)
                    val restoreColumn = cursorPosition.second.coerceIn(0, editor.text.getColumnCount(restoreLine))
                    editor.setSelection(restoreLine, restoreColumn)
                    appliedPath = activePath
                }
                editor.setTextSize(fontSizeSp)
                if (appliedTheme != themeMode) {
                    editor.setColorScheme(editorColorScheme(themeMode))
                    appliedTheme = themeMode
                }
                if (appliedDiagnostics != problems) {
                    editor.setDiagnostics(createDiagnostics(editor, problems))
                    appliedDiagnostics = problems
                }
            },
        )

        if (completionState.visible) {
            CompletionPopup(
                state = completionState,
                onSelect = { suggestion ->
                    editorRef?.let { editor ->
                        activeTabStops = applyCompletion(editor, suggestion, completionState.prefix)
                        nextTabStop = if (activeTabStops.isEmpty()) 0 else 1
                    }
                    completionViewModel.dismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SymbolBar(editorRef, completionViewModel, projectSources) { editor ->
            if (activeTabStops.isEmpty() || nextTabStop >= activeTabStops.size) {
                activeTabStops = emptyList()
                nextTabStop = 0
                editor.commitText("\\t")
            } else {
                val previousStop = activeTabStops[(nextTabStop - 1).coerceAtLeast(0)]
                val delta = editor.text.cursor.right - previousStop
                val adjustedStops = activeTabStops.mapIndexed { index, offset ->
                    if (index >= nextTabStop) offset + delta else offset
                }
                val target = adjustedStops[nextTabStop].coerceIn(0, editor.text.length())
                val position = editor.text.indexer.getCharPosition(target)
                editor.setSelection(position.line, position.column)
                activeTabStops = adjustedStops
                nextTabStop++
            }
        }
    }
}

private fun applyCompletion(editor: CodeEditor, suggestion: CompletionSuggestion, prefix: String): List<Int> {
    val content = editor.text
    var cursor = content.cursor.right
    val source = content.toString()
    val importName = suggestion.importName
    if (importName != null && !Regex("(?m)^\\s*import\\s+${Regex.escape(importName)}\\s*;").containsMatchIn(source)) {
        val packageLine = Regex("(?m)^\\s*package\\s+[^;]+;[^\\n]*\\n").find(source)
        val importOffset = packageLine?.range?.last?.plus(1) ?: 0
        val position = content.indexer.getCharPosition(importOffset)
        val importText = "import $importName;\\n"
        content.insert(position.line, position.column, importText)
        if (importOffset <= cursor) cursor += importText.length
    }

    val startOffset = (cursor - prefix.length).coerceAtLeast(0)
    val start = content.indexer.getCharPosition(startOffset)
    val end = content.indexer.getCharPosition(cursor)
    editor.setSelectionRegion(start.line, start.column, end.line, end.column)
    editor.commitText(suggestion.insertion)
    val caretOffset = startOffset + suggestion.cursorOffset.coerceIn(0, suggestion.insertion.length)
    val caret = content.indexer.getCharPosition(caretOffset)
    editor.setSelection(caret.line, caret.column)
    if (suggestion.kind != dev.javapad.completion.CompletionKind.SNIPPET) return emptyList()
    return (suggestion.tabStops.ifEmpty { listOf(suggestion.cursorOffset) })
        .map { startOffset + it.coerceIn(0, suggestion.insertion.length) }
}

@Composable
private fun CompletionPopup(
    state: CompletionUiState,
    onSelect: (CompletionSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(max = 190.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 6.dp,
    ) {
        Column {
            state.parameterHint?.let { hint ->
                Text(
                    hint,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn {
                itemsIndexed(state.suggestions) { _, suggestion ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(suggestion) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(suggestion.kind.name.first().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(suggestion.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text(suggestion.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                suggestion.signature,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun editorColorScheme(mode: AppThemeMode): EditorColorScheme =
    (if (mode == AppThemeMode.LIGHT) SchemeGitHub() else SchemeDarcula()).apply {
        if (mode == AppThemeMode.AMOLED) {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, android.graphics.Color.BLACK)
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, android.graphics.Color.BLACK)
        }
    }

private fun createDiagnostics(editor: CodeEditor, problems: List<CompileProblem>): DiagnosticsContainer? {
    if (problems.isEmpty()) return null
    val diagnostics = DiagnosticsContainer(true)
    problems.forEachIndexed { index, problem ->
        val line = (problem.line - 1).coerceIn(0, editor.lineCount - 1)
        val column = (problem.column - 1).coerceIn(0, editor.text.getColumnCount(line))
        val start = editor.text.getCharIndex(line, column)
        val end = (start + problem.length.coerceAtLeast(1)).coerceAtMost(editor.text.length())
        val severity = if (problem.isWarning) DiagnosticRegion.SEVERITY_WARNING else DiagnosticRegion.SEVERITY_ERROR
        diagnostics.addDiagnostic(
            DiagnosticRegion(start, end, severity, index.toLong(), DiagnosticDetail(problem.message)),
        )
    }
    return diagnostics
}

private fun createJavaTreeSitterLanguage(context: android.content.Context): TsLanguageJava {
    // These are the same native library names used by Sora's official Java tree-sitter demo.
    System.loadLibrary("android-tree-sitter")
    System.loadLibrary("tree-sitter-java")
    val queries = context.assets
    return TsLanguageJava(
        JavaLanguageSpec(
            highlightScmSource = queries.open("tree-sitter-queries/java/highlights.scm").bufferedReader().use { it.readText() },
            codeBlocksScmSource = queries.open("tree-sitter-queries/java/blocks.scm").bufferedReader().use { it.readText() },
            bracketsScmSource = queries.open("tree-sitter-queries/java/brackets.scm").bufferedReader().use { it.readText() },
            localsScmSource = queries.open("tree-sitter-queries/java/locals.scm").bufferedReader().use { it.readText() },
        ),
    )
}

@Composable
private fun SymbolBar(
    editor: CodeEditor?,
    completionViewModel: CompletionViewModel,
    projectSources: List<String>,
    onTabPressed: (CodeEditor) -> Unit,
) {
    val symbols = listOf(
        "Tab" to "\t", "{" to "{", "}" to "}", "(" to "(", ")" to ")",
        "[" to "[", "]" to "]", ";" to ";", "\"" to "\"", "<" to "<",
        ">" to ">", "=" to "=", "+" to "+", "-" to "-", "*" to "*",
        "/" to "/", "&" to "&", "|" to "|", "!" to "!", "." to ".", "," to ",",
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            symbols.forEach { (label, inserted) ->
                SymbolButton(label) {
                    editor?.let { view -> if (label == "Tab") onTabPressed(view) else view.commitText(inserted) }
                }
            }
            SymbolButton("Ctrl+Space") {
                editor?.let { completionViewModel.update(it.text.toString(), it.text.cursor.right, force = true, projectSources = projectSources) }
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = { editor?.undo() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = { editor?.redo() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun OutputPanel(
    state: RunnerUiState,
    onTabSelected: (OutputTab) -> Unit,
    onClose: () -> Unit,
    onSendInput: (String) -> Unit,
    onProblemSelected: (CompileProblem) -> Unit,
    onImportQuickFix: (CompileProblem) -> Unit,
) {
    var inputLine by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.output.size, state.selectedTab) {
        if (state.selectedTab == OutputTab.OUTPUT && state.output.isNotEmpty()) {
            listState.scrollToItem(state.output.lastIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(250.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(OutputTab.OUTPUT to "Output", OutputTab.PROBLEMS to "Problems (${state.problems.size})").forEach { (tab, title) ->
                    val selected = state.selectedTab == tab
                    Surface(
                        onClick = { onTabSelected(tab) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            title,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close output panel")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (state.selectedTab == OutputTab.OUTPUT) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (state.output.isEmpty()) {
                        item { Text("Run a program to see its output.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                    itemsIndexed(state.output) { _, entry ->
                        Text(
                            text = entry.text,
                            color = if (entry.isStderr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                if (state.isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = inputLine,
                            onValueChange = { inputLine = it },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            singleLine = true,
                            placeholder = { Text("Program input…") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            onSendInput(inputLine)
                            inputLine = ""
                        }) { Text("Send") }
                    }
                }
            } else if (state.problems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No compile problems.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(state.problems) { _, problem ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onProblemSelected(problem) }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = if (problem.isWarning) Icons.Filled.WarningAmber else Icons.Filled.ErrorOutline,
                                contentDescription = if (problem.isWarning) "Warning" else "Error",
                                tint = if (problem.isWarning) Color(0xFFFFB300) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "${problem.file}:${problem.line}:${problem.column}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(problem.message, style = MaterialTheme.typography.bodySmall)
                                if (!problem.isWarning && problem.message.contains(Regex("cannot be resolved|cannot find symbol", RegexOption.IGNORE_CASE))) {
                                    TextButton(onClick = { onImportQuickFix(problem) }) { Text("Add import") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 2.dp),
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = if (label == "Tab") 10.dp else 9.dp, vertical = 7.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppearanceSettings,
    onThemeSelected: (AppThemeMode) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onTimeoutChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Editor settings", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Choose how JavaPad looks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = settings.theme == mode
                    if (selected) {
                        Button(onClick = { onThemeSelected(mode) }, modifier = Modifier.weight(1f)) {
                            Text(mode.label())
                        }
                    } else {
                        OutlinedButton(onClick = { onThemeSelected(mode) }, modifier = Modifier.weight(1f)) {
                            Text(mode.label())
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("Code font", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("JetBrains Mono · ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.fontSizeSp,
                onValueChange = onFontSizeChanged,
                valueRange = 12f..28f,
                steps = 15,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("12 sp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("28 sp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("Run timeout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${settings.timeoutSeconds} seconds", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.timeoutSeconds.toFloat(),
                onValueChange = { onTimeoutChanged(it.toInt()) },
                valueRange = 5f..120f,
                steps = 22,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("5 sec", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("120 sec", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Text(
                    "Your editor and run preferences are saved on this device.",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun AppThemeMode.label(): String = when (this) {
    AppThemeMode.LIGHT -> "Light"
    AppThemeMode.DARK -> "Dark"
    AppThemeMode.AMOLED -> "AMOLED"
}
