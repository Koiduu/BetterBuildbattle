package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.store.LocalDatabase;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.renderer.GlStateManager;

import java.awt.Desktop;
import java.net.URI;
import java.util.Map;

/** The main Better Battle screen, built from 1.8.9's own widgets and textures. */
public final class MenuScreen extends GuiScreen implements GuiYesNoCallback {
    private static final int ACHIEVEMENTS = 0, PROFILE = 1, SYNC = 2, CREDITS = 3, DONE = 4;

    private final GuiScreen parent;
    private boolean tracking;
    private long level, xpInto, xpNeeded;
    private int games, wins, unlocked, ap, pending;
    private String rank = "-";
    private String syncLine = "";

    public MenuScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        loadStats();
        int cx = width / 2;
        int y = height - 52;
        buttonList.clear();
        buttonList.add(new GuiButton(ACHIEVEMENTS, cx - 154, y, 100, 20, "Achievements"));
        buttonList.add(new GuiButton(PROFILE, cx - 50, y, 100, 20, "View Profile"));
        GuiButton sync = new GuiButton(SYNC, cx + 54, y, 100, 20, "Sync Now");
        sync.enabled = tracking;
        buttonList.add(sync);
        buttonList.add(new GuiButton(CREDITS, cx - 154, y + 24, 100, 20, "Credits"));
        buttonList.add(new GuiButton(DONE, cx - 50, y + 24, 204, 20, "Done"));
    }

    private void loadStats() {
        BetterBattle core = BetterBattle.get();
        LocalDatabase db = core.db();
        tracking = db != null;
        if (!tracking) return;
        try {
            LocalDatabase.LocalStats s = db.stats(null);
            games = s.games;
            wins = s.wins;
            Map<String, Long> got = db.achievementTimes();
            unlocked = got.size();
            ap = 0;
            for (String id : got.keySet()) {
                Catalog.Entry e = Catalog.get(id);
                if (e != null) ap += e.points;
            }
            pending = db.countPending();
            String r = db.getMeta("rank", null);
            String of = db.getMeta("ranked_players", null);
            rank = r == null ? "-" : "#" + String.format("%,d", Long.parseLong(r)) + (of == null ? "" : " of " + String.format("%,d", Long.parseLong(of)));
            long[] lv = core.levelProgress();
            level = lv[0];
            xpInto = lv[1];
            xpNeeded = lv[2];
            String last = db.getMeta("last_sync_at", null);
            if (pending > 0) syncLine = pending + (pending == 1 ? " game" : " games") + " waiting to upload";
            else if (last == null) syncLine = "Not synced yet";
            else syncLine = "Synced " + ago(Long.parseLong(last));
        } catch (Exception e) {
            syncLine = "Couldn't read your stats";
        }
    }

    private static String ago(long millis) {
        long s = (System.currentTimeMillis() - millis) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ACHIEVEMENTS:
                mc.displayGuiScreen(new AchievementsScreen(this));
                break;
            case PROFILE:
                String url = BetterBattle.get().config().website + "/p/" + mc.getSession().getUsername();
                mc.displayGuiScreen(new GuiConfirmOpenLink(this, url, 0, false));
                break;
            case SYNC:
                BetterBattle.get().syncNow();
                button.enabled = false;
                syncLine = "Syncing...";
                break;
            case CREDITS:
                mc.displayGuiScreen(new CreditsScreen(this));
                break;
            default:
                mc.displayGuiScreen(parent);
        }
    }

    /** Answer from the "open this link?" prompt. */
    @Override
    public void confirmClicked(boolean yes, int id) {
        if (yes) {
            try {
                Desktop.getDesktop().browse(new URI(BetterBattle.get().config().website + "/p/" + mc.getSession().getUsername()));
            } catch (Exception ignored) {
            }
        }
        mc.displayGuiScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2;
        drawCenteredString(fontRendererObj, "Better Battle", cx, 15, 0xFFFFFF);
        int y = Math.max(40, height / 2 - 70);

        if (!tracking) {
            drawCenteredString(fontRendererObj, "Join Hypixel to start tracking your games.", cx, y + 30, 0xAAAAAA);
            drawCenteredString(fontRendererObj, "Achievements and your level live here once you've played.", cx, y + 44, 0x555555);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        drawCenteredString(fontRendererObj, mc.getSession().getUsername(), cx, y, 0xFFFFFF);
        y += 18;

        // Level number and bar, exactly like the vanilla XP bar
        String lv = String.valueOf(level);
        int lx = cx - fontRendererObj.getStringWidth(lv) / 2;
        fontRendererObj.drawString(lv, lx + 1, y, 0);
        fontRendererObj.drawString(lv, lx - 1, y, 0);
        fontRendererObj.drawString(lv, lx, y + 1, 0);
        fontRendererObj.drawString(lv, lx, y - 1, 0);
        fontRendererObj.drawString(lv, lx, y, 8453920);
        y += 11;
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(Gui.icons);
        int barX = cx - 91;
        drawTexturedModalRect(barX, y, 0, 64, 182, 5);
        int fill = xpNeeded <= 0 ? 0 : (int) (xpInto * 183L / xpNeeded);
        if (fill > 0) drawTexturedModalRect(barX, y, 0, 69, fill, 5);
        y += 9;
        drawCenteredString(fontRendererObj, "Aspiring Level · " + String.format("%,d", xpInto) + " / " + String.format("%,d", xpNeeded) + " XP", cx, y, 0xAAAAAA);
        y += 22;

        int panelW = Math.min(300, width - 40);
        int px = cx - panelW / 2;
        int rowH = 14;
        String[][] rows = {
            {"Games logged", String.format("%,d", games), "Wins", String.format("%,d", wins)},
            {"Achievements", unlocked + " / " + Catalog.all().size(), "Aspiring Points", String.format("%,d", ap)},
            {"Global rank", rank, "", ""},
        };
        drawRect(px - 6, y - 5, px + panelW + 6, y + rows.length * rowH + 1, 0x66000000);
        int half = panelW / 2;
        for (String[] r : rows) {
            stat(r[0], r[1], px, half - 8, y);
            if (!r[2].isEmpty()) stat(r[2], r[3], px + half + 8, half - 8, y);
            y += rowH;
        }
        y += 12;
        String dot = (pending > 0 ? "§e" : "§a") + "● §7";
        drawCenteredString(fontRendererObj, dot + syncLine, cx, y, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void stat(String label, String value, int x, int w, int y) {
        fontRendererObj.drawString(label, x, y, 0xAAAAAA);
        fontRendererObj.drawStringWithShadow(value, x + w - fontRendererObj.getStringWidth(value), y, 0xFFFFFF);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
