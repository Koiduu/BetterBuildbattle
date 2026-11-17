package net.aspir3.betterbattle.achieve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Daily goals, ordered by mode familiarity, remaining track progress and AP difficulty. */
public final class Recommender {
    private Recommender() {}

    public static List<Catalog.Entry> pick(List<Catalog.Entry> catalog, Collection<String> unlocked,
            Collection<String> pinned, Map<String, Long> counts, Map<String, Long> totals,
            long day, String player, String mode) {
        Map<String, Catalog.Entry> next = new HashMap<String, Catalog.Entry>();
        for (Catalog.Entry e : catalog) {
            if (e.track == null || e.threshold == null || unlocked.contains(e.id)) continue;
            Catalog.Entry previous = next.get(e.track);
            if (previous == null || e.threshold < previous.threshold) next.put(e.track, e);
        }
        long games = 0;
        for (Long count : counts.values()) games += Math.max(0, count);
        final Map<String, Double> scores = new HashMap<String, Double>();
        List<Catalog.Entry> candidates = new ArrayList<Catalog.Entry>();
        for (Catalog.Entry e : catalog) {
            if (e.secret || unlocked.contains(e.id) || pinned.contains(e.id) || !AchievementBrowser.inMode(e, mode)) continue;
            if (e.track != null && next.get(e.track) != e) continue;
            double familiarity = 0;
            for (Map.Entry<String, Long> count : counts.entrySet()) {
                if (AchievementBrowser.inMode(e, count.getKey())) familiarity += Math.max(0, count.getValue());
            }
            double progress = 0;
            Long total = totals.get(e.track);
            if (total != null && e.threshold != null && e.threshold > 0) progress = Math.min(1, Math.max(0, total / (double) e.threshold));
            Random random = new Random(day * 1000003L + (player == null ? 0 : player.hashCode()) * 31L + e.id.hashCode());
            double score = e.points * (1 - progress * 0.8) - progress * 20
                + (games == 0 ? 0 : 40 * (1 - familiarity / games)) + random.nextDouble() * 2;
            scores.put(e.id, score);
            candidates.add(e);
        }
        Collections.sort(candidates, new Comparator<Catalog.Entry>() {
            public int compare(Catalog.Entry a, Catalog.Entry b) {
                int result = Double.compare(scores.get(a.id), scores.get(b.id));
                return result == 0 ? a.id.compareTo(b.id) : result;
            }
        });
        return new ArrayList<Catalog.Entry>(candidates.subList(0, Math.min(5, candidates.size())));
    }
}
