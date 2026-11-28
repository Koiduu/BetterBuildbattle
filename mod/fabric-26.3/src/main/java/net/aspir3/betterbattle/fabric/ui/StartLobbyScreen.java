package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Start a lobby: name, tag, size, region, an optional password and a short note. */
public final class StartLobbyScreen extends Screen {
    private final Screen parent;
    private EditBox name, password, note;
    private String nameText = "", passwordText = "", noteText = "";
    private String tag = "teams", region = "any";
    private int size = 4;
    private boolean sent;
    private String startStatus = "";

    public StartLobbyScreen(Screen parent) {
        super(Component.literal("Start a lobby"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - 150, y = Math.max(34, height / 2 - 80);
        name = box(x, y, 300, "Lobby name (3 to 32 characters)", Lfg.NAME_MAX, nameText);
        name.setResponder(v -> nameText = v);

        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Tag: " + Lfg.tagLabel(tag)), b -> {
            tag = Lfg.next(Lfg.TAGS, tag);
            size = Math.min(size, Lfg.maxSize(tag));
            rebuildWidgets();
        }).bounds(x, y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Players: " + size), b -> {
            size = size >= Lfg.maxSize(tag) ? 2 : size + 1;
            b.setMessage(Component.literal("Players: " + size));
        }).bounds(x + 102, y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lfg.regionLabel(region)), b -> {
            region = Lfg.next(Lfg.REGIONS, region);
            b.setMessage(Component.literal(Lfg.regionLabel(region)));
        }).bounds(x + 204, y, 96, 20).build());

        y += 26;
        note = box(x, y, 300, "Note, e.g. \"English, chill\" (optional)", Lfg.NOTE_MAX, noteText);
        note.setResponder(v -> noteText = v);
        y += 26;
        password = box(x, y, 300, "Password (optional)", Lfg.PASSWORD_MAX, passwordText);
        password.setResponder(v -> passwordText = v);
        password.addFormatter((text, start) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));

        y += 32;
        addRenderableWidget(Button.builder(Component.literal("Start lobby"), b -> start()).bounds(x, y, 146, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x + 154, y, 146, 20).build());
        setInitialFocus(name);
    }

    private EditBox box(int x, int y, int w, String hint, int max, String value) {
        EditBox box = new EditBox(font, x, y, w, 20, Component.literal(hint));
        box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        box.setMaxLength(max);
        box.setValue(value);
        return addRenderableWidget(box);
    }

    private void start() {
        String n = nameText.trim().replaceAll("\\s+", " ");
        if (n.length() < 3) {
            startStatus = "§cGive the lobby a name of at least 3 characters.";
            return;
        }
        ApiClient.LobbySettings s = new ApiClient.LobbySettings();
        s.name = n;
        s.tag = tag;
        s.maxSize = size;
        s.region = region;
        s.note = noteText.trim();
        sent = true;
        startStatus = "";
        LobbiesScreen.lfg().start(s, passwordText.isEmpty() ? null : passwordText);
    }

    @Override
    public void tick() {
        super.tick();
        if (!sent) return;
        // Opened: manage it. In line: back to the list, which shows your place.
        if (LobbiesScreen.lfg().hosting() != null) Compat.setScreen(minecraft, new MyLobbyScreen(parent));
        else if (LobbiesScreen.lfg().queue() != null) onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        String st = !startStatus.isEmpty() ? startStatus : sent ? LobbiesScreen.lfg().status() : "Players ask to join; you accept and send the party invite yourself.";
        g.centeredText(font, Component.literal(st == null ? "" : st).withStyle(ChatFormatting.GRAY), width / 2, height - 40, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(Lfg.NOTICE).withStyle(ChatFormatting.DARK_GRAY), width / 2, height - 22, 0xFFFFFFFF);
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
