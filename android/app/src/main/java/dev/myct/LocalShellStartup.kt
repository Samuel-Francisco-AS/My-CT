package dev.myct

import java.io.File

/** Outside projetos/: private app configuration, never a project or a SAF document. */
internal fun localShellStartup(base: File): File {
    check(base.isDirectory || base.mkdirs()) { "Shell startup directory unavailable" }
    return File(base, "shell-init.mkshrc").apply {
        writeText("""
            # My-CT: preserve Android initialization, then apply the local prompt.
            if [ -r /system/etc/mkshrc ]; then
                . /system/etc/mkshrc
            fi
            PS1='My-CT> '
            export PS1
        """.trimIndent() + "\n", Charsets.UTF_8)
    }
}
