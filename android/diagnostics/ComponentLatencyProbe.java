import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Manual desktop component probe. Never packaged in the APK; no socket/SSH connection. */
public final class ComponentLatencyProbe {
    private static volatile long bytesSent;
    private static volatile long updates;

    private static void report(String name, long[] ns) {
        Arrays.sort(ns);
        System.out.printf(Locale.ROOT, "%s n=%d median_us=%.3f p95_us=%.3f max_us=%.3f%n",
                name, ns.length, ns[(ns.length - 1) / 2] / 1000.0,
                ns[(int) Math.ceil(ns.length * .95) - 1] / 1000.0, ns[ns.length - 1] / 1000.0);
    }

    private static void measure(String name, Runnable action) {
        for (int i = 0; i < 5000; i++) action.run();
        long[] samples = new long[20000];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - start;
        }
        report(name, samples);
    }

    public static void main(String[] args) throws Exception {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
                TerminalSessionClient.class.getClassLoader(), new Class<?>[]{TerminalSessionClient.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("onTextChanged")) updates++;
                    return null;
                });
        TerminalSession screen = new TerminalSession(new TerminalSession.Transport() {
            public void send(byte[] data) { bytesSent += data.length; }
            public void resize(int c, int r, int w, int h) {}
        }, client);
        // Android KEYCODE_DEL=67. Test real KeyHandler mapping, not an assumed DEL mapping.
        String delete = KeyHandler.getCode(67, 0, false, false);
        if (!"\u007f".equals(delete)) throw new AssertionError("Unexpected delete mapping");
        measure("adapter_ascii", () -> screen.writeCodePoint(false, 120));
        measure("adapter_delete", () -> screen.write(delete));
        if (bytesSent != 50000) throw new AssertionError("Input count mismatch");
        byte[] echoErase = "x\b \b".getBytes(StandardCharsets.US_ASCII);
        for (int[] size : new int[][]{{51, 17}, {80, 24}}) {
            screen.updateSize(size[0], size[1], 8, 16);
            measure("parser_echo_erase_" + size[0] + "x" + size[1], () -> screen.append(echoErase));
            if (screen.getEmulator().getCursorCol() != 0) throw new AssertionError("Cursor mismatch");
        }
        if (updates != 50000) throw new AssertionError("Callback count mismatch");

        // Exercise the cached JSch getInputStream -> IO.put/flush -> read path only.
        // Construct only an unconnected channel: openChannel() requires a live session.
        // Reflection is isolated to this desktop fixture; no Session/socket is created.
        var constructor = ChannelShell.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Channel channel = constructor.newInstance();
        Method deliver = Channel.class.getDeclaredMethod("write", byte[].class, int.class, int.class);
        deliver.setAccessible(true);
        ArrayBlockingQueue<Long> received = new ArrayBlockingQueue<>(1);
        try (InputStream input = channel.getInputStream()) {
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < 1200; i++) {
                        if (input.read() != 120) throw new AssertionError("Pipe byte mismatch");
                        received.put(System.nanoTime());
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }, "component-pipe-reader");
            reader.setDaemon(true);
            reader.start();
            byte[] synthetic = {120};
            long[] samples = new long[1000];
            for (int i = 0; i < 1200; i++) {
                long start = System.nanoTime();
                deliver.invoke(channel, synthetic, 0, 1);
                Long end = received.poll(5, TimeUnit.SECONDS);
                if (end == null) throw new AssertionError("Pipe timeout");
                if (i >= 200) samples[i - 200] = end - start;
            }
            reader.join(5000);
            if (reader.isAlive()) throw new AssertionError("Reader did not finish");
            report("jsch_receive_pipe_desktop", samples);
        }
    }
}
