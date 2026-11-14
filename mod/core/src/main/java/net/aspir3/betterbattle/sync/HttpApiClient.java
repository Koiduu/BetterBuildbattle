package net.aspir3.betterbattle.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.aspir3.betterbattle.game.MatchRecord;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Plain HttpURLConnection + Gson, so it works the same on Java 8 and Java 25. */
public final class HttpApiClient implements ApiClient {
    private final String base;
    private final String modVersion;
    private final String mcVersion;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private volatile String token;

    public HttpApiClient(String base, String modVersion, String mcVersion) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
    }

    public void setToken(String token) { this.token = token; }

    private static final class Challenge { String serverId; }
    private static final class Verified { String token; }

    public String challenge() throws Exception {
        return gson.fromJson(post("/auth/mod/challenge", "{}"), Challenge.class).serverId;
    }

    public String verify(String playerName, String serverId) throws Exception {
        Map<String, String> body = new HashMap<String, String>();
        body.put("name", playerName);
        body.put("serverId", serverId);
        return gson.fromJson(post("/auth/mod/verify", gson.toJson(body)), Verified.class).token;
    }

    public void link(String code) throws Exception {
        Map<String, String> body = new HashMap<String, String>();
        body.put("code", code);
        post("/mod/link", gson.toJson(body));
    }

    public SyncResult sync(List<MatchRecord> matches, List<String> achievements, List<String> seenPlayers) throws Exception {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MatchRecord m : matches) out.add(toJson(m));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("modVersion", modVersion);
        body.put("mcVersion", mcVersion);
        body.put("matches", out);
        body.put("localAchievements", achievements);
        body.put("seenPlayers", seenPlayers);
        return gson.fromJson(post("/mod/sync", gson.toJson(body)), SyncResult.class);
    }

    public Latest latest() throws Exception {
        String loader = "1.8.9".equals(mcVersion) ? "forge" : "fabric";
        try {
            return gson.fromJson(request("GET", "/mod/latest?loader=" + loader + "&mc=" + mcVersion, null), Latest.class);
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).startsWith("HTTP 404")) return null;   // older server, or no release yet
            throw e;
        }
    }

    public Bootstrap bootstrap() throws Exception {
        return gson.fromJson(request("GET", "/mod/bootstrap", null), Bootstrap.class);
    }

    private Map<String, Object> toJson(MatchRecord m) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        o.put("nonce", m.nonce);
        o.put("playedAt", iso.format(new Date(m.playedAt)));
        o.put("mode", m.mode);
        o.put("theme", m.theme);
        o.put("placement", m.placement);
        o.put("players", m.players);
        o.put("score", m.score);
        o.put("grade", m.grade);
        o.put("durationSec", Math.min(3600, Math.max(0, m.durationSec)));
        o.put("blocksPlaced", Math.min(20000, m.blocksPlaced));
        o.put("blocksBroken", Math.min(20000, m.blocksBroken));
        if (!m.palette.isEmpty()) o.put("palette", m.palette);
        if (!m.tags.isEmpty()) o.put("tags", m.tags);
        if ("gtb".equals(m.mode)) o.put("gtbCorrect", m.gtbCorrect);
        if (!m.spbRounds.isEmpty()) {
            List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
            for (net.aspir3.betterbattle.game.SpbTracker.Round r : m.spbRounds) {
                Map<String, Object> ro = new LinkedHashMap<String, Object>();
                ro.put("build", r.build);
                ro.put("name", r.name);
                ro.put("timeMs", r.timeMs);
                ro.put("accuracy", r.accuracy);
                ro.put("precision", r.precision);
                ro.put("perfect", r.perfect);
                ro.put("placed", r.placed);
                ro.put("broken", r.broken);
                rounds.add(ro);
            }
            o.put("spbRounds", rounds);
        }
        if (!m.votes.isEmpty()) {
            List<Map<String, Object>> votes = new ArrayList<Map<String, Object>>();
            for (MatchRecord.Vote v : m.votes) {
                Map<String, Object> vo = new LinkedHashMap<String, Object>();
                vo.put("round", v.round);
                vo.put("vote", v.vote);
                vo.put("builder", v.builder);
                vo.put("at", iso.format(new Date(v.at)));
                votes.add(vo);
            }
            o.put("votes", votes);
        }
        return o;
    }

    private String post(String path, String json) throws Exception {
        return request("POST", path, json);
    }

    private String request(String method, String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "BetterBattle/" + modVersion + " (Minecraft " + mcVersion + ")");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            try {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
        }
        int status = c.getResponseCode();
        if (status == 401) throw new Unauthorized();
        String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
        if (status >= 400) throw new Exception("HTTP " + status + ": " + text);
        return text;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
