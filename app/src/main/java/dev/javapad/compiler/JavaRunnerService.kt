package dev.javapad.compiler

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.json.JSONArray
import org.json.JSONObject
import dalvik.system.DexClassLoader

/** Runs untrusted user main methods in a separate Android process that can be killed on Stop/timeout. */
class JavaRunnerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private var client: Messenger? = null
    private var stdin: QueuedStdin? = null
    private lateinit var messenger: Messenger

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                RunnerProtocol.RUN -> {
                    client = message.replyTo
                    val sources = parseSources(message.data.getString(RunnerProtocol.KEY_SOURCES).orEmpty())
                    val entryPoint = message.data.getString(RunnerProtocol.KEY_ENTRY_POINT).orEmpty().ifBlank { "Main" }
                    val projectId = message.data.getString(RunnerProtocol.KEY_PROJECT_ID).orEmpty()
                    val timeoutSeconds = message.data.getInt(RunnerProtocol.KEY_TIMEOUT, 30).coerceIn(5, 300)
                    beginRun(sources, entryPoint, projectId, timeoutSeconds)
                }
                RunnerProtocol.STDIN -> {
                    val line = message.data.getString(RunnerProtocol.KEY_TEXT).orEmpty()
                    stdin?.offerLine(line)
                }
                RunnerProtocol.STOP -> {
                    send(RunnerProtocol.STOPPED, "Program stopped by user")
                    // This process is intentionally disposable: killing it is the only reliable
                    // way to stop arbitrary Java code stuck in a tight loop.
                    postDelayed({ Process.killProcess(Process.myPid()) }, 120)
                }
                else -> super.handleMessage(message)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginRun(sources: Map<String, String>, entryPoint: String, projectId: String, timeoutSeconds: Int) {
        if (activeJob?.isActive == true) {
            sendOutput("A program is already running. Stop it before starting another.\n", stderr = true)
            return
        }
        stdin = QueuedStdin()
        activeJob = serviceScope.launch {
            var watchdog: Job? = null
            var previousOut: PrintStream? = null
            var previousErr: PrintStream? = null
            var previousIn: InputStream? = null
            try {
                sendOutput("Compiling Java source with ECJ…\n", stderr = false)
                val runsRoot = File(cacheDir, "javapad-runs")
                runsRoot.listFiles()?.forEach { it.deleteRecursively() }
                val workDir = File(runsRoot, System.nanoTime().toString()).apply { mkdirs() }
                val androidJar = obtainAndroidJar()
                val classpathFiles = obtainAppClasspath()
                val projectLibraries = obtainProjectLibraries(projectId)
                require(sources.isNotEmpty()) { "Project has no Java source files" }
                val sourceRoot = File(workDir, "src").apply { mkdirs() }
                val sourceFiles = sources.map { (requestedPath, source) ->
                    val relativePath = safeSourcePath(requestedPath)
                    File(sourceRoot, relativePath).apply {
                        parentFile?.mkdirs()
                        writeText(source, Charsets.UTF_8)
                    }
                }
                val classesDir = File(workDir, "classes").apply { mkdirs() }
                val compilerResult = withContext(Dispatchers.Default) {
                    compile(sourceFiles, sourceRoot, classesDir, androidJar, classpathFiles + projectLibraries)
                }
                sendProblems(compilerResult.problems)
                if (!compilerResult.success) {
                    sendOutput("Compilation failed. See Problems for details.\n", stderr = true)
                    send(RunnerProtocol.FINISHED, "")
                    return@launch
                }

                sendOutput("Converting bytecode to DEX…\n", stderr = false)
                val dexDir = File(workDir, "dex").apply { mkdirs() }
                withContext(Dispatchers.Default) {
                    D8.run(
                        D8Command.builder()
                            .addProgramFiles(classesDir.toPath(), *projectLibraries.map { it.toPath() }.toTypedArray())
                            .addLibraryFiles(androidJar.toPath(), *classpathFiles.map { it.toPath() }.toTypedArray())
                            .setMinApiLevel(26)
                            .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                            .build(),
                    )
                }

                val optimizedDir = File(codeCacheDir, "javapad-dex").apply { mkdirs() }
                val classLoader = DexClassLoader(
                    dexDir.absolutePath,
                    optimizedDir.absolutePath,
                    null,
                    javaClass.classLoader,
                )
                val entryClass = classLoader.loadClass(entryPoint.trim().removeSuffix(".java"))
                val main = entryClass.getMethod("main", Array<String>::class.java)
                val args = emptyArray<String>()

                val input = stdin ?: QueuedStdin().also { stdin = it }
                val outStream = ForwardingOutputStream { bytes -> sendOutput(bytes, stderr = false) }
                val errStream = ForwardingOutputStream { bytes -> sendOutput(bytes, stderr = true) }
                previousOut = System.out
                previousErr = System.err
                previousIn = System.`in`
                System.setOut(PrintStream(outStream, true, "UTF-8"))
                System.setErr(PrintStream(errStream, true, "UTF-8"))
                System.setIn(input)

                val startNanos = System.nanoTime()
                val startMemory = usedMemory()
                watchdog = serviceScope.launch {
                    delay(timeoutSeconds * 1_000L)
                    send(RunnerProtocol.TIMED_OUT, "Program exceeded the ${timeoutSeconds}s timeout; runner process was terminated.")
                    // Allow the IPC message to reach the UI process before forcibly ending this one.
                    delay(180)
                    Process.killProcess(Process.myPid())
                }
                try {
                    main.invoke(null, args as Any)
                } catch (wrapped: InvocationTargetException) {
                    printThrowable(wrapped.targetException)
                } finally {
                    watchdog.cancel()
                }
                outStream.flush()
                errStream.flush()
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                val memoryDelta = (usedMemory() - startMemory).coerceAtLeast(0L) / (1024L * 1024L)
                send(RunnerProtocol.FINISHED, "Finished in ${elapsedMs} ms · ${memoryDelta} MB memory delta")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Compile/run pipeline failed", error)
                printThrowable(error)
                send(RunnerProtocol.FINISHED, "Run failed internally")
            } finally {
                watchdog?.cancel()
                previousOut?.let { System.setOut(it) }
                previousErr?.let { System.setErr(it) }
                previousIn?.let { System.setIn(it) }
                stdin = null
            }
        }
    }

    private fun compile(
        sourceFiles: List<File>,
        sourceRoot: File,
        outputDir: File,
        androidJar: File,
        classpathFiles: List<File>,
    ): CompileResult {
        val out = StringWriter()
        val err = StringWriter()
        val command = buildString {
            append("-source 17 -target 17 -encoding UTF-8 -proc:none ")
            append("-bootclasspath ").append(quote(androidJar.absolutePath)).append(' ')
            val classpath = (listOf(androidJar) + classpathFiles).joinToString(File.pathSeparator) { it.absolutePath }
            append("-classpath ").append(quote(classpath)).append(' ')
            append("-d ").append(quote(outputDir.absolutePath)).append(' ')
            sourceFiles.forEach { append(quote(it.absolutePath)).append(' ') }
        }
        val outWriter = PrintWriter(out)
        val errWriter = PrintWriter(err)
        val success = BatchCompiler.compile(command, outWriter, errWriter, null)
        outWriter.flush()
        errWriter.flush()
        val compilerOutput = listOf(out.toString(), err.toString()).filter { it.isNotBlank() }.joinToString("\n")
        val problems = parseProblems(compilerOutput, sourceRoot)
        return CompileResult(success, problems.ifEmpty {
            if (success) emptyList() else listOf(CompileProblem(sourceFiles.firstOrNull()?.name ?: "Main.java", 1, 1, compilerOutput.ifBlank { "ECJ compilation failed" }))
        })
    }

    private fun parseProblems(text: String, sourceRoot: File): List<CompileProblem> {
        val header = Pattern.compile("(?m)^\\s*\\d+\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\s+\\(at line (\\d+)\\)\\s*$")
        val matcher = header.matcher(text)
        val matches = mutableListOf<Triple<Int, Int, MatchData>>()
        while (matcher.find()) {
            matches += Triple(
                matcher.start(),
                matcher.end(),
                MatchData(
                    matcher.group(1) == "WARNING",
                    runCatching { File(matcher.group(2).trim()).relativeTo(sourceRoot).invariantSeparatorsPath }
                        .getOrElse { File(matcher.group(2).trim()).name },
                    matcher.group(3).toIntOrNull() ?: 1,
                ),
            )
        }
        return matches.mapIndexed { index, (_, end, data) ->
            val next = matches.getOrNull(index + 1)?.first ?: text.length
            val body = text.substring(end, next)
            val lines = body.lines()
            val caretIndex = lines.indexOfFirst { it.contains('^') }
            val caretLine = lines.getOrNull(caretIndex)
            val sourceIndent = lines.getOrNull(caretIndex - 1)?.takeWhile { it == ' ' }?.length ?: 0
            val caretIndent = caretLine?.takeWhile { it == ' ' }?.length ?: sourceIndent
            val column = (caretIndent - sourceIndent + 1).coerceAtLeast(1)
            val length = caretLine?.count { it == '^' }?.coerceAtLeast(1) ?: 1
            val message = if (caretIndex >= 0) {
                lines.drop(caretIndex + 1).firstOrNull {
                    it.isNotBlank() && !it.trim().matches(Regex("[-~^]+")) &&
                        !it.trim().matches(Regex("\\d+\\s+problems?\\s+\\(.*"))
                }?.trim()
            } else null
            val diagnosticMessage = message ?: "Java compilation diagnostic"
            CompileProblem(data.file, data.line, column, diagnosticMessage, data.warning, length)
        }
    }

    private fun obtainAndroidJar(): File {
        val jar = File(cacheDir, "javapad/android.jar")
        if (jar.isFile && jar.length() > 1_000_000L) return jar
        jar.parentFile?.mkdirs()
        assets.open("android.jar").use { input -> jar.outputStream().use { output -> input.copyTo(output) } }
        return jar
    }

    private fun obtainAppClasspath(): List<File> {
        val classpathDir = File(cacheDir, "javapad/classpath")
        if (!classpathDir.isDirectory || classpathDir.listFiles().isNullOrEmpty()) {
            classpathDir.mkdirs()
            val names = assets.list("java-classpath").orEmpty()
            names.forEach { name ->
                assets.open("java-classpath/$name").use { input ->
                    File(classpathDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return classpathDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
    }

    private fun obtainProjectLibraries(projectId: String): List<File> {
        if (projectId.isBlank()) return emptyList()
        require(projectId.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid project id" }
        return File(File(File(filesDir, "projects"), projectId), "lib").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
    }

    private fun parseSources(json: String): Map<String, String> = runCatching {
        val objectJson = JSONObject(json)
        buildMap {
            val keys = objectJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val path = safeSourcePath(key)
                put(path, objectJson.getString(key))
            }
        }
    }.getOrElse { emptyMap() }

    private fun safeSourcePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Invalid source path" }
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." } && parts.lastOrNull()?.endsWith(".java", true) == true) {
            "Invalid Java source path"
        }
        return parts.joinToString("/")
    }

    private fun printThrowable(error: Throwable) {
        val unwrapped = if (error is InvocationTargetException) error.targetException ?: error else error
        val writer = StringWriter()
        unwrapped.printStackTrace(PrintWriter(writer))
        sendOutput(writer.toString(), stderr = true)
    }

    private fun sendProblems(problems: List<CompileProblem>) {
        val json = JSONArray()
        problems.forEach { item ->
            json.put(JSONObject().apply {
                put("file", item.file)
                put("line", item.line)
                put("column", item.column)
                put("message", item.message)
                put("warning", item.isWarning)
                put("length", item.length)
            })
        }
        val message = Message.obtain(null, RunnerProtocol.PROBLEMS)
        message.data = Bundle().apply { putString(RunnerProtocol.KEY_PROBLEMS, json.toString()) }
        sendMessage(message)
    }

    private fun sendOutput(text: String, stderr: Boolean) {
        val message = Message.obtain(null, RunnerProtocol.OUTPUT)
        message.data = Bundle().apply {
            putString(RunnerProtocol.KEY_TEXT, text)
            putBoolean(RunnerProtocol.KEY_STDERR, stderr)
        }
        sendMessage(message)
    }

    private fun send(what: Int, summary: String) {
        val message = Message.obtain(null, what)
        message.data = Bundle().apply { putString(RunnerProtocol.KEY_SUMMARY, summary) }
        sendMessage(message)
    }

    private fun sendMessage(message: Message) {
        try {
            client?.send(message)
        } catch (error: Exception) {
            Log.w(TAG, "UI process is no longer connected", error)
        }
    }

    private fun usedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun quote(path: String): String = "\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private data class MatchData(val warning: Boolean, val file: String, val line: Int)
    private data class CompileResult(val success: Boolean, val problems: List<CompileProblem>)

    private class ForwardingOutputStream(private val forward: (String) -> Unit) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        @Synchronized
        override fun write(value: Int) {
            buffer.write(value)
            if (value == '\n'.code || buffer.size() >= 512) drain()
        }
        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) write(bytes[i].toInt() and 0xff)
        }
        @Synchronized
        override fun flush() = drain()
        private fun drain() {
            if (buffer.size() == 0) return
            val text = buffer.toString(StandardCharsets.UTF_8.name())
            buffer.reset()
            forward(text)
        }
    }

    private class QueuedStdin : InputStream() {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false

        fun offerLine(line: String) {
            if (closed) return
            (line + "\n").toByteArray(StandardCharsets.UTF_8).forEach { queue.offer(it.toInt() and 0xff) }
        }

        override fun read(): Int {
            if (closed && queue.isEmpty()) return -1
            return try { queue.take() } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            bytes[offset] = first.toByte()
            var count = 1
            while (count < length) {
                val next = queue.poll() ?: break
                bytes[offset + count] = next.toByte()
                count++
            }
            return count
        }

        override fun close() {
            closed = true
            queue.offer(-1)
        }
    }

    companion object {
        private const val TAG = "JavaPadRunner"
    }
}
