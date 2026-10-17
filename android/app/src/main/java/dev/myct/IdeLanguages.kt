package dev.myct

import android.content.res.AssetManager
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

internal object IdeLanguages {
    @Volatile private var loaded = false
    @Synchronized fun load(assets: AssetManager) {
        if (loaded) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        val themes = ThemeRegistry.getInstance()
        assets.open("ide/theme.json").use { input ->
            themes.loadTheme(ThemeModel(IThemeSource.fromInputStream(input, "theme.json", null), "myct").apply { isDark = true })
        }
        themes.setTheme("myct")
        GrammarRegistry.getInstance().loadGrammars("ide/languages.json")
        loaded = true
    }
}
internal fun codeScope(path: String): String? = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
    "c" -> "source.c"
    "cc", "cpp", "cxx", "h", "hpp", "hxx" -> "source.c++"
    "rs" -> "source.rust"
    "py", "pyw" -> "source.python"
    "kt", "kts" -> "source.kotlin"
    "sh", "bash", "zsh" -> "source.shell"
    "js", "mjs", "cjs", "jsx" -> "source.js"
    else -> if (path.substringAfterLast('/') in setOf(".bashrc", ".bash_profile", ".profile", ".zshrc")) "source.shell" else null
}
