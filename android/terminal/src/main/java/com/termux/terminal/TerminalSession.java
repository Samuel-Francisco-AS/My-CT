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
    public void append(byte[] data) {
        emulator.append(data, data.length);
        client.onTextChanged(this);
    }
    public TerminalEmulator getEmulator() { return emulator; }
    public boolean isRunning() { return running; }
    public String getTitle() { return emulator.getTitle(); }
    public void finish() { running = false; client.onSessionFinished(this); }
    public void reset() { emulator.reset(); client.onTextChanged(this); }
    @Override public void write(byte[] data, int offset, int count) {
        if (running) transport.send(java.util.Arrays.copyOfRange(data, offset, offset + count));
    }
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) return;
        write((prependEscape ? "\u001b" : "") + new String(Character.toChars(codePoint)));
    }
    @Override public void titleChanged(String oldTitle, String newTitle) { client.onTitleChanged(this); }
    // OSC clipboard requests from the remote host are deliberately ignored.
    @Override public void onCopyTextToClipboard(String text) {}
    @Override public void onPasteTextFromClipboard() {}
    @Override public void onBell() { client.onBell(this); }
    @Override public void onColorsChanged() { client.onColorsChanged(this); }
}
