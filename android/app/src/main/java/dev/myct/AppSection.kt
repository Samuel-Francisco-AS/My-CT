package dev.myct

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

internal enum class AppSection(val label: String) {
    Terminal("Terminal"), Files("Arquivos"), Health("Saúde"), Vault("Cofre"), Downloads("Downloads")
}

/** Opening navigation or options must never override the vault's unconditional protection. */
internal fun captureProtectionRequired(globalPreference: Boolean, section: AppSection): Boolean =
    globalPreference || section == AppSection.Vault

/** Keep Compose scroll/subsection state; SFTP retains its accepted named-anchor restoration. */
@Composable
internal fun AppSectionContent(section: AppSection, content: @Composable () -> Unit) {
    val savedScreens = rememberSaveableStateHolder()
    if (section == AppSection.Files) content()
    else savedScreens.SaveableStateProvider(section.name, content)
}
