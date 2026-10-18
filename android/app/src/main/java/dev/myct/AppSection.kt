package dev.myct

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

internal enum class AppSection(val label: String) {
    Home("Início"), Terminal("Terminal"), Files("Arquivos"), Ide("IDE"), Health("Saúde"), Vault("Cofre"), Downloads("Downloads")
}

/** Opening navigation or options must never override the vault's unconditional protection. */
internal fun captureProtectionRequired(
    globalPreference: Boolean, section: AppSection, transitionProtected: Boolean = false
): Boolean = globalPreference || section == AppSection.Vault || transitionProtected

/** Latest request wins; only displayed owns a composed screen. No sessions belong here. */
internal class SectionNavigation(initial: AppSection) {
    var requested by mutableStateOf(initial)
        private set
    var displayed by mutableStateOf(initial)
        private set
    // Latched through interruptions and the whole outgoing/incoming animation.
    var protectCapture by mutableStateOf(initial == AppSection.Vault)
        private set

    fun request(destination: AppSection): Boolean {
        if (destination == requested) return false
        if (destination == AppSection.Vault) protectCapture = true
        requested = destination
        return true
    }

    fun showRequested() { displayed = requested }

    fun settle() {
        if (displayed == requested) protectCapture = displayed == AppSection.Vault
    }

    companion object {
        // Restore the latest intent fully visible, rather than a half-faded outgoing screen.
        val Saver = Saver<SectionNavigation, String>(
            save = { it.requested.name }, restore = { SectionNavigation(AppSection.valueOf(it)) })
    }
}

/** Called by one LaunchedEffect. Cancellation retains opacity; it never commits a stale target. */
internal suspend fun animateSectionChange(
    navigation: SectionNavigation,
    opacity: Animatable<Float, AnimationVector1D>,
    ready: () -> Boolean
) {
    if (!ready()) {
        // Opening/reopening the menu cancels a swap, restoring its current background.
        opacity.animateTo(1f, tween(80, easing = LinearEasing))
        return
    }
    if (navigation.displayed != navigation.requested) {
        opacity.animateTo(0f, tween(80, easing = LinearEasing))
        if (!ready()) return
        navigation.showRequested()
    }
    // Compose applies the single destination at zero opacity, then fades it in.
    // Animatable uses the composition's MotionDurationScale, including scale zero.
    opacity.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
    navigation.settle()
}

@Composable
internal fun AnimatedAppSection(
    navigation: SectionNavigation,
    ready: Boolean,
    content: @Composable () -> Unit
) {
    val opacity = remember { Animatable(1f) }
    val latestReady by rememberUpdatedState(ready)
    LaunchedEffect(navigation.requested, ready) {
        animateSectionChange(navigation, opacity) { latestReady }
    }
    // A persistent host/SaveableStateHolder, with no AnimatedContent/Crossfade overlap.
    // Layer-only alpha avoids recomposing/re-measuring TerminalView/Sora on each frame.
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
        AppSectionContent(navigation.displayed, content)
    }
}

/** Keep Compose scroll/subsection state; SFTP retains its accepted named-anchor restoration. */
@Composable
internal fun AppSectionContent(section: AppSection, content: @Composable () -> Unit) {
    val savedScreens = rememberSaveableStateHolder()
    if (section == AppSection.Files) content()
    else savedScreens.SaveableStateProvider(section.name, content)
}
