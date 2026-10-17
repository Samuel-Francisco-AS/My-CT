package com.termux.terminal;

/** My-CT transport adapter for the upstream Termux view. No Android subprocess or JNI. */
public final class TerminalSession extends TerminalOutput {
    public interface Transport {
        void send(byte[] data);
        void resize(int columns, int rows, int widthPixels, int heightPixels);
    }
    /** Only explicit Android UI actions may call these methods, never TerminalOutput OSC. */
    public interface UserClipboard {
        void copy(String text);
        void requestPaste();
    }
    private UserClipboard userClipboard;
    public void setUserClipboard(UserClipboard clipboard) { userClipboard = clipboard; }
    public void clearUserClipboard(UserClipboard expected) {
        if (userClipboard == expected) userClipboard = null;
    }
    public void copySelectionToClipboard(String text) {
        if (userClipboard != null) userClipboard.copy(text);
    }
    public void requestUserPaste() {
        if (userClipboard != null) userClipboard.requestPaste();
    }
    private int columns = 80, rows = 24, cellWidth, cellHeight;
    private final Transport transport;
    private final TerminalSessionClient client;
    private TerminalEmulator emulator;
    private boolean running = true;

    public TerminalSession(Transport transport, TerminalSessionClient client) {
        this.transport = transport;
        this.client = client;
        emulator = new TerminalEmulator(this, 80, 24, 0, 0, 2000, client);
    }
    public void updateSize(int columns, int rows, int cellWidth, int cellHeight) {
        if (columns == this.columns && rows == this.rows && cellWidth == this.cellWidth && cellHeight == this.cellHeight) return;
        this.columns = columns; this.rows = rows;
        this.cellWidth = cellWidth; this.cellHeight = cellHeight;
        emulator.resize(columns, rows, cellWidth, cellHeight);
        if (running) transport.resize(columns, rows, columns * cellWidth, rows * cellHeight);
    }
    public void requestPtyResize() {
        if (running) transport.resize(columns, rows, columns * cellWidth, rows * cellHeight);
    }
    public void append(byte[] data) { append(data, data.length); }
    /** Caller retains the buffer until this synchronous append returns (main thread only). */
    public void append(byte[] data, int count) {
        emulator.append(data, count);
        client.onTextChanged(this);
    }
    public TerminalEmulator getEmulator() { return emulator; }
    public boolean isRunning() { return running; }
    public String getTitle() { return emulator.getTitle(); }
    public void finish() { running = false; client.onSessionFinished(this); }
    public void reset() { emulator.reset(); client.onTextChanged(this); }
    @Override public void write(byte[] data, int offset, int count) {
        flushInputEvent();
        if (running) transport.send(java.util.Arrays.copyOfRange(data, offset, offset + count));
    }
    private java.io.ByteArrayOutputStream inputEvent;

    /** Only scopes one synchronous IME callback, never waits for a later event or frame. */
    public void beginInputEvent() {
        if (inputEvent != null) throw new IllegalStateException("Nested input event");
        inputEvent = new java.io.ByteArrayOutputStream();
    }
    public void endInputEvent() {
        flushInputEvent();
        inputEvent = null;
    }
    private void flushInputEvent() {
        if (inputEvent != null && inputEvent.size() > 0) {
            byte[] bytes = inputEvent.toByteArray();
            inputEvent.reset();
            if (running) transport.send(bytes);
        }
    }
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) return;
        if (!running) return;
        // One owned UTF-8 buffer, rather than char[] + Strings + encoding + defensive copy.
        int length = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
        byte[] bytes = new byte[length + (prependEscape ? 1 : 0)];
        int i = 0;
        if (prependEscape) bytes[i++] = 27;
        if (length == 1) bytes[i] = (byte) codePoint;
        else {
            if (length == 2) bytes[i++] = (byte) (0xC0 | (codePoint >> 6));
            else {
                if (length == 3) bytes[i++] = (byte) (0xE0 | (codePoint >> 12));
                else {
                    bytes[i++] = (byte) (0xF0 | (codePoint >> 18));
                    bytes[i++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                }
                bytes[i++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            }
            bytes[i] = (byte) (0x80 | (codePoint & 0x3F));
        }
        if (inputEvent != null && !prependEscape && codePoint >= 32 && codePoint != 127) {
            inputEvent.write(bytes, 0, bytes.length);
            // Bound temporary memory even for a large IME commit. No timer or deferred flush.
            if (inputEvent.size() >= 4096) flushInputEvent();
        } else {
            flushInputEvent();
            transport.send(bytes);
        }
    }
    @Override public void titleChanged(String oldTitle, String newTitle) { client.onTitleChanged(this); }
    // OSC clipboard requests from the remote host are deliberately ignored.
    @Override public void onCopyTextToClipboard(String text) {}
    @Override public void onPasteTextFromClipboard() {}
    @Override public void onBell() { client.onBell(this); }
    @Override public void onColorsChanged() { client.onColorsChanged(this); }
}
