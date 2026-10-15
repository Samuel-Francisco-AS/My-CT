package dev.myct

import java.util.Locale

/** Decimal units for display only; counters and integrity checks retain exact Long bytes. */
fun formatFileSize(bytes: Long): String {
    require(bytes >= 0)
    if (bytes < 1000) return "$bytes bytes"
    val (divisor, unit) = when {
        bytes < 1_000_000 -> 1_000.0 to "KB"
        bytes < 1_000_000_000 -> 1_000_000.0 to "MB"
        else -> 1_000_000_000.0 to "GB"
    }
    return String.format(Locale.forLanguageTag("pt-BR"), "%.1f %s", bytes / divisor, unit)
}
