package dev.myct

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

internal class LocalWriteUncertain : Exception()

/** Relative workspace paths only. No RemotePath, shell, SFTP or storage permission. Call on IO. */
internal class LocalWorkspace(private val base: File) {
    private val root = File(base, "projetos").toPath()
    private val origins = File(base, "importados.properties").toPath()
    /** Same checked root as IDE creation, initialized only on explicit shell start. */
    fun terminalDirectory(): File = resolve("").toFile()
    fun name(value: String): String {
        if (value.isBlank() || value in setOf(".", "..") || value.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } ||
            value.toByteArray(Charsets.UTF_8).size > 240 || value.startsWith(".myct-"))
            throw EditRejected("Nome inválido: use até 240 bytes, sem barras, controles, . ou ...")
        return value
    }
    fun child(directory: String, entry: String): String = listOf(directory, name(entry)).filter { it.isNotEmpty() }.joinToString("/")
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    private fun resolve(path: String): java.nio.file.Path {
        if (path.length > 4096 || path.startsWith('/') || (path.isNotEmpty() && path.split('/').any { name(it) != it }))
            throw EditRejected("Caminho fora de Projetos locais.")
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(base.toPath())) throw EditRejected("Links não são permitidos na workspace.")
        Files.createDirectories(root)
        var result = root
        if (path.isNotEmpty()) for (part in path.split('/')) {
            result = result.resolve(part)
            if (Files.isSymbolicLink(result)) throw EditRejected("Links não são permitidos na workspace.")
        }
        if (!result.normalize().startsWith(root)) throw EditRejected("Caminho fora de Projetos locais.")
        return result
    }
    fun list(path: String = ""): RemoteListing {
        val directory = resolve(path)
        val entries = mutableListOf<RemoteEntry>()
        var limited = false
        Files.newDirectoryStream(directory).use { stream ->
            for (file in stream) {
                val filename = file.fileName.toString()
                if (filename.startsWith(".myct-")) continue
                if (runCatching { name(filename) }.isFailure) { limited = true; continue }
                if (entries.size == 2000) { limited = true; break }
                val a = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                entries.add(RemoteEntry(filename, when {
                    a.isSymbolicLink -> RemoteKind.LINK; a.isDirectory -> RemoteKind.DIRECTORY
                    a.isRegularFile -> RemoteKind.FILE; else -> RemoteKind.OTHER
                }, a.size(), a.lastModifiedTime().toMillis() / 1000))
            }
        }
        return RemoteListing(path, entries.sortedWith(compareBy<RemoteEntry> { it.kind != RemoteKind.DIRECTORY }.thenBy { it.name }), limited)
    }
    fun create(directory: String, filename: String, folder: Boolean): String {
        val path = child(directory, filename)
        val file = resolve(path)
        if (folder) Files.createDirectory(file) else Files.createFile(file)
        return path
    }
    private fun metadata(path: java.nio.file.Path): EditMetadata {
        val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!a.isRegularFile) throw EditRejected("Abra um arquivo regular de texto; links não são editáveis.")
        return EditMetadata(a.size(), a.lastModifiedTime().toMillis(), 0x180, 0, 0)
    }
    fun load(path: String): EditSnapshot {
        val file = resolve(path)
        val before = metadata(file)
        val bytes = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use(::readIdeBytes)
        if (before != metadata(file) || bytes.size.toLong() != before.size) throw EditConflict()
        return EditSnapshot(path, bytes, before)
    }
    fun save(original: EditSnapshot, bytes: ByteArray): EditSnapshot {
        decodeCode(bytes)
        fun unchanged() {
            val current = load(original.path)
            if (current.metadata != original.metadata || !current.bytes.contentEquals(original.bytes)) throw EditConflict()
        }
        unchanged()
        val target = resolve(original.path)
        val temporary = Files.createTempFile(target.parent, ".myct-save-", ".tmp")
        var publishing = false
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!Files.readAllBytes(temporary).contentEquals(bytes)) throw IntegrityFailure()
            unchanged()
            publishing = true
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            val verified = load(original.path)
            if (!verified.bytes.contentEquals(bytes)) throw IntegrityFailure()
            return verified
        } catch (e: Exception) {
            if (publishing) throw LocalWriteUncertain()
            throw e
        } finally { runCatching { Files.deleteIfExists(temporary) } }
    }
    private fun importedPaths(): Properties = Properties().also { props ->
        if (Files.exists(origins)) Files.newInputStream(origins).use { props.load(it) }
    }
    private fun writeOrigins(props: Properties) {
        val temporary = Files.createTempFile(base.toPath(), ".myct-origin-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { props.store(it, "SAF copies, workspace-relative paths"); it.fd.sync() }
            Files.move(temporary, origins, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { runCatching { Files.deleteIfExists(temporary) } }
    }
    fun isImported(path: String): Boolean = importedPaths().getProperty(path) == "SAF"
    fun rename(path: String, newName: String): String {
        if (path.isEmpty()) throw EditRejected("A raiz Projetos locais não pode ser renomeada.")
        val targetPath = child(parent(path), newName)
        val source = resolve(path); val target = resolve(targetPath)
        if (Files.exists(target, NOFOLLOW_LINKS)) throw EditRejected("Já existe um item com esse nome.")
        val props = importedPaths()
        Files.move(source, target) // no REPLACE_EXISTING
        for (key in props.stringPropertyNames().toList()) if (key == path || key.startsWith("$path/")) {
            props.setProperty(targetPath + key.removePrefix(path), props.getProperty(key)); props.remove(key)
        }
        // File rename is complete even if provenance metadata cannot be updated.
        runCatching { writeOrigins(props) }.getOrElse { throw LocalWriteUncertain() }
        return targetPath
    }
    fun importCopy(filename: String, bytes: ByteArray): String {
        decodeCode(bytes)
        val safe = name(filename)
        val directory = resolve("Importados")
        if (!Files.exists(directory)) Files.createDirectory(directory)
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var path = child("Importados", safe)
        var index = 1
        while (Files.exists(resolve(path), NOFOLLOW_LINKS)) path = child("Importados", name("${stem.take(80)}-${index++}$extension"))
        val temporary = Files.createTempFile(directory, ".myct-import-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!Files.readAllBytes(temporary).contentEquals(bytes)) throw IntegrityFailure()
            // Record provenance first. A failed publication may leave a harmless entry for
            // a nonexistent file, but never an imported file falsely labelled as original.
            val props = importedPaths(); props.setProperty(path, "SAF"); writeOrigins(props)
            Files.move(temporary, resolve(path)) // no replacement, only complete validated content
            return path
        } finally { runCatching { Files.deleteIfExists(temporary) } }
    }
}

/** Bounded, strict UTF-8 even when SAF lies about SIZE or MIME. */
internal fun readIdeBytes(input: InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray().also(::decodeCode)
}
