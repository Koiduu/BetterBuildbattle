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

/** Asks for a locked lobby's password, then sends the join request. */
public final class LobbyPasswordScreen extends Screen {
    private final Screen parent;
    private final ApiClient.Lobby lobby;
    private EditBox password;
    private String text = "";

    public LobbyPasswordScreen(Screen parent, ApiClient.Lobby lobby) {
        super(Component.literal("Password for " + lobby.name));
        this.parent = parent;
        this.lobby = lobby;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 2 - 20;
        password = new EditBox(font, x, y, 200, 20, Component.literal("Password"));
        password.setHint(Component.literal("Password").withStyle(ChatFormatting.DARK_GRAY));
        password.setMaxLength(Lfg.PASSWORD_MAX);
        password.setValue(text);
        password.setResponder(v -> text = v);
        password.addFormatter((t, start) -> FormattedCharSequence.forward("*".repeat(t.length()), Style.EMPTY));
        addRenderableWidget(password);
        addRenderableWidget(Button.builder(Component.literal("Ask to join"), b -> {
            if (text.isEmpty()) return;
            LobbiesScreen.lfg().join(lobby.id, text);
            onClose();
        }).bounds(x, y + 26, 96, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x + 104, y + 26, 96, 20).build());
        setInitialFocus(password);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, height / 2 - 44, 0xFFFFFFFF);
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
