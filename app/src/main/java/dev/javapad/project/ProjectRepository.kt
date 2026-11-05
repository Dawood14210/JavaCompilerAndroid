package dev.javapad.project

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.javapad.editor.DEFAULT_JAVA_SOURCE
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class ProjectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    private val activeProjectFile: File get() = File(context.filesDir, "active-project-id")
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun ensureDefaultProject(): ProjectWorkspace = withContext(Dispatchers.IO) {
        val projects = listProjects()
        val rememberedId = activeProjectFile.takeIf { it.isFile }?.readText()?.trim()
        val existing = projects.firstOrNull { it.id == rememberedId } ?: projects.firstOrNull()
        if (existing != null) return@withContext loadProject(existing.id)
        val id = UUID.randomUUID().toString()
        val workspace = ProjectWorkspace(id, "My Java Project", listOf(ProjectFile("Main.java", DEFAULT_JAVA_SOURCE)), "Main.java", "Main")
        writeWorkspace(workspace)
        activeProjectFile.writeText(id)
        workspace
    }

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, META_FILE).isFile }.mapNotNull { folder ->
            runCatching {
                val meta = JSONObject(File(folder, META_FILE).readText())
                ProjectSummary(
                    meta.optString("id", folder.name),
                    meta.optString("name", folder.name),
                    javaFiles(folder).size,
                    libraryFiles(folder).size,
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun loadProject(id: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val folder = projectDir(id)
        require(File(folder, META_FILE).isFile) { "Project does not exist" }
        activeProjectFile.writeText(id)
        readWorkspace(folder)
    }

    suspend fun createProject(name: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val normalizedName = name.trim().ifBlank { "Java Project" }
        val id = UUID.randomUUID().toString()
        val workspace = ProjectWorkspace(id, normalizedName, listOf(ProjectFile("Main.java", DEFAULT_JAVA_SOURCE)), "Main.java", "Main")
        writeWorkspace(workspace)
        activeProjectFile.writeText(id)
        workspace
    }

    suspend fun renameProject(id: String, name: String) = withContext(Dispatchers.IO) {
        val workspace = readWorkspace(projectDir(id))
        writeWorkspace(workspace.copy(name = name.trim().ifBlank { workspace.name }))
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        projectDir(id).deleteRecursively()
        if (activeProjectFile.isFile && activeProjectFile.readText().trim() == id) activeProjectFile.delete()
    }

    suspend fun saveWorkspace(workspace: ProjectWorkspace) = withContext(Dispatchers.IO) {
        writeWorkspace(workspace)
    }

    suspend fun addLibrary(id: String, uri: Uri): ProjectWorkspace = withContext(Dispatchers.IO) {
        val originalName = safeLibraryName(uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':'))
        val directory = File(projectDir(id), "lib").apply { mkdirs() }
        val stem = originalName.dropLast(4)
        var name = originalName
        var suffix = 2
        while (directory.listFiles().orEmpty().any { it.name.equals(name, ignoreCase = true) }) {
            name = "$stem-$suffix.jar"
            suffix++
        }
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        val existingBytes = libraryFiles(projectDir(id)).sumOf { it.length() }
        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(existingBytes + total <= MAX_LIBRARY_BYTES) { "Project JAR libraries are limited to 40 MB total" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Could not open the selected JAR")
        check(temp.renameTo(target)) { "Could not save the selected JAR" }
        readWorkspace(projectDir(id))
    }

    suspend fun removeLibrary(id: String, name: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val file = File(File(projectDir(id), "lib"), safeLibraryName(name))
        require(file.isFile && file.delete()) { "Could not remove the selected JAR" }
        readWorkspace(projectDir(id))
    }

    suspend fun createFile(workspace: ProjectWorkspace, requestedPath: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val path = safeJavaPath(requestedPath)
        require(workspace.files.none { it.path == path }) { "A file with this path already exists" }
        val updated = workspace.copy(
            files = workspace.files + ProjectFile(path, ""),
            activePath = path,
        )
        writeWorkspace(updated)
        updated
    }

    suspend fun renameFile(workspace: ProjectWorkspace, oldPath: String, requestedPath: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val newPath = safeJavaPath(requestedPath)
        require(workspace.files.none { it.path == newPath }) { "A file with this path already exists" }
        val updated = workspace.copy(
            files = workspace.files.map { if (it.path == oldPath) it.copy(path = newPath) else it },
            activePath = if (workspace.activePath == oldPath) newPath else workspace.activePath,
            cursorPositions = workspace.cursorPositions.toMutableMap().apply {
                remove(oldPath)?.let { put(newPath, it) }
            },
        )
        require(updated.files.any { it.path == newPath }) { "File not found" }
        writeWorkspace(updated)
        updated
    }

    suspend fun duplicateFile(workspace: ProjectWorkspace, path: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        val source = workspace.files.first { it.path == path }
        val file = File(path)
        val base = file.nameWithoutExtension
        val extension = file.extension.ifBlank { "java" }
        var index = 1
        var candidate: String
        do {
            val name = if (index == 1) "${base}Copy.$extension" else "${base}Copy$index.$extension"
            candidate = listOf(file.parent?.takeIf { it != "." }, name).filterNotNull().joinToString("/")
            index++
        } while (workspace.files.any { it.path == candidate })
        val updated = workspace.copy(files = workspace.files + source.copy(path = candidate), activePath = candidate)
        writeWorkspace(updated)
        updated
    }

    suspend fun deleteFile(workspace: ProjectWorkspace, path: String): ProjectWorkspace = withContext(Dispatchers.IO) {
        require(workspace.files.size > 1) { "A project must keep at least one Java file" }
        val remaining = workspace.files.filterNot { it.path == path }
        require(remaining.size < workspace.files.size) { "File not found" }
        val active = if (workspace.activePath == path) remaining.first().path else workspace.activePath
        val updated = workspace.copy(
            files = remaining,
            activePath = active,
            cursorPositions = workspace.cursorPositions - path,
        )
        writeWorkspace(updated)
        updated
    }

    suspend fun exportProject(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val projectDirectory = projectDir(id)
        val workspace = readWorkspace(projectDirectory)
        val libraries = libraryFiles(projectDirectory)
        val metadata = serialize(workspace).toByteArray(Charsets.UTF_8)
        val uncompressedBytes = workspace.files.sumOf { it.source.toByteArray(Charsets.UTF_8).size.toLong() } +
            libraries.sumOf { it.length() } + metadata.size
        require(uncompressedBytes <= MAX_IMPORT_BYTES) { "Project export content exceeds the 50 MB archive limit" }
        resolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                workspace.files.forEach { projectFile ->
                    zip.putNextEntry(ZipEntry(safeJavaPath(projectFile.path)))
                    zip.write(projectFile.source.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                libraries.forEach { library ->
                    zip.putNextEntry(ZipEntry("lib/${library.name}"))
                    library.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(META_FILE))
                zip.write(metadata)
                zip.closeEntry()
            }
        } ?: error("Could not open the selected export destination")
    }

    suspend fun importProject(uri: Uri): ProjectWorkspace = withContext(Dispatchers.IO) {
        val files = linkedMapOf<String, String>()
        val libraries = linkedMapOf<String, ByteArray>()
        var totalLibraryBytes = 0L
        var metadata: JSONObject? = null
        resolver.openInputStream(uri)?.use { input ->
            readZip(input) { name, bytes ->
                if (name == META_FILE) metadata = JSONObject(bytes.toString(Charsets.UTF_8))
                else if (name.endsWith(".java", ignoreCase = true)) files[safeJavaPath(name)] = bytes.toString(Charsets.UTF_8)
                else if (name.startsWith("lib/", ignoreCase = true) && name.endsWith(".jar", ignoreCase = true)) {
                    totalLibraryBytes += bytes.size
                    require(totalLibraryBytes <= MAX_LIBRARY_BYTES) { "Project JAR libraries are limited to 40 MB total" }
                    libraries[safeLibraryName(name.substringAfterLast('/'))] = bytes
                }
            }
        } ?: error("Could not open the selected project archive")
        require(files.isNotEmpty()) { "The archive contains no Java source files" }
        val id = UUID.randomUUID().toString()
        val projectFiles = files.map { ProjectFile(it.key, it.value) }
        val importedCursors = mutableMapOf<String, Pair<Int, Int>>()
        val cursorJson = metadata?.optJSONObject("cursors") ?: JSONObject()
        cursorJson.keys().forEach { path ->
            if (path in files) cursorJson.optJSONArray(path)?.let { importedCursors[path] = it.optInt(0) to it.optInt(1) }
        }
        val workspace = ProjectWorkspace(
            id = id,
            name = metadata?.optString("name")?.takeIf { !it.isNullOrBlank() } ?: "Imported Project",
            files = projectFiles,
            activePath = metadata?.optString("activePath")?.takeIf { it in files } ?: files.keys.first(),
            entryPoint = metadata?.optString("entryPoint", "Main") ?: "Main",
            cursorPositions = importedCursors,
            libraries = libraries.keys.toList(),
        )
        writeWorkspace(workspace)
        libraries.forEach { (name, bytes) -> writeLibrary(id, name, bytes) }
        activeProjectFile.writeText(id)
        workspace
    }

    private fun readZip(input: InputStream, consume: (String, ByteArray) -> Unit) {
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) { "Unsafe path in project archive" }
                    val bytes = readEntry(zip, MAX_IMPORT_BYTES - totalBytes)
                    totalBytes += bytes.size
                    if (
                        normalized == META_FILE || normalized.endsWith(".java", true) ||
                        normalized.startsWith("lib/", ignoreCase = true) && normalized.endsWith(".jar", true)
                    ) consume(normalized, bytes)
                }
                zip.closeEntry()
            }
        }
    }

    private fun readEntry(zip: ZipInputStream, remainingBytes: Long): ByteArray {
        require(remainingBytes >= 0) { "Project archive is larger than 50 MB" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= remainingBytes) { "Project archive is larger than 50 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeWorkspace(workspace: ProjectWorkspace) {
        val directory = projectDir(workspace.id)
        directory.mkdirs()
        val desired = workspace.files.map { safeJavaPath(it.path) }.toSet()
        javaFiles(directory).filter { relativePath(directory, it) !in desired }.forEach { it.delete() }
        workspace.files.forEach { file ->
            val target = File(directory, safeJavaPath(file.path))
            target.parentFile?.mkdirs()
            target.writeText(file.source, Charsets.UTF_8)
        }
        val metadata = File(directory, META_FILE)
        val temp = File(directory, "$META_FILE.tmp")
        temp.writeText(serialize(workspace), Charsets.UTF_8)
        if (!temp.renameTo(metadata)) {
            metadata.delete()
            check(temp.renameTo(metadata)) { "Could not save project metadata" }
        }
    }

    private fun readWorkspace(directory: File): ProjectWorkspace {
        val metadata = JSONObject(File(directory, META_FILE).readText())
        val files = javaFiles(directory).map { file -> ProjectFile(relativePath(directory, file), file.readText(Charsets.UTF_8)) }
            .sortedBy { it.path }
        require(files.isNotEmpty()) { "Project contains no Java source files" }
        val paths = files.map { it.path }
        val active = metadata.optString("activePath").takeIf { it in paths } ?: paths.first()
        val cursors = mutableMapOf<String, Pair<Int, Int>>()
        val cursorJson = metadata.optJSONObject("cursors") ?: JSONObject()
        cursorJson.keys().forEach { key -> cursorJson.optJSONArray(key)?.let { cursors[key] = it.optInt(0) to it.optInt(1) } }
        return ProjectWorkspace(
            id = metadata.optString("id", directory.name),
            name = metadata.optString("name", directory.name),
            files = files,
            activePath = active,
            entryPoint = metadata.optString("entryPoint", "Main"),
            cursorPositions = cursors,
            libraries = libraryFiles(directory).map { it.name }.sortedBy { it.lowercase() },
        )
    }

    private fun serialize(workspace: ProjectWorkspace): String = JSONObject().apply {
        put("id", workspace.id)
        put("name", workspace.name)
        put("activePath", workspace.activePath)
        put("entryPoint", workspace.entryPoint)
        put("cursors", JSONObject().apply {
            workspace.cursorPositions.forEach { (path, cursor) -> put(path, JSONArray().put(cursor.first).put(cursor.second)) }
        })
    }.toString(2)

    private fun writeLibrary(id: String, name: String, bytes: ByteArray) {
        val directory = File(projectDir(id), "lib").apply { mkdirs() }
        val safeName = safeLibraryName(name)
        val target = File(directory, safeName)
        val temp = File(directory, "$safeName.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target)) { "Could not save project library" }
    }

    private fun libraryFiles(directory: File): List<File> = File(directory, "lib").walkTopDown()
        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        .toList()

    private fun safeLibraryName(name: String): String {
        val normalized = name.replace('\\', '/').substringAfterLast('/').trim()
        require(normalized.isNotBlank() && normalized.endsWith(".jar", ignoreCase = true)) { "Library files must use the .jar extension" }
        require(normalized != "." && normalized != "..") { "Invalid library filename" }
        return normalized.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun javaFiles(directory: File): List<File> = directory.walkTopDown()
        .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
        .toList()

    private fun relativePath(directory: File, file: File): String = file.relativeTo(directory).invariantSeparatorsPath

    private fun projectDir(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid project id" }
        return File(root, id)
    }

    private fun safeJavaPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Enter a relative Java filename" }
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Invalid project file path" }
        require(parts.last().endsWith(".java", ignoreCase = true)) { "Java files must use the .java extension" }
        return parts.joinToString("/")
    }

    private companion object {
        const val META_FILE = "javapad-project.json"
        const val MAX_IMPORT_BYTES = 50L * 1024L * 1024L
        const val MAX_LIBRARY_BYTES = 40L * 1024L * 1024L
    }
}
