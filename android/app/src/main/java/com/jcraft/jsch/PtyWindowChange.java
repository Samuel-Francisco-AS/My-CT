package com.jcraft.jsch;

/** Narrow adapter for pinned JSch 2.28.8; caller must own all writes/requests on this shell. */
public final class PtyWindowChange {
    private PtyWindowChange() {}

    public static void resize(ChannelShell channel, int cols, int rows, int width, int height) {
        // RFC 4254 §6.7: window-change has want_reply=false. Request.request() otherwise
        // inherits the earlier connect(timeout), enabling reply polling for every resize.
        // Keep channel-open/auth timeouts intact; change only this serialized request.
        int timeout = channel.connectTimeout;
        try {
            channel.connectTimeout = 0;
            channel.setPtySize(cols, rows, width, height);
        } finally {
            channel.connectTimeout = timeout;
        }
    }
}
