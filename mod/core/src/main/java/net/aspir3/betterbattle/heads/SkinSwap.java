package net.aspir3.betterbattle.heads;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gets a database head with Hypixel's /skull: puts the head's texture on your own skin for a moment,
 * runs /skull with your name, waits for the head to land in your inventory, then puts your skin back.
 *
 * Your old skin is written to heads/restore-<you>.json before anything changes, so if the game closes
 * halfway it is put back the next time the mod starts.
 */
public final class SkinSwap {
    /** What the swap needs from the game. Called on the main thread. */
    public interface Game {
        String accessToken();
        String playerName();
        /** Runs a command as if typed, without the slash. */
        void sendCommand(String command);
        /** Texture hashes of every player head in your inventory (null for heads without one). */
        List<String> skullHashes();
        void chat(String message);
        void debug(String line);
        /** Leaves and rejoins the current server; false when that can't be done here. */
        boolean reconnect();
        /** True while in a Build Battle game (so /rejoin is worth sending after reconnecting). */
        boolean inGame();
        /** The rejoin started by {@link #reconnect()} never got back on. */
        void reconnectFailed();
    }

    public enum Step { IDLE, CHANGING_SKIN, RECONNECTING, WAITING, ASKED, RESTORING }

    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    private static final String P = "§d§lBetter Battle §8» §7";
    /** Hypixel looks your skin up again after a short while; these are the gaps between tries. */
    private static final long[] TRY_AFTER_MS = {6000, 12000, 15000, 20000, 25000, 30000, 30000, 30000};
    private static final long ANSWER_WAIT_MS = 5000;
    private static final long RECONNECT_DELAY_MS = 1500, RECONNECT_TIMEOUT_MS = 60000;
    private static final long JOIN_WAIT_MS = 3000, REJOIN_GAME_WAIT_MS = 8000;

    private static final class Restore {
        String url, variant;
        boolean wasDefault;
    }

    private final File headsDir;
    private final Game game;
    private volatile Step step = Step.IDLE;
    private volatile String status = "";
    private HeadLibrary.Head head;
    private String targetHash;
    private int tries;
    private long nextAt, askedAt, reconnectAt;
    private volatile boolean reconnecting;
    private boolean rejoinGame;
    private Set<String> before = new HashSet<String>();
    private volatile boolean busyThread;

    public SkinSwap(File modDir, Game game) {
        this.headsDir = new File(modDir, "heads");
        this.game = game;
    }

    /** One backup per account, so switching accounts never puts one account's skin on another. */
    private File restoreFile() {
        String name = game.playerName();
        File f = new File(headsDir, "restore-" + (name == null ? "unknown" : name.toLowerCase(Locale.ROOT)) + ".json");
        File legacy = new File(headsDir, "restore.json");   // 0.2.1 kept a single backup
        if (!f.isFile() && legacy.isFile()) legacy.renameTo(f);
        return f;
    }

    public Step step() { return step; }
    public String status() { return status; }
    public boolean busy() { return step != Step.IDLE; }
    public HeadLibrary.Head head() { return head; }

    /** Puts back a skin left changed by a crash or a closed game. Safe to call any time nothing is running. */
    public void restoreIfLeftOver() {
        if (busy() || !restoreFile().isFile()) return;
        step = Step.RESTORING;
        status = "Putting your skin back...";
        background(new Runnable() {
            public void run() {
                restore(true);
            }
        });
    }

    /** A real player's head: /skull works with any username, no skin change needed. */
    public void playerHead(String name) {
        game.sendCommand("skull " + name);
        status = "Asked for " + name + "'s head";
    }

    public boolean start(final HeadLibrary.Head h) {
        if (busy()) return false;
        String token = game.accessToken();
        if (token == null || token.length() < 20) {
            game.chat(P + "§cCan't change your skin: this game isn't signed in to a Microsoft account.");
            return false;
        }
        head = h;
        targetHash = h.hash;
        tries = 0;
        step = Step.CHANGING_SKIN;
        status = "Putting " + h.name + " on your skin...";
        game.debug("heads: start " + h.name + " " + h.hash);
        final String tok = token;
        background(new Runnable() {
            public void run() {
                try {
                    saveCurrentSkin(tok);
                    String applied = applySkin(tok, h);
                    if (applied != null && !applied.equalsIgnoreCase(targetHash)) {
                        game.debug("heads: Mojang stored it as " + applied + " (asked for " + targetHash + ")");
                        targetHash = applied;
                    }
                    // Hypixel only reads your skin when you log in, so rejoin to make /skull see it.
                    reconnecting = false;
                    reconnectAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
                    status = "Skin changed. Rejoining so Hypixel sees it...";
                    step = Step.RECONNECTING;
                } catch (Exception e) {
                    game.debug("heads: skin change failed: " + e);
                    fail("Couldn't change your skin (" + e.getMessage() + ").");
                }
            }
        });
        return true;
    }

    public void cancel() {
        if (step == Step.RECONNECTING || step == Step.WAITING || step == Step.ASKED) finish("Cancelled. Putting your skin back...");
    }

    /** Call when the client has (re)joined a server. */
    public void onJoined() {
        if (step != Step.RECONNECTING || !reconnecting) return;
        reconnecting = false;
        if (rejoinGame) game.sendCommand("rejoin");
        tries = 0;
        nextAt = System.currentTimeMillis() + (rejoinGame ? REJOIN_GAME_WAIT_MS : JOIN_WAIT_MS);
        status = rejoinGame ? "Back on Hypixel. Rejoining your game..." : "Back on Hypixel. Asking for the head...";
        step = Step.WAITING;
    }

    /** Called every client tick on the main thread. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (step == Step.RECONNECTING && !reconnecting && now >= reconnectAt) {
            rejoinGame = game.inGame();
            if (game.reconnect()) {
                reconnecting = true;
                reconnectAt = now + RECONNECT_TIMEOUT_MS;
                game.debug("heads: rejoining" + (rejoinGame ? " (then /rejoin)" : ""));
            } else {
                nextAt = now;
                status = "Waiting for Hypixel to see the new skin...";
                step = Step.WAITING;
            }
        } else if (step == Step.RECONNECTING && reconnecting && now >= reconnectAt) {
            reconnecting = false;
            game.reconnectFailed();
            fail("Couldn't get back on the server, so your skin is going back.");
        } else if (step == Step.WAITING && now >= nextAt) {
            before = new HashSet<String>();
            for (String s : game.skullHashes()) if (s != null) before.add(s);
            tries++;
            game.debug("heads: /skull try " + tries);
            game.sendCommand("skull " + game.playerName());
            askedAt = now;
            status = "Asking for the head (try " + tries + " of " + TRY_AFTER_MS.length + ")...";
            step = Step.ASKED;
        } else if (step == Step.ASKED) {
            List<String> hashes = game.skullHashes();
            boolean gotSomething = false;
            for (String s : hashes) {
                if (s != null && s.equalsIgnoreCase(targetHash)) {
                    game.debug("heads: got it on try " + tries);
                    game.chat(P + "§aGot §f" + head.name + "§a. Putting your skin back...");
                    finish("Got it! Putting your skin back...");
                    return;
                }
                if (s == null || !before.contains(s)) gotSomething = true;
            }
            if (now - askedAt < ANSWER_WAIT_MS) return;
            if (gotSomething) game.debug("heads: try " + tries + " gave the old skin");
            if (tries >= TRY_AFTER_MS.length) {
                game.chat(P + "§cHypixel kept handing out your old skin. Your skin is going back; try again in a minute.");
                finish("Hypixel didn't pick up the new skin in time.");
                return;
            }
            nextAt = now + TRY_AFTER_MS[tries];
            status = "Hypixel still has your old skin. Trying again in " + (TRY_AFTER_MS[tries] / 1000) + "s...";
            step = Step.WAITING;
        }
    }

    private void finish(final String message) {
        status = message;
        step = Step.RESTORING;
        background(new Runnable() {
            public void run() {
                restore(false);
            }
        });
    }

    private void fail(String message) {
        game.chat(P + "§c" + message);
        finish(message);
    }

    private void background(final Runnable r) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                busyThread = true;
                try {
                    r.run();
                } finally {
                    busyThread = false;
                }
            }
        }, "Better Battle skin swap");
        t.setDaemon(true);
        t.start();
    }

    // ---- Mojang

    private void saveCurrentSkin(String token) throws Exception {
        if (restoreFile().isFile()) return;   // an earlier swap never finished: that file has the real skin
        JsonObject profile = request("GET", PROFILE, token, null, null).getAsJsonObject();
        Restore r = new Restore();
        r.wasDefault = true;
        if (profile.has("skins")) {
            for (JsonElement el : profile.getAsJsonArray("skins")) {
                JsonObject s = el.getAsJsonObject();
                if (!"ACTIVE".equalsIgnoreCase(str(s, "state"))) continue;
                r.url = str(s, "url");
                r.variant = str(s, "variant");
                r.wasDefault = r.url == null;
            }
        }
        restoreFile().getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(restoreFile()), StandardCharsets.UTF_8)) {
            new Gson().toJson(r, w);
        }
        game.debug("heads: saved skin " + (r.wasDefault ? "(default)" : r.url + " " + r.variant));
    }

    /** Returns the hash Mojang stored the new skin under. */
    private String applySkin(String token, HeadLibrary.Head h) throws Exception {
        JsonElement res;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("variant", "classic");
            body.addProperty("url", h.textureUrl());
            res = request("POST", PROFILE + "/skins", token, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception byUrl) {
            game.debug("heads: skin by URL failed (" + byUrl.getMessage() + "), uploading the file instead");
            res = upload(token, download(h.textureUrl()), "classic");
        }
        return activeHash(res);
    }

    private void restore(boolean leftOver) {
        try {
            if (!restoreFile().isFile()) {
                done(leftOver ? "" : "Skin back to normal.");
                return;
            }
            Restore r;
            try (Reader in = new InputStreamReader(new FileInputStream(restoreFile()), StandardCharsets.UTF_8)) {
                r = new Gson().fromJson(in, Restore.class);
            }
            String token = game.accessToken();
            if (r == null || r.wasDefault) {
                request("DELETE", PROFILE + "/skins/active", token, null, null);
            } else {
                String variant = r.variant == null ? "classic" : r.variant.toLowerCase(Locale.ROOT);
                try {
                    JsonObject body = new JsonObject();
                    body.addProperty("variant", variant);
                    body.addProperty("url", r.url);
                    request("POST", PROFILE + "/skins", token, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception byUrl) {
                    upload(token, download(r.url), variant);
                }
            }
            if (!restoreFile().delete()) restoreFile().deleteOnExit();
            game.debug("heads: skin restored");
            if (leftOver) game.chat(P + "Your skin was left changed last time. It's back to normal now.");
            done(leftOver ? "" : "Skin back to normal.");
        } catch (Exception e) {
            game.debug("heads: restore failed: " + e);
            game.chat(P + "§cCouldn't put your skin back yet (" + e.getMessage() + "). It'll try again next time you open Heads.");
            done("Couldn't put your skin back yet.");
        }
    }

    private void done(String message) {
        status = message;
        step = Step.IDLE;
    }

    private static String activeHash(JsonElement res) {
        if (res == null || !res.isJsonObject() || !res.getAsJsonObject().has("skins")) return null;
        for (JsonElement el : res.getAsJsonObject().getAsJsonArray("skins")) {
            JsonObject s = el.getAsJsonObject();
            if ("ACTIVE".equalsIgnoreCase(str(s, "state"))) return HeadLibrary.hashOf(str(s, "url"));
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static JsonElement request(String method, String url, String token, String type, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
        }
        return read(c);
    }

    private static JsonElement upload(String token, byte[] png, String variant) throws Exception {
        String boundary = "----bb" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + variant + "\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(png);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request("POST", PROFILE + "/skins", token, "multipart/form-data; boundary=" + boundary, b.toByteArray());
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url.replace("http://", "https://")).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != 200) throw new java.io.IOException("texture HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        }
    }

    private static JsonElement read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        if (code == 401) throw new java.io.IOException("Mojang said your login expired; restart the game");
        if (code == 429) throw new java.io.IOException("Mojang is rate limiting skin changes; wait a minute");
        if (code >= 400) throw new java.io.IOException("Mojang HTTP " + code + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(120, text.length()))));
        if (text.trim().isEmpty()) return null;
        return new JsonParser().parse(text);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
