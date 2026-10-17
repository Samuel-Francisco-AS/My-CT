package com.jcraft.jsch;

import dev.myct.JschRemoteStore;
import kotlin.Unit;
import java.io.*;

/** Real JSch SFTP protocol against a disposable OpenSSH subsystem process.
 * SSH framing/authentication is simulated; no sshd, TCP listener, keys or credentials.
 */
public final class LocalSftpSubsystem implements AutoCloseable {
    private final Process process;
    private final ChannelSftp channel;
    private final Thread reader;
    private volatile boolean stopped;
    public final JschRemoteStore store;
    private final class Transport extends Session {
        Transport() throws Exception {
            super(new JSch(), "fixture", "unused.invalid", 22);
            java.lang.reflect.Field field = Session.class.getDeclaredField("isConnected");
            field.setAccessible(true); field.setBoolean(this, true);
        }
        @Override public boolean isConnected() { return !stopped; }
        @Override void write(Packet packet) {
            // A real SSH peer would acknowledge the subsystem request before SFTP INIT.
            if (packet.buffer.buffer[5] == 98) channel.reply = 1;
        }
        @Override void write(Packet packet, Channel ignored, int length) throws Exception {
            if (stopped) throw new IOException("fixture closed");
            // SSH_MSG_CHANNEL_DATA framing is 14 bytes before SFTP payload.
            process.getOutputStream().write(packet.buffer.buffer, 14, length);
            process.getOutputStream().flush();
        }
    }
    public LocalSftpSubsystem(String directory) throws Exception {
        process = new ProcessBuilder("/usr/libexec/openssh/sftp-server", "-d", directory)
            .redirectError(new File("/dev/null")).start();
        Transport transport = new Transport();
        channel = (ChannelSftp) transport.openChannel("sftp");
        channel.connected = true; channel.recipient = 0; channel.rmpsize = 32768; channel.rwsize = 1 << 20;
        reader = new Thread(() -> {
            try {
                byte[] buffer = new byte[32768];
                int count;
                // start() sets up io.out before sending INIT; server cannot reply before INIT.
                while ((count = process.getInputStream().read(buffer)) >= 0) channel.write(buffer, 0, count);
            } catch (Exception ignored) { }
        }, "myct-test-sftp-subsystem");
        reader.setDaemon(true); reader.start();
        try { channel.start(); }
        catch (Exception error) { close(); throw error; }
        store = new JschRemoteStore(channel, () -> { close(); return Unit.INSTANCE; });
    }
    @Override public void close() {
        stopped = true;
        channel.disconnect(); process.destroy();
        try { reader.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
