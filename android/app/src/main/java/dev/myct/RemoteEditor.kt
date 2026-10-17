package dev.myct

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

internal const val IDE_MAX_BYTES = 1024 * 1024
internal data class EditMetadata(val size: Long, val modified: Long, val mode: Int, val uid: Int, val gid: Int)
internal interface RemoteEditorStore : RemoteStore {
    fun privateDirectory(path: String)
    fun editMetadata(path: String): EditMetadata
    fun prepareMode(path: String, original: EditMetadata)
    fun supportsAtomicEdit(): Boolean
    fun backup(source: String, backup: String)
    fun replace(temporary: String, target: String)
}
internal data class EditSnapshot(val path: String, val bytes: ByteArray, val metadata: EditMetadata) {
    val digest: String get() = editDigest(bytes)
}
internal class EditConflict : Exception()
internal class EditRejected(val explanation: String) : Exception()
internal class EditUncertain(val backupPath: String) : Exception()
internal data class EditSaved(val snapshot: EditSnapshot, val backupPath: String)
internal fun editDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
internal fun decodeCode(bytes: ByteArray): String {
    if (bytes.size > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw EditRejected("O arquivo não contém UTF-8 válido.") }
    if (text.any { it == '\u0000' || (it.code < 32 && it !in "\t\r\n\u000c") })
        throw EditRejected("Arquivo binário ou com controles incompatíveis com edição.")
    if (text.lineSequence().any { it.length > 16384 } || text.count { it == '\n' } > 30000)
        throw EditRejected("Arquivo com linhas muito grandes ou mais de 30 mil linhas.")
    return text
}
internal fun encodeCode(text: CharSequence): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
    if (buffer.remaining() > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
    return ByteArray(buffer.remaining()).also { buffer.get(it); decodeCode(it) }
}

/** Optimistic SFTP transaction. Original is never opened for truncating writes.
 * Same-directory atomic rename + retained hardlink backup; no shell or silent force-save.
 * SFTP has no compare-and-swap: another writer must not edit during the final check/rename.
 */
internal object RemoteEditor {
    fun load(store: RemoteEditorStore, path: String, control: TransferControl): EditSnapshot {
        RemotePath.absolute(path)
        control.check()
        SftpTransfers.requireRegular(store, path) // Reject symlinks/devices, don't follow on save.
        val before = store.editMetadata(path)
        if (before.size > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
        val output = ByteArrayOutputStream()
        store.read(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                control.check()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
                output.write(buffer, 0, count)
            }
        }
        control.check()
        val after = store.editMetadata(path)
        val bytes = output.toByteArray()
        if (before != after || bytes.size.toLong() != before.size) throw EditConflict()
        decodeCode(bytes)
        return EditSnapshot(path, bytes, before)
    }
    fun save(store: RemoteEditorStore, original: EditSnapshot, bytes: ByteArray, control: TransferControl,
             directory: String = RemotePath.child(RemotePath.parent(original.path), ".myct-edit-${UUID.randomUUID()}")): EditSaved {
        decodeCode(bytes)
        if (!store.supportsAtomicEdit()) throw EditRejected("Servidor sem rename atômico/hardlink OpenSSH; original preservado.")
        if (original.metadata.mode and 0x80 == 0 || original.metadata.mode and 0xE00 != 0)
            throw EditRejected("Edição exige arquivo gravável pelo proprietário e sem bits especiais.")
        fun unchanged() {
            val current = load(store, original.path, control)
            if (current.metadata != original.metadata || !current.bytes.contentEquals(original.bytes)) throw EditConflict()
        }
        unchanged()
        require(RemotePath.parent(directory) == RemotePath.parent(original.path) && directory.substringAfterLast('/').startsWith(".myct-edit-"))
        val temporary = RemotePath.child(directory, "new")
        val backup = RemotePath.child(directory, "original")
        store.privateDirectory(directory)
        var backupCreated = false
        var publishing = false
        var saved = false
        try {
            store.writeNewTemporary(temporary).use { out ->
                control.check(); out.write(bytes); out.flush(); control.check()
            }
            store.prepareMode(temporary, original.metadata)
            val prepared = load(store, temporary, control)
            if (!prepared.bytes.contentEquals(bytes)) throw IntegrityFailure()
            unchanged()
            store.backup(original.path, backup)
            backupCreated = true
            val backedUp = load(store, backup, control)
            if (backedUp.metadata != original.metadata || !backedUp.bytes.contentEquals(original.bytes)) throw EditConflict()
            unchanged()
            control.check()
            publishing = true // After this point interruption has an uncertain outcome; keep backup.
            store.replace(temporary, original.path)
            val verified = load(store, original.path, control)
            if (!verified.bytes.contentEquals(bytes) || verified.metadata.mode != original.metadata.mode ||
                verified.metadata.uid != original.metadata.uid || verified.metadata.gid != original.metadata.gid) throw IntegrityFailure()
            saved = true
            return EditSaved(verified, backup)
        } catch (error: Exception) {
            if (publishing) throw EditUncertain(backup)
            throw error
        } finally {
            // Retain backup on success/uncertainty. Never delete the original or a published target.
            runCatching { store.removeTemporary(temporary) }
            if (!publishing && backupCreated) runCatching { store.removeTemporary(backup) }
            if (!saved && !publishing) runCatching { store.removeTemporaryDirectory(directory) }
        }
    }
}
