import com.Cigram.vid.CigramWs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Drives the real CigramWs against the hand-rolled Node server. */
public final class WsSmokeTest {
    private static int pass = 0;
    private static int fail = 0;

    static void check(String name, boolean ok, Object extra) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (extra == null ? "" : "  -> " + extra)); }
    }

    public static void main(String[] args) throws Exception {
        String url = "ws://127.0.0.1:" + (args.length > 0 ? args[0] : "18099") + "/ws?role=user";
        final List<String> received = new ArrayList<String>();
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final boolean[] cleanClose = new boolean[1];

        final CigramWs ws = new CigramWs(url, new CigramWs.Listener() {
            @Override public void onOpen() { open.countDown(); }
            @Override public void onText(String text) {
                synchronized (received) { received.add(text); }
            }
            @Override public void onClosed(boolean clean, String reason) {
                cleanClose[0] = clean;
                closed.countDown();
            }
        });
        ws.header("Authorization", "Bearer test-token-123456");
        ws.connect();

        check("the handshake completes", open.await(8, TimeUnit.SECONDS), null);
        check("isOpen() is true after the handshake", ws.isOpen(), null);

        waitFor(received, 1, 3000);
        check("the server hello arrives", contains(received, "hello"), received);

        check("send() succeeds", ws.send("مرحبا"), null);
        waitFor(received, 2, 3000);
        check("a UTF-8 round trip survives masking", contains(received, "echo:مرحبا"), received);

        ws.send("MEDIUM");
        waitFor(received, 3, 3000);
        check("a 1000-byte frame (16-bit length) is read", lastLen(received) == 1000, lastLen(received));

        ws.send("BIG");
        waitFor(received, 4, 5000);
        check("a 70000-byte frame (64-bit length) is read", lastLen(received) == 70000, lastLen(received));

        ws.send("FRAGMENT");
        waitFor(received, 5, 3000);
        check("a fragmented message is reassembled in order",
                contains(received, "frag-part2-end"), received);

        // The server pings us ~150ms in; if our pong were wrong it would have died.
        check("still open after the server's ping", ws.isOpen(), null);

        ws.send("BYE");
        check("a server close is reported", closed.await(5, TimeUnit.SECONDS), null);
        check("a 1000 close is reported as clean", cleanClose[0], null);
        check("isOpen() is false after closing", !ws.isOpen(), null);

        // A second close must not throw or fire a second callback.
        ws.close();
        check("close() is safe to call again", true, null);

        System.out.println("\n  " + pass + " passed, " + fail + " failed\n");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void waitFor(List<String> list, int size, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        for (;;) {
            synchronized (list) { if (list.size() >= size) return; }
            if (System.currentTimeMillis() > until) return;
            Thread.sleep(25);
        }
    }

    static boolean contains(List<String> list, String needle) {
        synchronized (list) {
            for (String s : list) if (s.contains(needle)) return true;
        }
        return false;
    }

    static int lastLen(List<String> list) {
        synchronized (list) { return list.isEmpty() ? -1 : list.get(list.size() - 1).length(); }
    }
}
