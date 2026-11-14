package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.pages.Pages;
import net.aspir3.betterbattle.store.LocalDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;

/** The main Better Battle screen: level, a few numbers, and the way to everything else. */
public final class MenuScreen extends Screen {
    private static final Identifier LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final Identifier XP_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier XP_PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 82);

    private boolean tracking;
    private long level, xpInto, xpNeeded;
    private int games, wins, unlocked, ap, pending;
    private String rank = "–";
    private String syncLine = "";

    public MenuScreen(Screen parent) {
        super(Component.literal("Better Battle"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadStats();
        layout.addTitleHeader(title, font);

        LinearLayout rows = layout.addToFooter(LinearLayout.vertical().spacing(4));
        LinearLayout top = rows.addChild(LinearLayout.horizontal().spacing(8));
        top.addChild(Button.builder(Component.literal("Achievements"), b -> minecraft.gui.setScreen(new AchievementsScreen(this))).width(100).build());
        top.addChild(Button.builder(Component.literal("View Profile"), b -> openProfile()).width(100).build());
        Button sync = top.addChild(Button.builder(Component.literal("Sync Now"), b -> {
            BetterBattle.get().syncNow();
            b.active = false;
            syncLine = "Syncing...";
        }).width(100).build());
        sync.active = tracking;

        BetterBattle core = BetterBattle.get();
        LinearLayout middle = rows.addChild(LinearLayout.horizontal().spacing(8));
        middle.addChild(Button.builder(Component.literal("What's New"), b -> minecraft.gui.setScreen(new TextPageScreen(this, BetterBattle.get().whatsNewPage()))).width(100).build());
        middle.addChild(Button.builder(Component.literal("Last Game"), b -> minecraft.gui.setScreen(new TextPageScreen(this, core.lastGamePage()))).width(100).build());
        middle.addChild(Button.builder(Component.literal("Weekly"), b -> minecraft.gui.setScreen(new TextPageScreen(this, core.weeklyPage()))).width(100).build());

        LinearLayout bottom = rows.addChild(LinearLayout.horizontal().spacing(8));
        bottom.addChild(Button.builder(Component.literal("Settings"), b -> minecraft.gui.setScreen(new SettingsScreen(this))).width(100).build());
        bottom.addChild(Button.builder(Component.literal("Credits"), b -> minecraft.gui.setScreen(new CreditsScreen(this))).width(100).build());
        bottom.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(100).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
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
            rank = r == null ? "–" : "#" + String.format("%,d", Long.parseLong(r)) + (of == null ? "" : " of " + String.format("%,d", Long.parseLong(of)));
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

    private void openProfile() {
        String name = minecraft.getGameProfile().name();
        ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(BetterBattle.get().config().website + "/p/" + name));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        int top = layout.getHeaderHeight();
        int h = layout.getContentHeight();
        graphics.blit(RenderPipelines.GUI_TEXTURED, LIST_BACKGROUND, 0, top, width, 0, width, h, 32, 32);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, 0, top - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, top + h, 0.0F, 0.0F, width, 2, 32, 2);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = layout.getHeaderHeight() + 14;

        if (!tracking) {
            graphics.centeredText(font, Component.literal("Join Hypixel to start tracking your games.").withStyle(ChatFormatting.GRAY), cx, y + 30, 0xFFFFFFFF);
            graphics.centeredText(font, Component.literal("Achievements and your level live here once you've played.").withStyle(ChatFormatting.DARK_GRAY), cx, y + 44, 0xFFFFFFFF);
            return;
        }

        graphics.centeredText(font, Component.literal(minecraft.getGameProfile().name()), cx, y, 0xFFFFFFFF);
        y += 18;

        // Level, drawn like the vanilla XP bar and number
        String lv = String.valueOf(level);
        int lx = cx - font.width(lv) / 2;
        graphics.text(font, lv, lx + 1, y, 0xFF000000, false);
        graphics.text(font, lv, lx - 1, y, 0xFF000000, false);
        graphics.text(font, lv, lx, y + 1, 0xFF000000, false);
        graphics.text(font, lv, lx, y - 1, 0xFF000000, false);
        graphics.text(font, lv, lx, y, 0xFF80FF20, false);
        y += 11;
        int barX = cx - 91;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BACKGROUND, barX, y, 182, 5);
        int fill = xpNeeded <= 0 ? 0 : (int) (xpInto * 183L / xpNeeded);
        if (fill > 0) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, XP_PROGRESS, 182, 5, 0, 0, barX, y, fill, 5);
        y += 9;
        graphics.centeredText(font, Component.literal("Aspiring Level · " + String.format("%,d", xpInto) + " / " + String.format("%,d", xpNeeded) + " XP")
            .withStyle(ChatFormatting.GRAY), cx, y, 0xFFFFFFFF);
        y += 22;

        // Two columns of numbers on a dark panel, like the vanilla statistics screen
        int panelW = Math.min(300, width - 40);
        int px = cx - panelW / 2;
        int rowH = 14;
        String[][] rows = {
            {"Games logged", String.format("%,d", games), "Wins", String.format("%,d", wins)},
            {"Achievements", unlocked + " / " + Catalog.all().size(), "Aspiring Points", String.format("%,d", ap)},
            {"Global rank", rank, "", ""},
        };
        graphics.fill(px - 6, y - 5, px + panelW + 6, y + rows.length * rowH + 1, 0x66000000);
        int half = panelW / 2;
        for (String[] r : rows) {
            stat(graphics, r[0], r[1], px, half - 8, y);
            if (!r[2].isEmpty()) stat(graphics, r[2], r[3], px + half + 8, half - 8, y);
            y += rowH;
        }
        y += 12;

        ChatFormatting colour = pending > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        graphics.centeredText(font, Component.literal("● ").withStyle(colour).append(Component.literal(syncLine).withStyle(ChatFormatting.GRAY)), cx, y, 0xFFFFFFFF);
    }

    private void stat(GuiGraphicsExtractor graphics, String label, String value, int x, int w, int y) {
        graphics.text(font, Component.literal(label).withStyle(ChatFormatting.GRAY), x, y, 0xFFFFFFFF, false);
        graphics.text(font, value, x + w - font.width(value), y, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
