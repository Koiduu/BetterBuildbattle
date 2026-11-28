package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/** Start a lobby: name, tag, size, region, an optional password and a short note. */
public final class StartLobbyScreen extends GuiScreen {
    private static final int TAG = 1, SIZE = 2, REGION = 3, START = 4, CANCEL = 5;
    private final GuiScreen parent;
    private GuiTextField name, note, password;
    private String tag = "teams", region = "any";
    private int size = 4, ticks;
    private boolean sent;
    private String startStatus = "";

    public StartLobbyScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private int top() { return Math.max(34, height / 2 - 80); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int x = width / 2 - 150, y = top();
        name = field(0, x, y, Lfg.NAME_MAX, name);
        if (name.getText().isEmpty() && !name.isFocused()) name.setFocused(true);
        buttonList.clear();
        buttonList.add(new GuiButton(TAG, x, y + 26, 96, 20, "Tag: " + Lfg.tagLabel(tag)));
        buttonList.add(new GuiButton(SIZE, x + 102, y + 26, 96, 20, "Players: " + size));
        buttonList.add(new GuiButton(REGION, x + 204, y + 26, 96, 20, Lfg.regionLabel(region)));
        note = field(1, x, y + 52, Lfg.NOTE_MAX, note);
        password = field(2, x, y + 78, Lfg.PASSWORD_MAX, password);
        buttonList.add(new GuiButton(START, x, y + 110, 146, 20, "Start lobby"));
        buttonList.add(new GuiButton(CANCEL, x + 154, y + 110, 146, 20, "Cancel"));
    }

    private GuiTextField field(int id, int x, int y, int max, GuiTextField old) {
        GuiTextField f = new GuiTextField(id, fontRendererObj, x, y, 300, 20);
        f.setMaxStringLength(max);
        if (old != null) {
            f.setText(old.getText());
            f.setFocused(old.isFocused());
        }
        return f;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case TAG:
                tag = Lfg.next(Lfg.TAGS, tag);
                size = Math.min(size, Lfg.maxSize(tag));
                initGui();
                break;
            case SIZE:
                size = size >= Lfg.maxSize(tag) ? 2 : size + 1;
                b.displayString = "Players: " + size;
                break;
            case REGION:
                region = Lfg.next(Lfg.REGIONS, region);
                b.displayString = Lfg.regionLabel(region);
                break;
            case START:
                start();
                break;
            default:
                mc.displayGuiScreen(parent);
        }
    }

    private void start() {
        String n = name.getText().trim().replaceAll("\\s+", " ");
        if (n.length() < 3) {
            startStatus = "§cGive the lobby a name of at least 3 characters.";
            return;
        }
        ApiClient.LobbySettings s = new ApiClient.LobbySettings();
        s.name = n;
        s.tag = tag;
        s.maxSize = size;
        s.region = region;
        s.note = note.getText().trim();
        sent = true;
        startStatus = "";
        LobbiesScreen.lfg().start(s, password.getText().isEmpty() ? null : password.getText());
    }

    @Override
    public void updateScreen() {
        ticks++;
        name.updateCursorCounter();
        note.updateCursorCounter();
        if (!sent) return;
        if (LobbiesScreen.lfg().hosting() != null) mc.displayGuiScreen(new MyLobbyScreen(parent));
        else if (LobbiesScreen.lfg().queue() != null) mc.displayGuiScreen(parent);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_TAB) {
            GuiTextField[] order = {name, note, password};
            int at = name.isFocused() ? 0 : note.isFocused() ? 1 : password.isFocused() ? 2 : -1;
            for (GuiTextField f : order) f.setFocused(false);
            order[(at + 1) % 3].setFocused(true);
            return;
        }
        if (key == Keyboard.KEY_RETURN) { start(); return; }
        if (name.textboxKeyTyped(c, key) || note.textboxKeyTyped(c, key) || password.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        name.mouseClicked(x, y, button);
        note.mouseClicked(x, y, button);
        password.mouseClicked(x, y, button);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Start a lobby", width / 2, 14, 0xFFFFFF);
        name.drawTextBox();
        note.drawTextBox();
        LobbiesScreen.masked(fontRendererObj, password, ticks);
        int x = width / 2 - 150, y = top();
        if (name.getText().isEmpty() && !name.isFocused()) fontRendererObj.drawString("Lobby name (3 to 32 characters)", x + 4, y + 6, 0x555555);
        if (note.getText().isEmpty() && !note.isFocused()) fontRendererObj.drawString("Note, e.g. \"English, chill\" (optional)", x + 4, y + 58, 0x555555);
        if (password.getText().isEmpty() && !password.isFocused()) fontRendererObj.drawString("Password (optional)", x + 4, y + 84, 0x555555);
        String st = !startStatus.isEmpty() ? startStatus : sent ? LobbiesScreen.lfg().status()
            : "Players ask to join; you accept and send the party invite yourself.";
        drawCenteredString(fontRendererObj, "§7" + (st == null ? "" : st), width / 2, height - 40, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "§8" + Lfg.NOTICE, width / 2, height - 22, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
