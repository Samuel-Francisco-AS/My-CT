package dev.myct

/** Byte limit avoids copying enormous histories or silently truncating shell input. */
object ClipboardPolicy {
    const val MAX_BYTES = 65_536
    fun allowed(text: String) = text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
}
