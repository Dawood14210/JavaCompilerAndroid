package dev.javapad.completion

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import java.lang.reflect.Modifier
import javax.inject.Inject

/** Simple, bounded completion engine. Parsing and reflection are always called off the UI thread. */
data class CompletionSuggestion(
    val label: String,
    val kind: CompletionKind,
    val type: String,
    val signature: String,
    val insertion: String = label,
    val importName: String? = null,
    val cursorOffset: Int = insertion.length,
    val tabStops: List<Int> = emptyList(),
)

enum class CompletionKind { LOCAL, PARAMETER, FIELD, METHOD, CLASS, MEMBER, KEYWORD, SNIPPET }

class CompletionEngine @Inject constructor() {
    fun suggest(source: String, cursor: Int, projectSources: Collection<String> = emptyList()): List<CompletionSuggestion> {
        val safeCursor = cursor.coerceIn(0, source.length)
        val beforeCursor = source.substring(0, safeCursor)
        val memberReceiver = Regex("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.([A-Za-z_$][\\w$]*)?$").find(beforeCursor)
        val prefix = if (memberReceiver != null) memberReceiver.groupValues[2] else
            Regex("[A-Za-z_$][\\w$]*$").find(beforeCursor)?.value.orEmpty()
        val eraseStart = if (memberReceiver != null) memberReceiver.range.first else safeCursor - prefix.length
        val parseableSource = source.replaceRange(eraseStart.coerceAtLeast(0), safeCursor, " ".repeat((safeCursor - eraseStart).coerceAtLeast(0)))
        val parsed = runCatching { StaticJavaParser.parse(parseableSource) }.getOrNull()
        val projectUnits = projectSources.mapNotNull { text -> runCatching { StaticJavaParser.parse(text) }.getOrNull() }
        val projectTypes = projectUnits.flatMap { unit ->
            unit.findAll(TypeDeclaration::class.java).map { declaration ->
                val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
                ProjectType(declaration.nameAsString, listOf(packageName, declaration.nameAsString).filter { it.isNotEmpty() }.joinToString("."), declaration)
            }
        }
        val candidates = mutableListOf<CompletionSuggestion>()

        if (memberReceiver != null) {
            val receiver = memberReceiver.groupValues[1]
            val typeName = resolveExpressionType(receiver, parsed)
            reflectedMembers(typeName, parsed, projectTypes).forEach(candidates::add)
        } else if (parsed != null) {
            parsed.findAll(VariableDeclarator::class.java).forEach { variable ->
                candidates += CompletionSuggestion(
                    variable.nameAsString, CompletionKind.LOCAL, variable.type.asString(), "${variable.type.asString()} ${variable.nameAsString}",
                )
            }
            parsed.findAll(MethodDeclaration::class.java).forEach { method ->
                method.parameters.forEach { parameter ->
                    candidates += CompletionSuggestion(
                        parameter.nameAsString, CompletionKind.PARAMETER, parameter.type.asString(), "${parameter.type.asString()} ${parameter.nameAsString}",
                    )
                }
                candidates += CompletionSuggestion(
                    method.nameAsString, CompletionKind.METHOD, method.typeAsString,
                    method.declarationAsString(false, false, true),
                )
            }
            parsed.findAll(FieldDeclaration::class.java).forEach { field ->
                field.variables.forEach { variable ->
                    candidates += CompletionSuggestion(
                        variable.nameAsString, CompletionKind.FIELD, variable.type.asString(), "${variable.type.asString()} ${variable.nameAsString}",
                    )
                }
            }
            parsed.findAll(TypeDeclaration::class.java).forEach { type ->
                candidates += CompletionSuggestion(type.nameAsString, CompletionKind.CLASS, "class", "${type.nameAsString} (this file)")
            }
            val imported = parsed.imports.mapNotNull { declaration ->
                val qualified = declaration.nameAsString
                if (declaration.isAsterisk) null else qualified.substringAfterLast('.').let { simple ->
                    CompletionSuggestion(simple, CompletionKind.CLASS, qualified, "import $qualified", importName = qualified)
                }
            }
            candidates += imported
        }

        if (memberReceiver == null) {
            val currentPackage = parsed?.packageDeclaration?.map { it.nameAsString }?.orElse("").orEmpty()
            projectTypes.forEach { projectType ->
                val targetPackage = projectType.qualifiedName.substringBeforeLast('.', "")
                candidates += CompletionSuggestion(
                    label = projectType.name,
                    kind = CompletionKind.CLASS,
                    type = "class",
                    signature = "${projectType.qualifiedName} (project source)",
                    importName = projectType.qualifiedName.takeIf { targetPackage.isNotEmpty() && targetPackage != currentPackage },
                )
            }
            candidates += keywords
            candidates += snippets
        }
        val distinct = candidates.distinctBy { "${it.kind}:${it.label}:${it.signature}" }
        return distinct.asSequence()
            .filter { prefix.isEmpty() || it.label.startsWith(prefix, ignoreCase = true) }
            .sortedWith(compareBy<CompletionSuggestion>({ priority(it.kind) }, { it.label.length }, { it.label.lowercase() }))
            .take(32)
            .toList()
    }

    private data class ProjectType(
        val name: String,
        val qualifiedName: String,
        val declaration: TypeDeclaration<*>,
    )

    private fun resolveExpressionType(expression: String, unit: com.github.javaparser.ast.CompilationUnit?): String {
        val parts = expression.split('.')
        val simple = parts.first()
        var typeName = unit?.findAll(VariableDeclarator::class.java)
            ?.lastOrNull { it.nameAsString == simple }?.type?.asString()
            ?: unit?.findAll(MethodDeclaration::class.java)?.flatMap { it.parameters }
                ?.lastOrNull { it.nameAsString == simple }?.type?.asString()
            ?: simple
        val imports = unit?.imports.orEmpty().associate { it.nameAsString.substringAfterLast('.') to it.nameAsString }
        typeName = imports[typeName] ?: if (typeName in javaLangTypes) "java.lang.$typeName" else typeName
        parts.drop(1).forEach { fieldName ->
            val reflectedType = runCatching { Class.forName(typeName, false, javaClass.classLoader) }.getOrNull()
            typeName = reflectedType?.fields?.firstOrNull { it.name == fieldName }?.type?.name ?: return@forEach
        }
        return typeName
    }

    private fun reflectedMembers(
        typeName: String,
        unit: com.github.javaparser.ast.CompilationUnit?,
        projectTypes: List<ProjectType>,
    ): List<CompletionSuggestion> {
        val cleaned = typeName.removeSuffix("[]").substringBefore('<')
        projectTypes.firstOrNull { it.name == cleaned || it.qualifiedName == cleaned }?.let { projectType ->
            val members = mutableListOf<CompletionSuggestion>()
            projectType.declaration.findAll(MethodDeclaration::class.java).forEach { method ->
                members += CompletionSuggestion(
                    method.nameAsString,
                    CompletionKind.MEMBER,
                    method.typeAsString,
                    method.declarationAsString(false, false, true),
                )
            }
            projectType.declaration.findAll(FieldDeclaration::class.java).forEach { field ->
                field.variables.forEach { variable ->
                    members += CompletionSuggestion(
                        variable.nameAsString,
                        CompletionKind.MEMBER,
                        variable.type.asString(),
                        "${variable.type.asString()} ${variable.nameAsString}",
                    )
                }
            }
            return members
        }
        val imports = unit?.imports.orEmpty().associate { it.nameAsString.substringAfterLast('.') to it.nameAsString }
        val qualified = when {
            cleaned.contains('.') -> cleaned
            imports.containsKey(cleaned) -> imports.getValue(cleaned)
            cleaned in javaLangTypes -> "java.lang.$cleaned"
            else -> cleaned
        }
        val type = runCatching { Class.forName(qualified, false, javaClass.classLoader) }
            .getOrNull() ?: return emptyList()
        val result = mutableListOf<CompletionSuggestion>()
        type.methods.asSequence()
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .distinctBy { it.name + it.parameterTypes.contentToString() }
            .forEach { method ->
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                result += CompletionSuggestion(
                    method.name, CompletionKind.MEMBER, method.returnType.simpleName,
                    "${method.returnType.simpleName} ${method.name}($params)",
                )
            }
        type.fields.asSequence().filter { Modifier.isPublic(it.modifiers) }.forEach { field ->
            result += CompletionSuggestion(field.name, CompletionKind.MEMBER, field.type.simpleName, "${field.type.simpleName} ${field.name}")
        }
        return result
    }

    private fun priority(kind: CompletionKind): Int = when (kind) {
        CompletionKind.LOCAL, CompletionKind.PARAMETER -> 0
        CompletionKind.FIELD, CompletionKind.METHOD -> 1
        CompletionKind.MEMBER -> 0
        CompletionKind.CLASS -> 2
        CompletionKind.KEYWORD -> 3
        CompletionKind.SNIPPET -> 4
    }

    private val javaLangTypes = setOf("String", "Object", "System", "Math", "Integer", "Long", "Boolean", "Double", "Throwable", "Exception", "Thread")

    private val keywords = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "new", "package", "private",
        "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws",
        "try", "void", "volatile", "while", "var", "record",
    ).map { CompletionSuggestion(it, CompletionKind.KEYWORD, "keyword", "Java keyword") }

    private val snippets = listOf(
        CompletionSuggestion("sout", CompletionKind.SNIPPET, "statement", "System.out.println(…)", "System.out.println();", cursorOffset = 19),
        CompletionSuggestion("psvm", CompletionKind.SNIPPET, "method", "public static void main(String[] args)", "public static void main(String[] args) {\n    \n}", cursorOffset = 45, tabStops = listOf(45)),
        CompletionSuggestion("fori", CompletionKind.SNIPPET, "loop", "for (int i = 0; …)", "for (int i = 0; i < ; i++) {\n    \n}", cursorOffset = 20),
        CompletionSuggestion("foreach", CompletionKind.SNIPPET, "loop", "for-each loop", "for (var item : collection) {\n    \n}", cursorOffset = 9, tabStops = listOf(9, 16, 34)),
        CompletionSuggestion("iter", CompletionKind.SNIPPET, "loop", "enhanced for loop", "for (var item : collection) {\n    \n}", cursorOffset = 9, tabStops = listOf(9, 16, 34)),
        CompletionSuggestion("ifelse", CompletionKind.SNIPPET, "conditional", "if / else", "if (condition) {\n    \n} else {\n    \n}", cursorOffset = 4, tabStops = listOf(4, 21, 35)),
        CompletionSuggestion("trycatch", CompletionKind.SNIPPET, "statement", "try / catch", "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", cursorOffset = 7),
        CompletionSuggestion("switch", CompletionKind.SNIPPET, "statement", "switch statement", "switch (value) {\n    case  -> { }\n    default -> { }\n}", cursorOffset = 8),
        CompletionSuggestion("class", CompletionKind.SNIPPET, "type", "class declaration", "class Name {\n    \n}", cursorOffset = 6, tabStops = listOf(6, 17)),
        CompletionSuggestion("interface", CompletionKind.SNIPPET, "type", "interface declaration", "interface Name {\n    \n}", cursorOffset = 10, tabStops = listOf(10, 21)),
        CompletionSuggestion("enum", CompletionKind.SNIPPET, "type", "enum declaration", "enum Name {\n    \n}", cursorOffset = 5, tabStops = listOf(5, 16)),
        CompletionSuggestion("record", CompletionKind.SNIPPET, "type", "record declaration", "record Name() {\n    \n}", cursorOffset = 7, tabStops = listOf(7, 20)),
        CompletionSuggestion("lambda", CompletionKind.SNIPPET, "expression", "lambda expression", "() -> {\n    \n}", cursorOffset = 8),
    )
}
