package dev.javapad.completion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.jar.JarInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazily indexes android.jar off-thread and caches class names in app-private storage. */
@Singleton
class AndroidClassIndex @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var names: List<String>? = null

    suspend fun find(prefix: String, limit: Int = 40): List<CompletionSuggestion> = withContext(Dispatchers.IO) {
        val allNames = names ?: load().also { names = it }
        allNames.asSequence()
            .filter { it.substringAfterLast('.').startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.substringAfterLast('.') }
            .take(limit)
            .map { qualified ->
                val simple = qualified.substringAfterLast('.')
                CompletionSuggestion(
                    label = simple,
                    kind = CompletionKind.CLASS,
                    type = qualified,
                    signature = qualified,
                    insertion = simple,
                    importName = qualified.takeIf { !it.startsWith("java.lang.") },
                )
            }
            .toList()
    }

    private fun load(): List<String> {
        val cache = java.io.File(context.filesDir, "completion/android-classes.txt")
        if (cache.isFile && cache.length() > 0L) {
            return runCatching { cache.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() } ?: scanAndCache(cache)
        }
        return scanAndCache(cache)
    }

    private fun scanAndCache(cache: java.io.File): List<String> {
        val result = mutableListOf<String>()
        context.assets.open("android.jar").use { raw ->
            JarInputStream(raw).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".class") &&
                        !name.startsWith("META-INF/") && !name.endsWith("module-info.class") &&
                        !name.endsWith("package-info.class") && !name.contains('$')
                    ) {
                        result += name.removeSuffix(".class").replace('/', '.')
                    }
                }
            }
        }
        cache.parentFile?.mkdirs()
        cache.writeText(result.joinToString("\n"))
        return result
    }
}
