package net.aspir3.betterbattle.pings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Teammate pings in Build Battle Teams: short callouts ("Do head") and a marker on a block, sent through the
 * Better Battle server, never through Hypixel chat.
 *
 * Only ever on while you're building in a Teams game with a teammate the sidebar names. Never in Guess the
 * Build or any other mode, so it can't be used to pass answers. The server also checks that both players
 * named each other as teammates, and keeps nothing once a ping is read.
 */
public final class TeammatePings {
    public enum Kind {
        HEAD("head", "Do head"),
        BODY("body", "Do body"),
        FLOOR("floor", "Do the floor"),
        BACKGROUND("background", "Do the background"),
        COLOURS("colours", "Colours?"),
        HELP("help", "Need help"),
        DONE("done", "Done ✓"),
        BLOCK("block", "Look here");

        public final String id, label;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    /** The callouts on the wheel, clockwise from the top. */
    public static final List<Kind> WHEEL = Collections.unmodifiableList(Arrays.asList(
        Kind.HEAD, Kind.BODY, Kind.FLOOR, Kind.BACKGROUND, Kind.COLOURS, Kind.HELP, Kind.DONE));

    public static final long KIND_COOLDOWN_MS = 4000L, GLOBAL_COOLDOWN_MS = 1000L;
    static final long POLL_EVERY_MS = 2000L, RETRY_AFTER_MS = 10000L, OLD_SERVER_RETRY_MS = 5L * 60L * 1000L;
    /** How long a block marker stays up. */
    public static final long MARKER_MS = 6000L;

    /** One ping the server handed over. Block pings carry x, y, z. */
    public static final class Incoming {
        public String from, kind;
        public Integer x, y, z;
        public long at;
    }

    public static final class Poll {
        /** The teammate is a Better Battle player who named you back, so your pings reach them. */
        public boolean ready;
        public List<Incoming> pings = new ArrayList<Incoming>();
    }

    /** The server side. HttpApiClient in the game, a fake in the tests. */
    public interface Transport {
        Poll poll(String teammate) throws Exception;
        void send(String teammate, String kind, int[] block) throws Exception;
        void leave() throws Exception;
    }

    /** Called on the ping thread; the core moves it to the main thread. */
    public interface Listener {
        void received(String from, Kind kind, int[] block);
        void sent(String to, Kind kind);
        void notice(String message);
    }

    private final Transport transport;
    private final Listener listener;
    private final ScheduledExecutorService exec;

    private volatile String teammate;     // set while pings are allowed, otherwise null
    private volatile boolean ready;
    private volatile boolean joined;      // told the server about this teammate, so leave() is due when it ends
    private long nextPollAt;
    private boolean warnedNotReady;
    private final Map<Kind, Long> lastSent = new HashMap<Kind, Long>();
    private long lastAnySent;

    public TeammatePings(Transport transport, Listener listener) {
        this(transport, listener, Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BetterBattle-Pings");
                t.setDaemon(true);
                return t;
            }
        }));
        exec.scheduleWithFixedDelay(new Runnable() {
            public void run() { tick(System.currentTimeMillis()); }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    /** For the tests: no polling of its own, tick() is called by hand. exec runs the sends. */
    public TeammatePings(Transport transport, Listener listener, ScheduledExecutorService exec) {
        this.transport = transport;
        this.listener = listener;
        this.exec = exec;
    }

    /**
     * Whether pings may be used right now: the toggle is on, the mode is Teams (never Guess the Build), you're
     * building, and the sidebar names a teammate.
     */
    public static boolean allowed(boolean enabled, String mode, String state, String teammate) {
        return enabled && "teams".equals(mode) && "BUILDING".equals(state)
            && teammate != null && teammate.matches("[A-Za-z0-9_]{1,16}");
    }

    /** The core calls this whenever the game changes. Main thread. */
    public void update(boolean enabled, String mode, String state, String mate) {
        String next = allowed(enabled, mode, state, mate) ? mate : null;
        String before = teammate;
        if (next == null ? before == null : next.equalsIgnoreCase(before)) return;
        teammate = next;
        ready = false;
        synchronized (this) {
            nextPollAt = 0;
            warnedNotReady = false;
        }
    }

    public boolean active() { return teammate != null; }

    public String teammate() { return teammate; }

    /** Polls the server when due, and says goodbye to it once pings stop. Ping thread. */
    public synchronized void tick(long now) {
        String mate = teammate;
        if (mate == null) {
            if (joined) {
                joined = false;
                try { transport.leave(); } catch (Exception ignored) { }
            }
            return;
        }
        if (now < nextPollAt) return;
        try {
            Poll p = transport.poll(mate);
            joined = true;
            nextPollAt = now + POLL_EVERY_MS;
            if (!mate.equals(teammate)) return;   // the game moved on while asking
            ready = p != null && p.ready;
            if (p == null) return;
            for (Incoming in : p.pings) {
                Kind k = Kind.of(in.kind);
                if (k == null || in.from == null || !in.from.equalsIgnoreCase(mate)) continue;
                int[] block = k == Kind.BLOCK && in.x != null && in.y != null && in.z != null
                    ? new int[] {in.x, in.y, in.z} : null;
                if (k == Kind.BLOCK && block == null) continue;
                listener.received(in.from, k, block);
            }
        } catch (UnsupportedOperationException e) {
            nextPollAt = now + OLD_SERVER_RETRY_MS;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            nextPollAt = now + (m.startsWith("HTTP 404") ? OLD_SERVER_RETRY_MS : RETRY_AFTER_MS);
        }
    }

    /**
     * The player picked a callout. Returns why it can't go, for the player, or null once it's on its way.
     * Main thread; the request itself goes on the ping thread.
     */
    public String send(Kind kind, int[] block, long now) {
        final String mate = teammate;
        if (mate == null) return "Pings work only while building in Teams, with a teammate.";
        if (kind == Kind.BLOCK && block == null) return "Look at a block first.";
        String wait = cooldown(kind, now);
        if (wait != null) return wait;
        if (!ready) {
            synchronized (this) {
                if (!warnedNotReady) {
                    warnedNotReady = true;
                    return mate + " isn't using Better Battle pings, or hasn't turned them on.";
                }
            }
            return mate + " can't get pings yet.";
        }
        synchronized (this) {
            lastSent.put(kind, now);
            lastAnySent = now;
        }
        final Kind k = kind;
        final int[] at = block;
        exec.execute(new Runnable() {
            public void run() {
                try {
                    transport.send(mate, k.id, at);
                    listener.sent(mate, k);
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("cooldown")) listener.notice("Slow down a little.");
                    else if (m.contains("not_teammates")) listener.notice(mate + " can't get pings right now.");
                    else listener.notice("That ping didn't go through.");
                }
            }
        });
        return null;
    }

    /** Null when this callout may go now, otherwise how long to wait. */
    synchronized String cooldown(Kind kind, long now) {
        if (now - lastAnySent < GLOBAL_COOLDOWN_MS) return "Wait a moment.";
        Long last = lastSent.get(kind);
        if (last != null && now - last < KIND_COOLDOWN_MS) {
            long s = (KIND_COOLDOWN_MS - (now - last) + 999) / 1000;
            return "Wait " + s + "s to send \"" + kind.label + "\" again.";
        }
        return null;
    }

    boolean ready() { return ready; }

    /** Left the server. Tells the server to forget the teammate, then stops the thread. */
    public void shutdown() {
        teammate = null;
        try {
            exec.execute(new Runnable() {
                public void run() { tick(System.currentTimeMillis()); }
            });
            exec.shutdown();
        } catch (Exception ignored) {
        }
    }
}
