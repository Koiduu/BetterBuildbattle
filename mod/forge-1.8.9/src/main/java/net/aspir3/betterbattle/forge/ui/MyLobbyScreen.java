package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.lfg.LfgService;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The lobby you host: who asked to join and who is in. Accept puts "/p invite name" in your chat box;
 * you press Enter to send it. Kick takes someone out, Block keeps them out for the rest of this lobby.
 */
public final class MyLobbyScreen extends GuiScreen {
    private static final int ROW = 22, RENAME = 1, CLOSE = 2, BACK = 3, ROW_BASE = 100;
    private static final String[] ACTIONS = {"accept", "decline", "kick", "block", "invite"};
    private final GuiScreen parent;
    private GuiTextField rename;
    private String shownKey = "";
    private final List<ApiClient.LobbyRequest> rows = new ArrayList<ApiClient.LobbyRequest>();

    public MyLobbyScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private static LfgService lfg() { return LobbiesScreen.lfg(); }

    private int listWidth() { return Math.min(width - 20, 360); }

    private int maxRows() { return Math.max(1, (height - 96 - 60) / ROW); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        rows.clear();
        ApiClient.Lobby l = lfg().hosting();
        int w = listWidth(), x = (width - w) / 2;
        if (l != null) {
            GuiTextField old = rename;
            rename = new GuiTextField(0, fontRendererObj, x, 52, w - 80, 20);
            rename.setMaxStringLength(Lfg.NAME_MAX);
            rename.setText(old == null ? l.name : old.getText());
            if (old != null) rename.setFocused(old.isFocused());
            buttonList.add(new GuiButton(RENAME, x + w - 76, 52, 76, 20, "Rename"));
            int y = 96;
            List<ApiClient.LobbyRequest> rs = lfg().requests();
            for (int i = 0; i < rs.size() && i < maxRows(); i++) {
                ApiClient.LobbyRequest r = rs.get(i);
                int n = rows.size();
                rows.add(r);
                int bx = x + w - 50;
                buttonList.add(new GuiButton(id(n, 3), bx, y, 50, 20, "Block"));
                if ("pending".equals(r.status)) {
                    bx -= 58;
                    buttonList.add(new GuiButton(id(n, 1), bx, y, 56, 20, "Decline"));
                    bx -= 58;
                    GuiButton accept = new GuiButton(id(n, 0), bx, y, 56, 20, "Accept");
                    accept.enabled = l.players < l.maxSize;
                    buttonList.add(accept);
                } else {
                    bx -= 58;
                    buttonList.add(new GuiButton(id(n, 2), bx, y, 56, 20, "Kick"));
                    bx -= 58;
                    buttonList.add(new GuiButton(id(n, 4), bx, y, 56, 20, "Invite"));
                }
                y += ROW;
            }
            buttonList.add(new GuiButton(CLOSE, width / 2 - 154, height - 52, 100, 20, "Close lobby"));
        } else {
            rename = null;
        }
        buttonList.add(new GuiButton(BACK, width / 2 + 54, height - 52, 100, 20, "Back"));
        shownKey = key();
    }

    private static int id(int row, int action) { return ROW_BASE + row * ACTIONS.length + action; }

    private static String key() {
        ApiClient.Lobby l = lfg().hosting();
        StringBuilder k = new StringBuilder(l == null ? "-" : l.id + l.players);
        for (ApiClient.LobbyRequest r : lfg().requests()) k.append(r.id).append(r.status).append(',');
        return k.toString();
    }

    @Override
    public void updateScreen() {
        if (rename != null) rename.updateCursorCounter();
        if (!key().equals(shownKey)) initGui();
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BACK) { mc.displayGuiScreen(parent); return; }
        if (b.id == CLOSE) { lfg().close(); return; }
        ApiClient.Lobby l = lfg().hosting();
        if (b.id == RENAME) {
            String n = rename.getText().trim().replaceAll("\\s+", " ");
            if (l != null && n.length() >= 3 && !n.equals(l.name)) lfg().rename(n);
            return;
        }
        if (b.id < ROW_BASE) return;
        int row = (b.id - ROW_BASE) / ACTIONS.length;
        if (row >= rows.size()) return;
        ApiClient.LobbyRequest r = rows.get(row);
        String action = ACTIONS[(b.id - ROW_BASE) % ACTIONS.length];
        if ("invite".equals(action)) mc.displayGuiScreen(new GuiChat("/p invite " + r.name));
        else lfg().decide(r.id, action);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (rename != null && rename.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        if (rename != null) rename.mouseClicked(x, y, button);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int w = listWidth(), x = (width - w) / 2;
        drawCenteredString(fontRendererObj, "My lobby", width / 2, 12, 0xFFFFFF);
        ApiClient.Lobby l = lfg().hosting();
        if (l == null) {
            drawCenteredString(fontRendererObj, "§7You're not hosting a lobby.", width / 2, 60, 0xFFFFFF);
            drawCenteredString(fontRendererObj, "§7" + lfg().status(), width / 2, 76, 0xFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        int cx = LobbiesScreen.chip(fontRendererObj, l.tag, x, 30);
        if (l.locked) { LobbiesScreen.lock(cx + 3, 30); cx += 12; }
        long hours = Lfg.hoursLeft(l.expiresAt, System.currentTimeMillis());
        fontRendererObj.drawString(l.players + "/" + l.maxSize + " players · " + Lfg.regionLabel(l.region)
            + (hours >= 0 ? " · closes in " + hours + " h" : ""), cx + 6, 31, l.closesSoon ? 0xFFAA00 : 0xAAAAAA);
        if (l.nameHidden) fontRendererObj.drawString("Players see this lobby as \"Lobby\" after reports, until an admin looks at it.", x, 42, 0xFF5555);
        rename.drawTextBox();
        fontRendererObj.drawString(lfg().requests().isEmpty() ? "No one has asked to join yet." : "Requests and players", x, 84, 0xAAAAAA);
        int y = 96;
        for (ApiClient.LobbyRequest r : rows) {
            drawRect(x - 4, y - 1, x + w + 4, y + ROW - 1, 0x55000000);
            fontRendererObj.drawStringWithShadow(r.name, x, y + 6, 0xFFFFFF);
            boolean pending = "pending".equals(r.status);
            fontRendererObj.drawString(pending ? "wants to join" : "in the lobby", x + fontRendererObj.getStringWidth(r.name) + 6, y + 6, pending ? 0xFFFF55 : 0x55FF55);
            y += ROW;
        }
        drawCenteredString(fontRendererObj, "§7" + lfg().status(), width / 2, height - 64, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "§8" + Lfg.NOTICE, width / 2, height - 22, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
