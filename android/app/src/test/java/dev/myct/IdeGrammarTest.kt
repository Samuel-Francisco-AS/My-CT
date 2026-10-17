package dev.myct

import org.eclipse.tm4e.core.registry.*
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class IdeGrammarTest {
    @Test fun bundledGrammarsReallyTokenizeCommonLanguagesAndConfigurationsLoad() {
        val root = File("src/main/assets")
        val definitions = JSONObject(File(root, "ide/languages.json").readText()).getJSONArray("languages")
        val sources = mutableMapOf<String, IGrammarSource>()
        for (index in 0 until definitions.length()) {
            val definition = definitions.getJSONObject(index)
            val file = File(root, definition.getString("grammar"))
            sources[definition.getString("scopeName")] = IGrammarSource.fromFile(file)
            File(root, definition.getString("languageConfiguration")).reader().use { assertNotNull(LanguageConfiguration.load(it)) }
        }
        val registry = Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String) = sources[scopeName]
        })
        val samples = mapOf("source.c" to "int main(void) { return 0; }", "source.c++" to "const auto value = 42;",
            "source.rust" to "fn main() { let value = 42; }", "source.python" to "def main(): return 42",
            "source.kotlin" to "fun main() { val value = 42 }", "source.shell" to "if true; then echo 'hello'; fi",
            "source.js" to "const value = 42; function main() { return value; }")
        for ((scope, sample) in samples) {
            val grammar = registry.loadGrammar(scope) ?: error(scope)
            val tokens = grammar.tokenizeLine(sample).tokens
            assertTrue("$scope must distinguish syntax", tokens.size > 2)
            assertTrue("$scope must have scoped tokens", tokens.any { it.scopes.size > 1 })
        }
        registry.setTheme(IThemeSource.fromFile(File(root, "ide/theme.json")))
    }
}
