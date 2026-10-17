package dev.myct

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val MyCtTypography = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
}
private val MyCtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/** Fixed identity, including dialogs/forms; ANSI and the native terminal renderer stay untouched. */
@Composable
internal fun MyCtTheme(content: @Composable () -> Unit) {
    val background = colorResource(R.color.myct_background)
    val surface = colorResource(R.color.myct_surface)
    val elevated = colorResource(R.color.myct_surface_elevated)
    val text = colorResource(R.color.myct_text)
    val secondaryText = colorResource(R.color.myct_text_secondary)
    val gold = colorResource(R.color.myct_gold)
    val secondaryGold = colorResource(R.color.myct_gold_secondary)
    val onGold = colorResource(R.color.myct_on_gold)
    val goldContainer = colorResource(R.color.myct_gold_container)
    val success = colorResource(R.color.myct_success)
    val successContainer = colorResource(R.color.myct_success_container)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = gold, onPrimary = onGold,
            primaryContainer = goldContainer, onPrimaryContainer = gold,
            inversePrimary = onGold,
            secondary = secondaryGold, onSecondary = onGold,
            secondaryContainer = goldContainer, onSecondaryContainer = secondaryGold,
            tertiary = success, onTertiary = background,
            tertiaryContainer = successContainer, onTertiaryContainer = success,
            primaryFixed = gold, primaryFixedDim = secondaryGold,
            onPrimaryFixed = onGold, onPrimaryFixedVariant = goldContainer,
            secondaryFixed = secondaryGold, secondaryFixedDim = secondaryGold,
            onSecondaryFixed = onGold, onSecondaryFixedVariant = goldContainer,
            tertiaryFixed = success, tertiaryFixedDim = success,
            onTertiaryFixed = background, onTertiaryFixedVariant = successContainer,
            background = background, onBackground = text,
            surface = surface, onSurface = text, surfaceVariant = elevated,
            onSurfaceVariant = secondaryText, surfaceTint = gold,
            inverseSurface = text, inverseOnSurface = background,
            error = colorResource(R.color.myct_error), onError = background,
            errorContainer = colorResource(R.color.myct_error_container), onErrorContainer = text,
            outline = colorResource(R.color.myct_bronze), outlineVariant = colorResource(R.color.myct_outline),
            scrim = Color.Black,
            surfaceBright = elevated, surfaceDim = background,
            surfaceContainerLowest = background, surfaceContainerLow = surface,
            surfaceContainer = surface, surfaceContainerHigh = elevated, surfaceContainerHighest = elevated
        ),
        typography = MyCtTypography,
        shapes = MyCtShapes,
        content = content
    )
}
