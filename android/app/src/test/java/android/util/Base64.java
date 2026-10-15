package android.util;

/** JVM-only adapter for parsing real OSC payloads without an emulator/Robolectric.
 * Not part of the application APK. Android's platform Base64 is used on the phone. */
public final class Base64 {
    public static byte[] decode(String input, int flags) {
        return java.util.Base64.getMimeDecoder().decode(input);
    }
}
