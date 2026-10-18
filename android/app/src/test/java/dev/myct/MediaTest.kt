package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MediaTest {
    private val hash = "a".repeat(40)
    private val id = "$hash:Witch Hat Atelier S01E01.mkv"

    @Test fun previewAndManagedLinksAreParsed() {
        val preview = parseMediaPreview("""
            {"added":[{"id":"$id","target":"Series/Witch Hat Atelier/Season 01/Witch Hat Atelier S01E01.mkv"}],
             "existing":[{"id":"$id","target":"Series/Frieren/Season 01/first.mkv"}],
             "pending":[{"id":"$id","name":"x.mkv","reason":"não reconhecido"}],"ignored":1}
        """.trimIndent())
        assertEquals(1, preview.proposals.size)
        assertEquals(1, preview.existing)
        assertEquals(1, preview.pending.size)
        assertEquals(1, preview.ignored)
        val links = parseMediaEntries("""{"links":{"Series/Witch Hat Atelier/Season 01/Witch Hat Atelier S01E01.mkv":{"id":"$id","dev":1,"ino":2}},"ignored":[]}""")
        assertEquals(1, links.size)
        assertEquals(id, links.single().id)
    }

    @Test fun rejectsUntrustedPathsAndMalformedReplies() {
        for (path in listOf("/etc/passwd", "Series/../bad.mkv", "Series//bad.mkv", "Series/a\nb.mkv", "Films/A.mkv")) {
            assertFalse(validMediaPath(path))
            assertThrows(IllegalArgumentException::class.java) { mediaCommand(MediaAction.Remove(path)) }
        }
        assertThrows(IllegalArgumentException::class.java) { mediaCommand(MediaAction.Skip("all")) }
        assertThrows(IllegalArgumentException::class.java) { mediaCommand(MediaAction.ApproveSeries(id, "x", 1, 1000)) }
        assertThrows(IllegalArgumentException::class.java) { mediaCommand(MediaAction.ApproveMovie(id, "x", 1500)) }
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaPreview("""{"added":[{"id":"$id","target":"Series/../secret"}],"existing":[],"pending":[],"ignored":0}""")
        }
    }

    @Test fun quotesShellArgumentsAndKeepsUserStringsAsData() {
        val root = Files.createTempDirectory("myct-media-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-media")
            checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\\$@\"\n")
            assertTrue(cli.setExecutable(true))
            val evil = "Film'; touch injected; echo '"
            val command = mediaCommand(MediaAction.ApproveMovie(id, evil, 2025))
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .apply { environment()["HOME"] = root.path }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertEquals("approve\n$id\nmovie\n$evil\n2025\n", output)
            assertFalse(root.resolve("injected").exists())
        } finally { root.deleteRecursively() }
    }
}
