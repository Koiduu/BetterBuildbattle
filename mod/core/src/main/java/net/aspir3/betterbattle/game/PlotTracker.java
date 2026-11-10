package net.aspir3.betterbattle.game;

import java.util.HashMap;
import java.util.Map;

/**
 * Works out floor and backdrop changes from the block updates the server sends.
 *
 * On Hypixel a plot is 27x27 (41x41 in Teams) and 50 blocks tall. The floor is two layers;
 * the floor selector replaces the top one, which starts as white terracotta. Changing the
 * floor or the backdrop rewrites hundreds of blocks at once, so a burst of updates on the
 * floor layer means a new floor, and a burst just outside the plot means a new backdrop.
 */
public final class PlotTracker {
    public static final int PLOT_HEIGHT = 50;
    public static final String DEFAULT_FLOOR = "minecraft:white_terracotta";

    private static final long BURST_WINDOW_MS = 1500;
    private static final int BACKDROP_REACH = 30;   // how far outside the plot the backdrop can sit

    private final int minX, minZ, maxX, maxZ, floorY;
    private final int floorBurst;                   // updates needed to call it a floor change
    private final int backdropBurst;

    private String floor = DEFAULT_FLOOR;
    private int floorChanges, backdropChanges;
    private boolean reachedTop;

    private long floorWindowStart, backdropWindowStart;
    private final Map<String, Integer> floorCounts = new HashMap<String, Integer>();
    private int backdropCount;
    private boolean backdropCounted;

    /** Bounds are the plot's inside edges; floorY is the top floor layer. */
    public PlotTracker(int minX, int minZ, int maxX, int maxZ, int floorY) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.floorY = floorY;
        int area = (maxX - minX + 1) * (maxZ - minZ + 1);
        this.floorBurst = Math.max(50, area / 3);
        this.backdropBurst = Math.max(150, area / 2);
    }

    public boolean inside(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * The top buildable layer. The plot is 50 tall counting from just above the floor;
     * this is one lower than that on purpose, so being one block off still counts.
     */
    public int topY() {
        return floorY + PLOT_HEIGHT - 1;
    }

    /** The player's own placement. */
    public void onPlace(int y) {
        if (y >= topY()) reachedTop = true;
    }

    /** Any block change the server sends while building. */
    public void onBlockUpdate(int x, int y, int z, String blockId, long now) {
        if (y == floorY && inside(x, z)) {
            if (now - floorWindowStart > BURST_WINDOW_MS) {
                floorWindowStart = now;
                floorCounts.clear();
            }
            Integer n = floorCounts.get(blockId);
            int count = n == null ? 1 : n + 1;
            floorCounts.put(blockId, count);
            if (count == floorBurst && !blockId.equals(floor)) {
                floor = blockId;
                floorChanges++;
            }
            return;
        }

        if (!inside(x, z) && near(x, z)) {
            if (now - backdropWindowStart > BURST_WINDOW_MS) {
                backdropWindowStart = now;
                backdropCount = 0;
                backdropCounted = false;
            }
            if (++backdropCount >= backdropBurst && !backdropCounted) {
                backdropCounted = true;
                backdropChanges++;
            }
        }
    }

    private boolean near(int x, int z) {
        return x >= minX - BACKDROP_REACH && x <= maxX + BACKDROP_REACH
            && z >= minZ - BACKDROP_REACH && z <= maxZ + BACKDROP_REACH;
    }

    public String floor() { return floor; }
    public int floorChanges() { return floorChanges; }
    public int backdropChanges() { return backdropChanges; }
    public boolean reachedTop() { return reachedTop; }
}
