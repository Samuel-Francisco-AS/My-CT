package dev.myct

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

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
    var direction by mutableStateOf(Offset(0f, 1f))
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

    fun startExit() { direction = navigationDirection(displayed, requested) }

    fun settle() {
        if (displayed == requested) protectCapture = displayed == AppSection.Vault
    }

    companion object {
        // Restore the latest intent with a fresh entrance, never a half-faded outgoing screen.
        val Saver = Saver<SectionNavigation, String>(
            save = { it.requested.name }, restore = { SectionNavigation(AppSection.valueOf(it)) })
    }
}

/** One LaunchedEffect; signed travel also reverses an interrupted exit without a position jump. */
internal suspend fun animateSectionChange(
    navigation: SectionNavigation,
    travel: Animatable<Float, AnimationVector1D>,
    ready: () -> Boolean
) {
    if (!ready()) {
        // Opening/reopening the menu cancels a swap, restoring its current background.
        travel.animateTo(0f, tween(160, easing = NavigationEnterEasing))
        return
    }
    if (navigation.displayed != navigation.requested) {
        // Retargeting mid-flight keeps the existing axis until rest: no sideways jump.
        if (travel.value == 0f) navigation.startExit()
        travel.animateTo(-1f, tween(100, easing = FastOutLinearInEasing))
        if (!ready()) return
        navigation.showRequested()
        travel.snapTo(1f)
    }
    // Compose applies the single destination at zero opacity, then brings it in from its edge.
    // Animatable uses the composition's MotionDurationScale, including scale zero.
    travel.animateTo(0f, tween(NavigationEnterMillis, easing = NavigationEnterEasing))
    navigation.settle()
}

@Composable
internal fun AnimatedAppSection(
    navigation: SectionNavigation,
    ready: Boolean,
    content: @Composable () -> Unit
) {
    val travel = remember { Animatable(1f) }
    val latestReady by rememberUpdatedState(ready)
    LaunchedEffect(navigation.requested, ready) {
        animateSectionChange(navigation, travel) { latestReady }
    }
    // A persistent host/SaveableStateHolder, with no AnimatedContent/Crossfade overlap.
    // Layer-only transforms never resize the native viewport. Home owns its staggered entrance.
    Box(Modifier.fillMaxSize().graphicsLayer {
        val p = travel.value
        val homeEntering = navigation.displayed == AppSection.Home && p >= 0f
        alpha = if (homeEntering) 1f else navigationAlpha(p)
        translationX = if (homeEntering) 0f else navigation.direction.x * p * 32.dp.toPx()
        translationY = if (homeEntering) 0f else navigation.direction.y * p * 24.dp.toPx()
    }) {
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
