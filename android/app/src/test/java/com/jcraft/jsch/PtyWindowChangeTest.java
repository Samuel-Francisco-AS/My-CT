package com.jcraft.jsch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Real pinned JSch packet construction; no socket/auth or manual SSH encoder. */
public class PtyWindowChangeTest {
    private static class Transport extends Session {
        int[] dimensions;
        int requests;
        int wantReply;
        ChannelShell replyTarget;
        Transport() throws Exception {
            super(new JSch(), "synthetic", "unused.invalid", 22);
            java.lang.reflect.Field field = Session.class.getDeclaredField("isConnected");
            field.setAccessible(true); field.setBoolean(this, true);
        }
        @Override public boolean isConnected() { return true; }
        @Override void write(Packet packet) {
            Buffer b = new Buffer(Arrays.copyOf(packet.buffer.buffer, packet.buffer.index));
            b.setOffSet(5);
            if (b.getByte() != Session.SSH_MSG_CHANNEL_REQUEST) return;
            assertEquals(2, b.getInt());
            assertEquals("window-change", new String(b.getString(), StandardCharsets.UTF_8));
            wantReply = b.getByte();
            if (wantReply != 0) replyTarget.reply = 1; // Simulated immediate ACK for baseline only.
            dimensions = new int[] {b.getInt(), b.getInt(), b.getInt(), b.getInt()};
            requests++;
        }
    }
    @Test public void sendsNoReplyWindowChangeAndRestoresOriginalTimeout() throws Exception {
        Transport transport = new Transport();
        ChannelShell shell = (ChannelShell) transport.openChannel("shell");
        shell.connected = true; shell.recipient = 2; shell.setPty(true); shell.connectTimeout = 10000;
        try {
            transport.replyTarget = shell;
            shell.setPtySize(55, 20, 440, 320);
            assertEquals(1, transport.wantReply); // Actual JSch inherits connectTimeout>0.
            PtyWindowChange.resize(shell, 55, 49, 440, 784);
            assertArrayEquals(new int[] {55, 49, 440, 784}, transport.dimensions);
            assertEquals(0, transport.wantReply);
            assertEquals(2, transport.requests);
            assertEquals(10000, shell.connectTimeout);
        } finally { shell.disconnect(); }
    }
    @Test public void timeoutRestoredEvenIfRequestThrows() {
        ChannelShell shell = new ChannelShell() {
            @Override public void setPtySize(int c, int r, int w, int h) {
                assertEquals(0, connectTimeout);
                throw new IllegalStateException("synthetic");
            }
        };
        shell.connectTimeout = 10000;
        try {
            PtyWindowChange.resize(shell, 55, 49, 0, 0);
            fail("Expected fixture failure");
        } catch (IllegalStateException expected) {
            assertEquals(10000, shell.connectTimeout);
        }
    }
}
