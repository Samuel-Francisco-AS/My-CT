package dev.myct

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Paths are data, never shell commands. Linux permissions remain the authority. */
object RemotePath {
    fun name(value: String): String {
        require(value.isNotEmpty() && value != "." && value != ".." && '/' !in value)
        require(value.none { it.code < 32 || it.code == 127 } && value.toByteArray(Charsets.UTF_8).size <= 255)
        return value
    }
    fun absolute(value: String): String {
        require(value.startsWith('/') && value.length <= 16384 && value.none { it.code < 32 || it.code == 127 })
        require(value.split('/').none { it == "." || it == ".." })
        return value.trimEnd('/').ifEmpty { "/" }
    }
    fun child(directory: String, entry: String) = absolute(directory).let { if (it == "/") "/${name(entry)}" else "$it/${name(entry)}" }
    fun parent(path: String) = absolute(path).substringBeforeLast('/', "").ifEmpty { "/" }
    // JSch expands glob patterns in several APIs, even though this is not a shell.
    fun literal(path: String) = path.flatMap { if (it == '\\' || it == '*' || it == '?') listOf('\\', it) else listOf(it) }.joinToString("")
}

enum class RemoteKind { DIRECTORY, FILE, LINK, OTHER }
data class RemoteEntry(val name: String, val kind: RemoteKind, val size: Long, val modified: Long)
data class RemoteListing(val path: String, val entries: List<RemoteEntry>, val limited: Boolean)
class NameConflict : Exception()
class IntegrityFailure : Exception()
class SafePublishUnavailable : Exception()

/** Exactly one worker owns a store/channel. Cancellation may close it from another IO worker. */
interface RemoteStore : Closeable {
    fun home(): String
    fun canonical(path: String): String
    fun list(path: String): RemoteListing
    fun stat(path: String): RemoteEntry?
    fun read(path: String): InputStream
    fun writeNewTemporary(path: String): OutputStream
    fun supportsSafePublish(): Boolean
    fun mkdir(path: String)
    fun publishNoReplace(temporary: String, target: String)
    fun removeTemporary(path: String)
    fun removeTemporaryDirectory(path: String)
}

class JschRemoteStore(private val channel: ChannelSftp, private val release: () -> Unit) : RemoteStore {
    override fun close() = release()
    override fun home() = RemotePath.absolute(channel.home)
    override fun canonical(path: String) = RemotePath.absolute(channel.realpath(RemotePath.absolute(path)))
    private fun entry(name: String, a: com.jcraft.jsch.SftpATTRS) = RemoteEntry(name,
        when { a.isLink -> RemoteKind.LINK; a.isDir -> RemoteKind.DIRECTORY; a.isReg -> RemoteKind.FILE; else -> RemoteKind.OTHER },
        a.size, a.mTime.toLong() and 0xffffffffL)
    override fun list(path: String): RemoteListing {
        val result = mutableListOf<RemoteEntry>()
        var limited = false
        channel.ls(RemotePath.literal(path), ChannelSftp.LsEntrySelector { item ->
            if (item.filename != "." && item.filename != "..") {
                if (result.size >= 2000) { limited = true; return@LsEntrySelector ChannelSftp.LsEntrySelector.BREAK }
                if (runCatching { RemotePath.name(item.filename) }.isSuccess) result.add(entry(item.filename, item.attrs))
                else limited = true
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        })
        return RemoteListing(path, result.distinctBy { it.name }.sortedWith(compareBy<RemoteEntry> { it.kind != RemoteKind.DIRECTORY }.thenBy { it.name }), limited)
    }
    override fun stat(path: String): RemoteEntry? = try { entry(path.substringAfterLast('/'), channel.lstat(RemotePath.literal(path))) }
        catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e }
    override fun read(path: String): InputStream = channel.get(RemotePath.literal(path))
    override fun writeNewTemporary(path: String): OutputStream = channel.put(RemotePath.literal(path), ChannelSftp.OVERWRITE)
    override fun supportsSafePublish() = channel.getExtension("hardlink@openssh.com") == "1"
    // mkdir/realpath take literal paths; glob-aware APIs take escaped paths.
    override fun mkdir(path: String) = channel.mkdir(path)
    override fun publishNoReplace(temporary: String, target: String) {
        try { channel.hardlink(RemotePath.literal(temporary), RemotePath.literal(target)) }
        catch (e: SftpException) {
            if (runCatching { stat(target) != null }.getOrDefault(false)) throw NameConflict()
            throw e
        }
    }
    override fun removeTemporary(path: String) = channel.rm(RemotePath.literal(path))
    override fun removeTemporaryDirectory(path: String) = channel.rmdir(RemotePath.literal(path))
}

/** No credential/content logging. All close actions run on IO, including provider streams. */
class TransferControl {
    val bytesTransferred = AtomicLong()
    private val stopped = AtomicBoolean()
    private val resources = mutableListOf<Closeable>()
    val cancelled get() = stopped.get()
    fun check() { if (cancelled) throw CancellationException() }
    fun track(resource: Closeable) {
        synchronized(resources) {
            if (!cancelled) { resources.add(resource); return }
        }
        runCatching { resource.close() }
        throw CancellationException()
    }
    fun cancel() {
        stopped.set(true)
        val copy = synchronized(resources) { resources.toList() }
        // Transport is registered first: close it before stream.close can wait for ACKs.
        copy.forEach { runCatching { it.close() } }
    }
}

data class CopyResult(val bytes: Long, val sha256: String)
object SftpTransfers {
    fun copy(source: InputStream, target: OutputStream, control: TransferControl, progress: (Long) -> Unit): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            control.check()
            val n = source.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            control.check()
            target.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            count += n
            progress(count)
        }
        control.check()
        target.flush()
        return CopyResult(count, digest.digest().joinToString("") { "%02x".format(it) })
    }
    fun requireRegular(store: RemoteStore, path: String): RemoteEntry {
        val entry = store.stat(path) ?: throw java.io.FileNotFoundException()
        require(entry.kind == RemoteKind.FILE) { "Regular file required" }
        return entry
    }
    fun upload(store: RemoteStore, directory: String, name: String, source: InputStream, expected: Long?,
               control: TransferControl, progress: (Long) -> Unit): CopyResult {
        val target = RemotePath.child(directory, name)
        control.check()
        if (store.stat(target) != null) throw NameConflict()
        if (!store.supportsSafePublish()) throw SafePublishUnavailable()
        val temporaryDirectory = RemotePath.child(directory, ".myct-upload-${UUID.randomUUID()}")
        val temporary = RemotePath.child(temporaryDirectory, "payload")
        var created = false
        try {
            store.mkdir(temporaryDirectory) // Atomic reservation; never reuse an existing directory.
            created = true
            control.check()
            val result = store.writeNewTemporary(temporary).use { output ->
                control.track(output)
                copy(source, output, control, progress)
            }
            if (expected != null && result.bytes != expected) throw IntegrityFailure()
            if (requireRegular(store, temporary).size != result.bytes) throw IntegrityFailure()
            control.check()
            if (store.stat(target) != null) throw NameConflict()
            // OpenSSH hardlink fails atomically if target exists, including a symlink.
            // JSch rename may use posix-rename (overwrites), so it is intentionally not used.
            store.publishNoReplace(temporary, target)
            // Commit point: late cancellation cannot undo or delete a completed target.
            return result
        } finally {
            if (created) {
                runCatching { store.removeTemporary(temporary) }
                runCatching { store.removeTemporaryDirectory(temporaryDirectory) }
            }
        }
    }
    fun download(store: RemoteStore, path: String, expected: RemoteEntry, target: OutputStream,
                 control: TransferControl, progress: (Long) -> Unit): CopyResult {
        require(expected.kind == RemoteKind.FILE)
        val before = requireRegular(store, path)
        if (before.size != expected.size || before.modified != expected.modified) throw IntegrityFailure()
        val result = store.read(path).use { input ->
            control.track(input)
            copy(input, target, control, progress)
        }
        val after = requireRegular(store, path)
        if (result.bytes != before.size || before.size != after.size || before.modified != after.modified) throw IntegrityFailure()
        return result
    }
}
