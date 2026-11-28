package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.lfg.LfgService;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The lobby browser (/bb lfg) for 1.8.9: coloured tag chips, name, host, region, players and a lock for
 * password lobbies. Search matches name, host and tag. A locked lobby asks for its password right here.
 */
public final class LobbiesScreen extends GuiScreen {
    static final int ROW = 24, TOP = 64;
    private static final int TAG = 1, REFRESH = 2, PREV = 3, NEXT = 4, START = 5, BACK = 6,
        PW_JOIN = 7, PW_CANCEL = 8, ROW_BASE = 100;

    private final GuiScreen parent;
    private GuiTextField search, password;
    private String query = "";
    private String tag;
    private int page, refreshTicks;
    private String shownKey = "";
    private boolean loaded;
    private ApiClient.Lobby askingPassword;
    private final List<ApiClient.Lobby> rows = new ArrayList<ApiClient.Lobby>();

    public LobbiesScreen(GuiScreen parent) {
        this.parent = parent;
    }

    static LfgService lfg() { return BetterBattle.get().lfg(); }

    private int perPage() { return Math.max(1, (height - TOP - 64) / ROW); }

    private List<ApiClient.Lobby> shown() { return lfg().shown(tag, query); }

    private int listWidth() { return Math.min(width - 20, 460); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (!loaded) { loaded = true; lfg().refresh(); }
        int w = listWidth(), x = (width - w) / 2;
        buttonList.clear();
        rows.clear();
        boolean searchFocused = search != null && search.isFocused();
        search = new GuiTextField(0, fontRendererObj, x, 30, w - 188, 20);
        search.setMaxStringLength(40);
        search.setText(query);
        search.setFocused(searchFocused);

        if (askingPassword != null) {
            password = new GuiTextField(1, fontRendererObj, width / 2 - 100, height / 2 - 10, 200, 20);
            password.setMaxStringLength(Lfg.PASSWORD_MAX);
            password.setFocused(true);
            buttonList.add(new GuiButton(PW_JOIN, width / 2 - 100, height / 2 + 16, 96, 20, "Ask to join"));
            buttonList.add(new GuiButton(PW_CANCEL, width / 2 + 4, height / 2 + 16, 96, 20, "Cancel"));
            shownKey = key();
            return;
        }

        buttonList.add(new GuiButton(TAG, x + w - 184, 30, 90, 20, "Tag: " + Lfg.tagLabel(tag)));
        buttonList.add(new GuiButton(REFRESH, x + w - 90, 30, 90, 20, "Refresh"));

        List<ApiClient.Lobby> list = shown();
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        int y = TOP;
        for (int i = page * perPage(); i < list.size() && i < (page + 1) * perPage(); i++) {
            ApiClient.Lobby l = list.get(i);
            int n = rows.size();
            rows.add(l);
            int bx = x + w - 74;
            if (l.mine) {
                buttonList.add(new GuiButton(ROW_BASE + n * 2, bx, y + 2, 74, 20, "Manage"));
            } else {
                String mine = lfg().myRequest(l.id);
                boolean waiting = "pending".equals(mine) || "accepted".equals(mine);
                GuiButton join = new GuiButton(ROW_BASE + n * 2, bx + 22, y + 2, 52, 20,
                    waiting ? ("accepted".equals(mine) ? "Leave" : "Cancel") : "Join");
                join.enabled = waiting || l.players < l.maxSize;
                buttonList.add(join);
                buttonList.add(new GuiButton(ROW_BASE + n * 2 + 1, bx, y + 2, 20, 20, "!"));
            }
            y += ROW;
        }
        int by = height - 52;
        if (pages > 1) {
            GuiButton prev = new GuiButton(PREV, x, by - 24, 20, 20, "<");
            prev.enabled = page > 0;
            GuiButton next = new GuiButton(NEXT, x + 70, by - 24, 20, 20, ">");
            next.enabled = page < pages - 1;
            buttonList.add(prev);
            buttonList.add(next);
        }
        ApiClient.LobbyQueue q = lfg().queue();
        String main;
        if (lfg().hosting() != null) {
            long n = lfg().pendingRequests();
            main = "My lobby" + (n > 0 ? " (" + n + ")" : "");
        } else if (q != null && q.offerUntil != null) {
            main = "Claim slot";
        } else if (q != null) {
            main = "Leave line";
        } else {
            main = "Start lobby";
        }
        GuiButton start = new GuiButton(START, width / 2 - 154, by, 100, 20, main);
        start.enabled = lfg().connected();
        buttonList.add(start);
        buttonList.add(new GuiButton(BACK, width / 2 + 54, by, 100, 20, parent == null ? "Done" : "Back"));
        shownKey = key();
    }

    private String key() {
        StringBuilder k = new StringBuilder();
        k.append(page).append('|').append(tag).append('|').append(query).append('|').append(askingPassword != null).append('|');
        ApiClient.Lobby h = lfg().hosting();
        k.append(h == null ? "-" : h.id).append(lfg().pendingRequests()).append('|');
        ApiClient.LobbyQueue q = lfg().queue();
        k.append(q == null ? "-" : q.offerUntil == null ? "q" : "o").append('|');
        for (ApiClient.Lobby l : shown()) k.append(l.id).append(l.players).append(lfg().myRequest(l.id)).append(',');
        return k.toString();
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        if (password != null) password.updateCursorCounter();
        if (++refreshTicks >= 200) {
            refreshTicks = 0;
            lfg().refresh();
        }
        if (askingPassword == null && !key().equals(shownKey)) initGui();
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case TAG:
                tag = tag == null ? Lfg.TAGS.get(0) : Lfg.TAGS.indexOf(tag) == Lfg.TAGS.size() - 1 ? null : Lfg.next(Lfg.TAGS, tag);
                page = 0;
                initGui();
                return;
            case REFRESH: lfg().refresh(); return;
            case PREV: page = Math.max(0, page - 1); initGui(); return;
            case NEXT: page++; initGui(); return;
            case BACK: mc.displayGuiScreen(parent); return;
            case PW_CANCEL: askingPassword = null; password = null; initGui(); return;
            case PW_JOIN:
                if (password.getText().isEmpty()) return;
                lfg().join(askingPassword.id, password.getText());
                askingPassword = null;
                password = null;
                initGui();
                return;
            case START: {
                ApiClient.LobbyQueue q = lfg().queue();
                if (lfg().hosting() != null) mc.displayGuiScreen(new MyLobbyScreen(this));
                else if (q != null && q.offerUntil != null) lfg().claim();
                else if (q != null) lfg().leaveQueue();
                else mc.displayGuiScreen(new StartLobbyScreen(this));
                return;
            }
            default:
        }
        if (b.id < ROW_BASE) return;
        int n = (b.id - ROW_BASE) / 2;
        if (n >= rows.size()) return;
        ApiClient.Lobby l = rows.get(n);
        if ((b.id - ROW_BASE) % 2 == 1) {
            lfg().report(l.id);
        } else if (l.mine) {
            mc.displayGuiScreen(new MyLobbyScreen(this));
        } else {
            String mine = lfg().myRequest(l.id);
            if ("pending".equals(mine) || "accepted".equals(mine)) lfg().leave(l.id);
            else if (l.locked) { askingPassword = l; initGui(); }
            else lfg().join(l.id, null);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (password != null && password.isFocused()) {
            if (key == Keyboard.KEY_RETURN) { actionPerformed(new GuiButton(PW_JOIN, 0, 0, "")); return; }
            if (key == Keyboard.KEY_ESCAPE) { actionPerformed(new GuiButton(PW_CANCEL, 0, 0, "")); return; }
            password.textboxKeyTyped(c, key);
            return;
        }
        if (search.textboxKeyTyped(c, key)) {
            query = search.getText();
            page = 0;
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        if (askingPassword == null) search.mouseClicked(x, y, button);
        else if (password != null) password.mouseClicked(x, y, button);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int w = listWidth(), x = (width - w) / 2;
        drawCenteredString(fontRendererObj, "Lobbies", width / 2, 12, 0xFFFFFF);
        if (askingPassword != null) {
            drawCenteredString(fontRendererObj, "Password for " + askingPassword.name, width / 2, height / 2 - 26, 0xFFFFFF);
            masked(fontRendererObj, password, refreshTicks);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) fontRendererObj.drawString("Search name, host or tag", x + 4, 36, 0x555555);
        drawCenteredString(fontRendererObj, statusLine(), width / 2, 52, 0xAAAAAA);

        List<ApiClient.Lobby> list = shown();
        if (list.isEmpty()) {
            String empty = !lfg().connected() ? "Join Hypixel to see lobbies." : lfg().loading() ? "Loading..." : "No open lobbies. Start one!";
            drawCenteredString(fontRendererObj, "§7" + empty, width / 2, TOP + 20, 0xFFFFFF);
        }
        int y = TOP;
        for (int i = page * perPage(); i < list.size() && i < (page + 1) * perPage(); i++) {
            ApiClient.Lobby l = list.get(i);
            drawRect(x - 4, y, x + w + 4, y + ROW - 1, l.mine ? 0x4433AA33 : 0x55000000);
            int cx = chip(fontRendererObj, l.tag, x, y + 3);
            if (l.locked) { lock(cx + 2, y + 3); cx += 12; }
            String name = fontRendererObj.trimStringToWidth(l.name, w - (cx - x) - 130);
            fontRendererObj.drawStringWithShadow(name, cx + 4, y + 4, l.nameHidden && !l.mine ? 0xAAAAAA : 0xFFFFFF);
            String sub = "by " + l.host + " · " + Lfg.regionLabel(l.region) + (l.note == null || l.note.isEmpty() ? "" : " · " + l.note);
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(sub, w - 130), x, y + 14, 0xAAAAAA);
            String count = l.players + "/" + l.maxSize;
            fontRendererObj.drawStringWithShadow(count, x + w - 82 - fontRendererObj.getStringWidth(count), y + 8, l.players >= l.maxSize ? 0xFF5555 : 0x55FF55);
            y += ROW;
        }
        int pages = Math.max(1, (list.size() + perPage() - 1) / perPage());
        if (pages > 1) drawCenteredString(fontRendererObj, (page + 1) + " / " + pages, x + 45, height - 70, 0xAAAAAA);
        drawCenteredString(fontRendererObj, "§8" + lfg().notice(), width / 2, height - 22, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiButton b : buttonList) {
            if (b.id >= ROW_BASE && (b.id - ROW_BASE) % 2 == 1 && b.isMouseOver()) {
                drawCenteredString(fontRendererObj, "§7Report this lobby's name", width / 2, height - 34, 0xFFFFFF);
            }
        }
    }

    static String statusLine() {
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
    static int chip(FontRenderer font, String tag, int x, int y) {
        String label = Lfg.tagLabel(tag);
        int w = font.getStringWidth(label) + 6;
        Gui.drawRect(x, y, x + w, y + 10, 0xFF000000 | Lfg.tagColour(tag));
        font.drawString(label, x + 3, y + 1, 0x000000);
        return x + w;
    }

    /** A small padlock: a password is needed to join. */
    static void lock(int x, int y) {
        int c = 0xFFFFCC55;
        Gui.drawRect(x + 1, y, x + 6, y + 1, c);
        Gui.drawRect(x + 1, y, x + 2, y + 4, c);
        Gui.drawRect(x + 5, y, x + 6, y + 4, c);
        Gui.drawRect(x, y + 4, x + 7, y + 10, c);
        Gui.drawRect(x + 3, y + 6, x + 4, y + 8, 0xFF000000);
    }

    /** Draws a text field with its text shown as stars (1.8.9's field can't hide text itself). */
    static void masked(FontRenderer font, GuiTextField f, int ticks) {
        int x = f.xPosition, y = f.yPosition;
        Gui.drawRect(x - 1, y - 1, x + f.width + 1, y + f.height + 1, f.isFocused() ? 0xFFFFFFFF : 0xFFA0A0A0);
        Gui.drawRect(x, y, x + f.width, y + f.height, 0xFF000000);
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < f.getText().length(); i++) stars.append('*');
        String shown = stars.toString();
        font.drawStringWithShadow(shown + (f.isFocused() && ticks / 6 % 2 == 0 ? "_" : ""), x + 4, y + (f.height - 8) / 2, 0xE0E0E0);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
