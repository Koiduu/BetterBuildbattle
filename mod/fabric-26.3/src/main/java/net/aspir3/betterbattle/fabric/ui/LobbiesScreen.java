package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.lfg.LfgService;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The lobby browser (/bb lfg): open lobbies with a coloured tag chip, name, host, region, player count and a
 * lock when there's a password. Search matches name, host and tag. Join asks the host; the host invites you
 * on Hypixel themselves.
 */
public final class LobbiesScreen extends Screen {
    static final int ROW = 24, TOP = 64;
    private final Screen parent;
    private EditBox search;
    private String query = "";
    private String tag;   // null = every tag
    private int page;
    private String shownKey = "";
    private int refreshTicks;

    public LobbiesScreen(Screen parent) {
        super(Component.literal("Lobbies"));
        this.parent = parent;
    }

    static LfgService lfg() { return BetterBattle.get().lfg(); }

    private int perPage() { return Math.max(1, (height - TOP - 64) / ROW); }

    private List<ApiClient.Lobby> shown() { return lfg().shown(tag, query); }

    @Override
    protected void init() {
        if (shownKey.isEmpty()) lfg().refresh();
        int w = Math.min(width - 20, 460), x = (width - w) / 2;
        search = new EditBox(font, x, 30, w - 188, 20, Component.literal("Search"));
        search.setHint(Component.literal("Search name, host or tag").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setValue(query);
        search.setResponder(v -> { query = v; page = 0; });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Tag: " + Lfg.tagLabel(tag)), b -> {
            tag = tag == null ? Lfg.TAGS.get(0) : Lfg.TAGS.indexOf(tag) == Lfg.TAGS.size() - 1 ? null : Lfg.next(Lfg.TAGS, tag);
            b.setMessage(Component.literal("Tag: " + Lfg.tagLabel(tag)));
            page = 0;
        }).bounds(x + w - 184, 30, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> lfg().refresh()).bounds(x + w - 90, 30, 90, 20).build());

        List<ApiClient.Lobby> list = shown();
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int y = TOP;
        for (int i = page * perPage(); i < list.size() && i < (page + 1) * perPage(); i++) {
            final ApiClient.Lobby l = list.get(i);
            int bx = x + w - 74;
            if (l.mine) {
                addRenderableWidget(Button.builder(Component.literal("Manage"), b -> Compat.setScreen(minecraft, new MyLobbyScreen(this)))
                    .bounds(bx, y + 2, 74, 20).build());
            } else {
                String mine = lfg().myRequest(l.id);
                boolean waiting = "pending".equals(mine) || "accepted".equals(mine);
                Button join = Button.builder(Component.literal(waiting ? ("accepted".equals(mine) ? "Leave" : "Cancel") : "Join"), b -> {
                    if (waiting) lfg().leave(l.id);
                    else if (l.locked) Compat.setScreen(minecraft, new LobbyPasswordScreen(this, l));
                    else lfg().join(l.id, null);
                }).bounds(bx + 22, y + 2, 52, 20).build();
                join.active = waiting || l.players < l.maxSize;
                addRenderableWidget(join);
                addRenderableWidget(Button.builder(Component.literal("!"), b -> lfg().report(l.id))
                    .tooltip(Tooltip.create(Component.literal("Report this lobby's name")))
                    .bounds(bx, y + 2, 20, 20).build());
            }
            y += ROW;
        }
        int by = height - 52;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuild(); }).bounds(x, by - 24, 20, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuild(); }).bounds(x + 70, by - 24, 20, 20).build()).active = page < pages - 1;
        }

        ApiClient.Lobby hosting = lfg().hosting();
        ApiClient.LobbyQueue queue = lfg().queue();
        if (hosting != null) {
            long n = lfg().pendingRequests();
            addRenderableWidget(Button.builder(Component.literal("My lobby" + (n > 0 ? " (" + n + ")" : "")),
                b -> Compat.setScreen(minecraft, new MyLobbyScreen(this))).bounds(width / 2 - 154, by, 100, 20).build());
        } else if (queue != null && queue.offerUntil != null) {
            addRenderableWidget(Button.builder(Component.literal("Claim slot"), b -> lfg().claim()).bounds(width / 2 - 154, by, 100, 20).build());
        } else if (queue != null) {
            addRenderableWidget(Button.builder(Component.literal("Leave line"), b -> lfg().leaveQueue()).bounds(width / 2 - 154, by, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Start lobby"), b -> Compat.setScreen(minecraft, new StartLobbyScreen(this)))
                .bounds(width / 2 - 154, by, 100, 20).build()).active = lfg().connected();
        }
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose())
            .bounds(width / 2 + 54, by, 100, 20).build());
        shownKey = key();
    }

    /** What the buttons depend on; they are rebuilt when it changes. */
    private String key() {
        StringBuilder k = new StringBuilder();
        k.append(page).append('|').append(tag).append('|').append(query).append('|');
        ApiClient.Lobby h = lfg().hosting();
        k.append(h == null ? "-" : h.id).append(lfg().pendingRequests()).append('|');
        ApiClient.LobbyQueue q = lfg().queue();
        k.append(q == null ? "-" : q.offerUntil == null ? "q" : "o").append('|');
        for (ApiClient.Lobby l : shown()) k.append(l.id).append(l.players).append(lfg().myRequest(l.id)).append(',');
        return k.toString();
    }

    private void rebuild() {
        boolean focused = search != null && search.isFocused();
        rebuildWidgets();
        if (focused) setFocused(search);
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 200) {   // every 10 seconds while open
            refreshTicks = 0;
            lfg().refresh();
        }
        if (!key().equals(shownKey)) rebuild();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = Math.min(width - 20, 460), x = (width - w) / 2;
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

        String line = statusLine();
        g.centeredText(font, Component.literal(line), width / 2, 52, 0xFFAAAAAA);

        List<ApiClient.Lobby> list = shown();
        if (list.isEmpty()) {
            String empty = !lfg().connected() ? "Join Hypixel to see lobbies." : lfg().loading() ? "Loading..." : "No open lobbies. Start one!";
            g.centeredText(font, Component.literal(empty).withStyle(ChatFormatting.GRAY), width / 2, TOP + 20, 0xFFFFFFFF);
        }
        int y = TOP;
        for (int i = page * perPage(); i < list.size() && i < (page + 1) * perPage(); i++) {
            ApiClient.Lobby l = list.get(i);
            g.fill(x - 4, y, x + w + 4, y + ROW - 1, l.mine ? 0x4433AA33 : 0x55000000);
            int cx = chip(g, font, l.tag, x, y + 3);
            if (l.locked) { lock(g, cx + 2, y + 3); cx += 12; }
            String name = font.plainSubstrByWidth(l.name, w - (cx - x) - 130);
            g.text(font, Component.literal(name).withStyle(l.nameHidden && !l.mine ? ChatFormatting.GRAY : ChatFormatting.WHITE), cx + 4, y + 4, 0xFFFFFFFF, true);
            String sub = "by " + l.host + " · " + Lfg.regionLabel(l.region) + (l.note == null || l.note.isEmpty() ? "" : " · " + l.note);
            g.text(font, Component.literal(font.plainSubstrByWidth(sub, w - 130)).withStyle(ChatFormatting.GRAY), x, y + 14, 0xFFFFFFFF, false);
            String count = l.players + "/" + l.maxSize;
            g.text(font, Component.literal(count).withStyle(l.players >= l.maxSize ? ChatFormatting.RED : ChatFormatting.GREEN),
                x + w - 82 - font.width(count), y + 8, 0xFFFFFFFF, true);
            y += ROW;
        }
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        if (pages > 1) g.centeredText(font, (page + 1) + " / " + pages, x + 45, height - 70, 0xFFAAAAAA);
        g.centeredText(font, Component.literal(lfg().notice()).withStyle(ChatFormatting.DARK_GRAY), width / 2, height - 22, 0xFFFFFFFF);
    }

    private static String statusLine() {
        LfgService s = lfg();
        ApiClient.LobbyQueue q = s.queue();
        if (q != null && q.offerUntil != null) return "§aA lobby slot is free for you! §7Claim it within " + Lfg.secondsLeft(q.offerUntil, System.currentTimeMillis()) + "s.";
        if (q != null) return "All lobbies are full, you're §f#" + q.position + "§7 in line.";
        String st = s.status();
        if (st != null && !st.isEmpty()) return st;
        ApiClient.Lobby h = s.hosting();
        if (h != null) return "You're hosting §f" + h.name + "§7 (" + h.players + "/" + h.maxSize + ").";
        return "Find players for your next game, or host your own lobby.";
    }

    /** A coloured tag chip; returns the x just after it. */
    static int chip(GuiGraphicsExtractor g, Font font, String tag, int x, int y) {
        String label = Lfg.tagLabel(tag);
        int w = font.width(label) + 6;
        g.fill(x, y, x + w, y + 10, 0xFF000000 | Lfg.tagColour(tag));
        g.text(font, label, x + 3, y + 1, 0xFF000000, false);
        return x + w;
    }

    /** A small padlock: a password is needed to join. */
    static void lock(GuiGraphicsExtractor g, int x, int y) {
        int c = 0xFFFFCC55;
        g.fill(x + 1, y, x + 6, y + 1, c);
        g.fill(x + 1, y, x + 2, y + 4, c);
        g.fill(x + 5, y, x + 6, y + 4, c);
        g.fill(x, y + 4, x + 7, y + 10, c);
        g.fill(x + 3, y + 6, x + 4, y + 8, 0xFF000000);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
