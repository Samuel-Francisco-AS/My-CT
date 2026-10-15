package dev.myct

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/** All blocking calls belong on an IO worker, never the Android main thread. */
class SshConnection {
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var closed = false
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
        shell?.takeIf { it.isConnected }?.setPtySize(cols, rows, width, height)
    }
    fun close() {
        closed = true
        shell?.disconnect()
        session?.disconnect()
    }
}
