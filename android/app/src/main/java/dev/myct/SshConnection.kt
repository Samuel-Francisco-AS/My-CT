package dev.myct

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/** All blocking calls belong on an IO worker, never the Android main thread. */
class SshConnection {
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var closed = false
    private val fileChannels = mutableSetOf<ChannelSftp>()
    private val downloadChannels = mutableSetOf<ChannelExec>()
    private val vaultChannels = mutableSetOf<Channel>()
    private val healthForwarding = HealthForwarding(open = {
        val current = checkNotNull(session)
        check(!closed && current.isConnected)
        current.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 8765)
    }, remove = { port -> session?.delPortForwardingL("127.0.0.1", port) })
    fun health(): HealthSample {
        check(!closed && session?.isConnected == true)
        return fetchHealth(healthForwarding.port())
    }
    /** Query only Secret Service locked/unlocked metadata through existing SSH. */
    fun vaultStatus(): VaultStatus {
        val channel = synchronized(vaultChannels) {
            check(!closed && session?.isConnected == true)
            (session!!.openChannel("exec") as ChannelExec).also { vaultChannels.add(it) }
        }
        try {
            channel.setCommand("\"\$HOME/.local/bin/myct-vault\" status")
            val stream = channel.inputStream
            channel.connect(10_000)
            return readVaultStatus(stream)
        } finally {
            channel.disconnect()
            synchronized(vaultChannels) { vaultChannels.remove(channel) }
        }
    }

    internal fun downloads(action: DownloadAction): String = executeDownload(action, { openDownloadExec() })

    internal fun torrents(action: TorrentAction): String = executeTorrent(action, { openDownloadExec() })

    internal fun search(action: SearchAction): SearchReply = executeSearch(action, { openDownloadExec() })

    internal fun media(action: MediaAction): String = executeMedia(action, { openDownloadExec() })

    internal fun inspectFile(path: String): FileInspection = executeInspection(path) { openDownloadExec(true) }

    internal fun scanAntivirus(path: String): AvScanResult = executeAvScan(path) { openDownloadExec(true) }
    internal fun antivirus(command: AvCommand): SshExecResult =
        executeSshResult(avCommand(command), { openDownloadExec() })

    private fun openDownloadExec(keepInput: Boolean = false): DownloadExec {
        val channel = synchronized(downloadChannels) {
            check(!closed && session?.isConnected == true)
            (session!!.openChannel("exec") as ChannelExec).also { downloadChannels.add(it) }
        }
        // Keep remote stdin open until this operation is cancelled/closed. EOF stops hashing.
        if (keepInput) channel.setInputStream(null)
        val input = if (keepInput) channel.outputStream else null
        return object : DownloadExec {
            override fun connect(command: String, output: OutputStream, error: OutputStream) {
                channel.setCommand(command)
                channel.setOutputStream(output)
                channel.setErrStream(error)
                channel.connect(10_000)
                check(!closed)
            }
            override val done get() = channel.isClosed
            override val exitStatus get() = channel.exitStatus
            override fun close() {
                runCatching { input?.close() }
                channel.disconnect()
                synchronized(downloadChannels) { downloadChannels.remove(channel) }
            }
        }
    }

    /** Separate interactive SSH shell: keeps password off the ordinary PTY and argv. */
    internal fun openVaultUnlock(): VaultUnlockChannel {
        val channel = synchronized(vaultChannels) {
            check(!closed && session?.isConnected == true)
            (session!!.openChannel("shell") as ChannelShell).also { vaultChannels.add(it) }
        }
        try {
            channel.setPty(true)
            channel.setPtyType("dumb", 80, 24, 0, 0)
            channel.connect(10_000)
            check(!closed)
            return VaultUnlockChannel(channel) {
                synchronized(vaultChannels) { vaultChannels.remove(channel) }
            }
        } catch (error: Exception) {
            channel.disconnect()
            synchronized(vaultChannels) { vaultChannels.remove(channel) }
            throw error
        }
    }
    lateinit var input: InputStream
        private set
    lateinit var output: OutputStream
        private set

    fun connect(host: String, port: Int, user: String, password: ByteArray,
                pins: PinnedHostKeys, cols: Int, rows: Int) {
        val client = JSch()
        client.hostKeyRepository = pins
        val newSession = client.getSession(user, host, port)
        session = newSession
        try {
            check(!closed) { "Cancelled" }
            newSession.setConfig("StrictHostKeyChecking", "yes")
            // Use JSch's existing lightweight BC implementations on Android.
            newSession.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            newSession.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            newSession.setConfig("PreferredAuthentications", "password")
            newSession.setConfig("server_host_key", "ssh-ed25519")
            newSession.setPassword(password)
            newSession.timeout = 15_000
            newSession.setServerAliveInterval(10_000)
            newSession.setServerAliveCountMax(3)
            newSession.connect(15_000)
            check(pins.verified && !closed) { "Host verification/cancellation" }
            newSession.setPassword(null as ByteArray?)
            val channel = newSession.openChannel("shell") as ChannelShell
            shell = channel
            channel.setPty(true)
            channel.setPtyType("xterm-256color", cols, rows, 0, 0)
            input = channel.inputStream
            output = channel.outputStream
            channel.connect(10_000)
            check(!closed) { "Cancelled" }
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            password.fill(0)
            newSession.setPassword(null as ByteArray?)
        }
    }

    fun resize(cols: Int, rows: Int, width: Int, height: Int) {
        shell?.takeIf { it.isConnected }?.let { com.jcraft.jsch.PtyWindowChange.resize(it, cols, rows, width, height) }
    }
    fun openFiles(control: TransferControl): RemoteStore {
        control.check()
        val channel = synchronized(fileChannels) {
            check(!closed && session?.isConnected == true)
            (session!!.openChannel("sftp") as ChannelSftp).also { fileChannels.add(it) }
        }
        val store = JschRemoteStore(channel) {
            runCatching { channel.disconnect() }
            synchronized(fileChannels) { fileChannels.remove(channel) }
        }
        try {
            control.trackTransport(store) // Cancels an in-flight channel connect without closing the PTY.
            control.check()
            channel.connect(10_000)
            control.check()
            return store
        } catch (e: Exception) {
            // Cancellation may close a not-yet-connected channel. If connect won that race,
            // disconnect again here: an idempotent store release alone cannot close it.
            runCatching { channel.disconnect() }
            store.close()
            throw e
        }
    }
    fun close() {
        val channels = synchronized(fileChannels) { closed = true; fileChannels.toList().also { fileChannels.clear() } }
        synchronized(downloadChannels) { downloadChannels.toList().also { downloadChannels.clear() } }.forEach { it.disconnect() }
        healthForwarding.close()
        synchronized(vaultChannels) { vaultChannels.toList().also { vaultChannels.clear() } }.forEach { it.disconnect() }
        channels.forEach { it.disconnect() }
        shell?.disconnect()
        session?.disconnect()
    }
}
