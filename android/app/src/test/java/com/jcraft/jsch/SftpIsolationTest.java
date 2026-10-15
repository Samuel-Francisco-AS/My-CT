package com.jcraft.jsch;

import dev.myct.JschRemoteStore;
import dev.myct.TransferControl;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual JSch channel teardown, simulated connected Session: no socket, auth or Android proof. */
public class SftpIsolationTest {
    private static class Transport extends Session {
        int disconnects;
        int packets;
        Transport() throws Exception {
            super(new JSch(), "synthetic", "unused.invalid", 22);
            java.lang.reflect.Field connected = Session.class.getDeclaredField("isConnected");
            connected.setAccessible(true);
            connected.setBoolean(this, true); // openChannel checks the private field directly.
        }
        @Override public boolean isConnected() { return true; }
        @Override public void disconnect() { disconnects++; }
        @Override void write(Packet packet) { packets++; }
    }
    @Test public void cancelAndRepeatedCloseAffectOnlySftpChannel() throws Exception {
        Transport transport = new Transport();
        ChannelSftp sftp = (ChannelSftp) transport.openChannel("sftp");
        ChannelShell shell = (ChannelShell) transport.openChannel("shell");
        // Model an already-connected pair of channels; channel.connect/auth are not exercised.
        sftp.connected = true; sftp.recipient = 1;
        shell.connected = true; shell.recipient = 2;
        TransferControl control = new TransferControl();
        JschRemoteStore store = new JschRemoteStore(sftp, () -> { sftp.disconnect(); return Unit.INSTANCE; });
        try {
            control.trackTransport(store);
            control.cancel(); control.cancel(); store.close();
            assertFalse(sftp.isConnected());
            assertTrue(shell.isConnected());
            assertEquals(0, transport.disconnects);
            assertEquals(1, transport.packets);
            assertTrue(transport.isConnected());
        } finally { shell.disconnect(); }
    }
}
