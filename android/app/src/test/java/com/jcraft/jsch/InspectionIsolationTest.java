package com.jcraft.jsch;

import org.junit.Test;
import static org.junit.Assert.*;

/** Real JSch teardown with simulated transport; no authenticated SSH or Moto proof. */
public class InspectionIsolationTest {
    private static class Transport extends Session {
        int disconnects;
        int packets;
        final java.util.List<Integer> commands = new java.util.ArrayList<>();
        Transport() throws Exception {
            super(new JSch(), "synthetic", "unused.invalid", 22);
            java.lang.reflect.Field connected = Session.class.getDeclaredField("isConnected");
            connected.setAccessible(true); connected.setBoolean(this, true);
        }
        @Override public boolean isConnected() { return true; }
        @Override public void disconnect() { disconnects++; }
        @Override void write(Packet packet) { packets++; commands.add((int) packet.buffer.buffer[5]); }
    }
    @Test public void closingInspectionStdinAndExecPreservesTerminalAndSession() throws Exception {
        Transport transport = new Transport();
        ChannelExec exec = (ChannelExec) transport.openChannel("exec");
        ChannelShell shell = (ChannelShell) transport.openChannel("shell");
        exec.connected = true; exec.recipient = 1; exec.rmpsize = 32768;
        shell.connected = true; shell.recipient = 2;
        try {
            exec.setInputStream(null);
            exec.getOutputStream().close(); // Sends stdin EOF to the polling helper.
            exec.disconnect(); exec.disconnect();
            assertFalse(exec.isConnected()); assertTrue(shell.isConnected());
            assertEquals(0, transport.disconnects); assertEquals(2, transport.packets);
            assertEquals(java.util.Arrays.asList(96, 97), transport.commands); // EOF, close.
        } finally { shell.disconnect(); }
    }
}
