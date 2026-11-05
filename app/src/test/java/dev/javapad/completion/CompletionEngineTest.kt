package dev.javapad.completion

import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionEngineTest {
    private val engine = CompletionEngine()

    @Test
    fun findsLocalsAndParametersFromParsedSource() {
        val source = "class Main { void greet(String name) { String message = \"hi\"; na } }"
        val cursor = source.indexOf("na }") + 2
        val labels = engine.suggest(source, cursor).map { it.label }
        assertTrue("parameter name should be suggested", "name" in labels)
        assertTrue("local variable should be suggested", "message" in labels)
    }

    @Test
    fun offersSnippetWhenAbbreviationIsTyped() {
        val source = "class Main { void greet() { sout } }"
        val cursor = source.indexOf("sout") + 4
        val suggestion = engine.suggest(source, cursor).firstOrNull { it.label == "sout" }
        assertTrue(suggestion?.kind == CompletionKind.SNIPPET)
        assertTrue(suggestion?.insertion?.contains("System.out.println") == true)
    }

    @Test
    fun resolvesStringMembersAfterDot() {
        val source = "class Main { void greet(String name) { name.sub } }"
        val cursor = source.indexOf("name.sub") + "name.sub".length
        val labels = engine.suggest(source, cursor).map { it.label }
        assertTrue("substring" in labels)
    }

    @Test
    fun suggestsTypesDeclaredInOtherProjectFiles() {
        val source = "class Main { void create() { Hel } }"
        val cursor = source.indexOf("Hel }") + 3
        val suggestions = engine.suggest(source, cursor, listOf("package demo; class Helper {}"))
        val helper = suggestions.firstOrNull { it.label == "Helper" }
        assertTrue("project type should be suggested", helper != null)
        assertTrue("cross-package project types should add an import", helper?.importName == "demo.Helper")
    }

    @Test
    fun suggestsMembersOfProjectTypes() {
        val source = "class Main { void create() { Helper helper = new Helper(); helper.na } }"
        val cursor = source.indexOf("helper.na") + "helper.na".length
        val projectSource = "class Helper { public String name; public void navigate() {} }"
        val labels = engine.suggest(source, cursor, listOf(projectSource)).map { it.label }
        assertTrue("field from project type should be suggested", "name" in labels)
        assertTrue("method from project type should be suggested", "navigate" in labels)
    }
}
