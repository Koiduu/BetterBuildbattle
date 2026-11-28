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

    public String report(String note, String log) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("modVersion", modVersion);
        body.put("mcVersion", mcVersion);
        body.put("note", note.length() > 500 ? note.substring(0, 500) : note);
        body.put("log", log.length() > 300000 ? log.substring(log.length() - 300000) : log);   // the newest part
        Map<?, ?> res = gson.fromJson(post("/mod/report", gson.toJson(body)), Map.class);
        Object id = res == null ? null : res.get("id");
        return id == null ? null : String.valueOf(id instanceof Number ? ((Number) id).longValue() : id);
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

    /** The server nests all-time and period standings; this flattens them into Rank. */
    private static final class RawRank {
        Integer globalRank;
        int rankedPlayers;
        java.util.Map<String, Standing> allTime;
        java.util.Map<String, PeriodStanding> periods;
    }

    public Rank rank() throws Exception {
        RawRank raw = gson.fromJson(request("GET", "/mod/rank", null), RawRank.class);
        Rank r = new Rank();
        r.globalRank = raw.globalRank;
        r.rankedPlayers = raw.rankedPlayers;
        if (raw.allTime != null) {
            r.allTimeScore = raw.allTime.get("score");
            r.allTimeLevel = raw.allTime.get("level");
            r.allTimeWinRate = raw.allTime.get("winRate");
        }
        if (raw.periods != null) {
            r.day = raw.periods.get("day");
            r.week = raw.periods.get("week");
            r.month = raw.periods.get("month");
        }
        return r;
    }

    public Compare compare(String name) throws Exception {
        return gson.fromJson(request("GET", "/mod/compare?name=" + java.net.URLEncoder.encode(name, "UTF-8"), null), Compare.class);
    }

    public Voice voice(List<String> members) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("scope", "team");
        body.put("members", members);
        return gson.fromJson(voiceRequest("POST", gson.toJson(body)), Voice.class);
    }

    public Voice voiceStatus() throws Exception {
        return gson.fromJson(voiceRequest("GET", null), Voice.class);
    }

    public void leaveVoice() throws Exception {
        voiceRequest("DELETE", null);
    }

    private String voiceRequest(String method, String json) throws Exception {
        try {
            return request(method, "/mod/voice", json);
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).startsWith("HTTP 404")) throw new UnsupportedOperationException("voice");   // older server
            throw e;
        }
    }

    // ---- LFG lobbies

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    public LobbyList lfgList(String tag, String search) throws Exception {
        StringBuilder path = new StringBuilder("/mod/lfg");
        String sep = "?";
        if (tag != null) { path.append(sep).append("tag=").append(enc(tag)); sep = "&"; }
        if (search != null && !search.trim().isEmpty()) path.append(sep).append("q=").append(enc(search.trim()));
        return gson.fromJson(request("GET", path.toString(), null), LobbyList.class);
    }

    public LobbyStarted lfgStart(LobbySettings settings, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("settings", settings);
        body.put("password", password == null || password.isEmpty() ? null : password);
        return gson.fromJson(post("/mod/lfg", gson.toJson(body)), LobbyStarted.class);
    }

    public LobbyStarted lfgClaim() throws Exception {
        return gson.fromJson(post("/mod/lfg/claim", "{}"), LobbyStarted.class);
    }

    public void lfgLeaveQueue() throws Exception {
        post("/mod/lfg/queue/leave", "{}");
    }

    public void lfgClose(String lobbyId) throws Exception {
        post("/mod/lfg/" + enc(lobbyId) + "/close", "{}");
    }

    private static final class Renamed { Lobby lobby; }

    public Lobby lfgRename(String lobbyId, String name) throws Exception {
        Map<String, String> body = new HashMap<String, String>();
        body.put("name", name);
        return gson.fromJson(post("/mod/lfg/" + enc(lobbyId) + "/rename", gson.toJson(body)), Renamed.class).lobby;
    }

    public void lfgReport(String lobbyId) throws Exception {
        post("/mod/lfg/" + enc(lobbyId) + "/report", "{}");
    }

    public void lfgJoin(String lobbyId, String password) throws Exception {
        Map<String, String> body = new HashMap<String, String>();
        body.put("password", password == null || password.isEmpty() ? null : password);
        post("/mod/lfg/" + enc(lobbyId) + "/join", gson.toJson(body));
    }

    public void lfgLeave(String lobbyId) throws Exception {
        post("/mod/lfg/" + enc(lobbyId) + "/leave", "{}");
    }

    public Decided lfgDecide(long requestId, String action) throws Exception {
        Map<String, String> body = new HashMap<String, String>();
        body.put("action", action);
        return gson.fromJson(post("/mod/lfg/requests/" + requestId, gson.toJson(body)), Decided.class);
    }

    public LobbyPoll lfgPoll() throws Exception {
        return gson.fromJson(request("GET", "/mod/lfg/poll", null), LobbyPoll.class);
    }

    public net.aspir3.betterbattle.pings.TeammatePings.Poll pingPoll(String teammate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("teammate", teammate);
        return gson.fromJson(post("/mod/pings/poll", gson.toJson(body)), net.aspir3.betterbattle.pings.TeammatePings.Poll.class);
    }

    public void pingSend(String teammate, String kind, int[] block) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("teammate", teammate);
        body.put("kind", kind);
        if (block != null) {
            Map<String, Object> at = new LinkedHashMap<String, Object>();
            at.put("x", block[0]);
            at.put("y", block[1]);
            at.put("z", block[2]);
            body.put("block", at);
        }
        post("/mod/pings/send", gson.toJson(body));
    }

    public void pingLeave() throws Exception {
        post("/mod/pings/leave", "{}");
    }

    public Bootstrap bootstrap() throws Exception {
        return gson.fromJson(request("GET", "/mod/bootstrap", null), Bootstrap.class);
    }

    private static final class GtbAnswer { List<GtbFlag> flags; }

    public List<GtbFlag> gtbGuesses(List<net.aspir3.betterbattle.game.GtbGuessTracker.Guess> guesses) throws Exception {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (net.aspir3.betterbattle.game.GtbGuessTracker.Guess g : guesses) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("gameKey", g.gameKey);
            o.put("round", g.round);
            o.put("name", g.name);
            o.put("builder", g.builder);
            o.put("msSinceStart", Math.min(600000L, g.msSinceStart));
            o.put("msSinceReveal", g.msSinceReveal == null ? null : Math.min(600000L, g.msSinceReveal));
            o.put("revealed", g.revealed);
            o.put("wordLength", g.wordLength);
            o.put("word", g.word == null || g.word.length() > 40 ? null : g.word);
            out.add(o);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("modVersion", modVersion);
        body.put("mcVersion", mcVersion);
        body.put("guesses", out);
        try {
            GtbAnswer a = gson.fromJson(post("/mod/gtb/guesses", gson.toJson(body)), GtbAnswer.class);
            return a == null || a.flags == null ? new ArrayList<GtbFlag>() : a.flags;
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).startsWith("HTTP 404")) return new ArrayList<GtbFlag>();   // older server
            throw e;
        }
    }

    private static final class HeadsReply {
        List<net.aspir3.betterbattle.heads.MyHeads.Entry> heads;
    }

    public List<net.aspir3.betterbattle.heads.MyHeads.Entry> myHeads() throws Exception {
        HeadsReply r = gson.fromJson(request("GET", "/mod/heads", null), HeadsReply.class);
        return r == null || r.heads == null ? new java.util.ArrayList<net.aspir3.betterbattle.heads.MyHeads.Entry>() : r.heads;
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
                ro.put("name", r.difficulty == null ? r.name : r.name + " (" + r.difficulty + ")");
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
