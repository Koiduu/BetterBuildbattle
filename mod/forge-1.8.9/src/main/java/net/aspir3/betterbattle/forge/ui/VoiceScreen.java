package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiYesNoCallback;
import org.lwjgl.input.Keyboard;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * Voice: type your teammate's or party's names and press Start. Everyone named has to press Voice too,
 * naming the same group, before the Discord channel is made.
 */
public final class VoiceScreen extends GuiScreen implements GuiYesNoCallback {
    private static final int START = 0, OPEN = 1, LEAVE = 2, DONE = 3;
    private final GuiScreen parent;
    private GuiTextField names;
    private GuiButton start, open, leave;
    private String typedError;
    private String openingUrl;

    public VoiceScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        BetterBattle.get().refreshVoice();
        Keyboard.enableRepeatEvents(true);
        int cx = width / 2;
        int y = height / 2 - 10;
        String typed = names == null ? "" : names.getText();
        names = new GuiTextField(10, fontRendererObj, cx - 150, y, 300, 20);
        names.setMaxStringLength(7 * 17);
        names.setText(typed);
        names.setFocused(true);
        y += 26;
        buttonList.clear();
        start = new GuiButton(START, cx - 154, y, 100, 20, "Start Voice");
        open = new GuiButton(OPEN, cx - 50, y, 100, 20, "Open in Discord");
        leave = new GuiButton(LEAVE, cx + 54, y, 100, 20, "Leave Voice");
        buttonList.add(start);
        buttonList.add(open);
        buttonList.add(leave);
        buttonList.add(new GuiButton(DONE, cx - 50, height - 28, 100, 20, parent == null ? "Done" : "Back"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void startVoice() {
        List<String> list = BetterBattle.voiceNames(names.getText());
        if (list == null || list.isEmpty() || list.size() > 7) {
            typedError = "Type 1 to 7 Minecraft names, separated by spaces.";
            return;
        }
        BetterBattle.get().voice(list);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case START:
                startVoice();
                break;
            case OPEN:
                ApiClient.Voice v = BetterBattle.get().voiceState();
                if (v != null && v.ready() && v.url != null) {
                    openingUrl = v.url;
                    mc.displayGuiScreen(new GuiConfirmOpenLink(this, v.url, 0, false));
                }
                break;
            case LEAVE:
                BetterBattle.get().leaveVoice();
                break;
            default:
                mc.displayGuiScreen(parent);
        }
    }

    /** Answer from the "open this link?" prompt. */
    @Override
    public void confirmClicked(boolean yes, int id) {
        if (yes && openingUrl != null) {
            try {
                Desktop.getDesktop().browse(new URI(openingUrl));
            } catch (Exception ignored) {
            }
        }
        mc.displayGuiScreen(this);
    }

    @Override
    protected void keyTyped(char c, int key) throws java.io.IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            startVoice();
            return;
        }
        if (names.textboxKeyTyped(c, key)) {
            typedError = null;
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws java.io.IOException {
        super.mouseClicked(x, y, button);
        names.mouseClicked(x, y, button);
    }

    @Override
    public void updateScreen() {
        names.updateCursorCounter();
        ApiClient.Voice v = BetterBattle.get().voiceState();
        leave.enabled = v != null && (v.ready() || v.waiting());
        open.enabled = v != null && v.ready() && v.url != null;
        start.enabled = BetterBattle.get().db() != null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2;
        drawCenteredString(fontRendererObj, "Voice Chat", cx, 15, 0xFFFFFF);
        int y = Math.max(34, height / 2 - 76);
        for (String line : new String[] {
            "Talk on the Better Battle Discord with your teammate or party.",
            "Everyone you name has to press Voice too, so nobody ends up in a call with strangers.",
            "Turn on voice chat and link Discord on your account page on the website first.",
        }) {
            drawCenteredString(fontRendererObj, line, cx, y, 0xAAAAAA);
            y += 12;
        }
        names.drawTextBox();
        if (names.getText().isEmpty() && !names.isFocused()) {
            fontRendererObj.drawString("Teammate or party names, like Koidu Aspir3", cx - 146, height / 2 - 4, 0x555555);
        }
        drawCenteredString(fontRendererObj, status(), cx, height / 2 + 46, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.",
            cx, height - 42, 0x555555);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String status() {
        BetterBattle core = BetterBattle.get();
        if (core.db() == null) return "§7Join Hypixel to use voice.";
        if (typedError != null) return "§c" + typedError;
        if (core.voiceProblem() != null) return "§c" + core.voiceProblem();
        ApiClient.Voice v = core.voiceState();
        if (v == null) return "§8Checking...";
        if (v.ready()) return "§aReady: §f" + v.label;
        if (v.waiting()) {
            StringBuilder who = new StringBuilder();
            if (v.waitingFor != null) for (String n : v.waitingFor) who.append(who.length() > 0 ? ", " : "").append(n);
            return "§eWaiting for §f" + (who.length() == 0 ? "the others" : who.toString()) + " §eto press Voice";
        }
        return "§7You're not in a voice channel.";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
