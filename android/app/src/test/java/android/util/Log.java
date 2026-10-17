package android.util;

/** JVM-only logging shim for Sora's real grammar engine; no UI/IME mocking.
 * Parsing/tokenization remain the actual library. Warnings/errors fail the smoke test.
 */
public final class Log {
    public static int i(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int v(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { throw new AssertionError(tag + ": " + msg); }
    public static int w(String tag, String msg, Throwable error) { throw new AssertionError(tag + ": " + msg, error); }
    public static int e(String tag, String msg) { throw new AssertionError(tag + ": " + msg); }
    public static int e(String tag, String msg, Throwable error) { throw new AssertionError(tag + ": " + msg, error); }
}
