package dev.myct

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalShellStartupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun appOwnedStartupOutsideProjectsIsRestoredBeforeANewSession() {
        val base = temp.newFolder("ide-local")
        val projects = File(base, "projetos").apply { mkdir() }
        val source = File(projects, "teste.c").apply { writeText("saved project") }
        val startup = localShellStartup(base)
        assertEquals(base, startup.parentFile)
        val expected = startup.readText()
        val systemInit = expected.indexOf(". /system/etc/mkshrc")
        assertTrue(systemInit >= 0)
        assertTrue(expected.indexOf("PS1='My-CT> '") > systemInit)
        startup.writeText("PS1='old prompt'\n")
        assertEquals(startup, localShellStartup(base))
        assertEquals(expected, startup.readText())
        assertEquals(listOf(source.name), projects.list()!!.toList())
        assertEquals("saved project", source.readText())
    }
}
