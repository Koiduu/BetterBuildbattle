package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.lfg.LfgService;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The lobby you host: who asked to join and who is in. Accept puts "/p invite name" in your chat box;
 * you press Enter to send it. Kick takes someone out, Block keeps them out for the rest of this lobby.
 */
public final class MyLobbyScreen extends Screen {
    private static final int ROW = 22;
    private final Screen parent;
    private EditBox rename;
    private String renameText;
    private String shownKey = "";

    public MyLobbyScreen(Screen parent) {
        super(Component.literal("My lobby"));
        this.parent = parent;
    }

    private static LfgService lfg() { return LobbiesScreen.lfg(); }

    @Override
    protected void init() {
        ApiClient.Lobby l = lfg().hosting();
        int w = Math.min(width - 20, 360), x = (width - w) / 2;
        if (l != null) {
            if (renameText == null) renameText = l.name;
            rename = new EditBox(font, x, 52, w - 80, 20, Component.literal("Name"));
            rename.setMaxLength(Lfg.NAME_MAX);
            rename.setValue(renameText);
            rename.setResponder(v -> renameText = v);
            addRenderableWidget(rename);
            addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
                String n = renameText.trim().replaceAll("\\s+", " ");
                if (n.length() >= 3 && !n.equals(l.name)) lfg().rename(n);
            }).bounds(x + w - 76, 52, 76, 20).build());

            int y = 96;
            int max = Math.max(1, (height - 96 - 60) / ROW);
            List<ApiClient.LobbyRequest> rs = lfg().requests();
            for (int i = 0; i < rs.size() && i < max; i++) {
                final ApiClient.LobbyRequest r = rs.get(i);
                int bx = x + w;
                bx -= 50;
                addRenderableWidget(Button.builder(Component.literal("Block"), b -> lfg().decide(r.id, "block"))
                    .tooltip(Tooltip.create(Component.literal("Keep " + r.name + " out of this lobby")))
                    .bounds(bx, y, 50, 20).build());
                if ("pending".equals(r.status)) {
                    bx -= 58;
                    addRenderableWidget(Button.builder(Component.literal("Decline"), b -> lfg().decide(r.id, "decline")).bounds(bx, y, 56, 20).build());
                    bx -= 58;
                    Button accept = Button.builder(Component.literal("Accept"), b -> lfg().decide(r.id, "accept"))
                        .tooltip(Tooltip.create(Component.literal("Puts /p invite " + r.name + " in your chat box. Press Enter to send it.")))
                        .bounds(bx, y, 56, 20).build();
                    accept.active = l.players < l.maxSize;
                    addRenderableWidget(accept);
                } else {
                    bx -= 58;
                    addRenderableWidget(Button.builder(Component.literal("Kick"), b -> lfg().decide(r.id, "kick")).bounds(bx, y, 56, 20).build());
                    bx -= 58;
                    addRenderableWidget(Button.builder(Component.literal("Invite"), b -> {
                        Compat.setScreen(minecraft, new net.minecraft.client.gui.screens.ChatScreen("/p invite " + r.name, false));
                    }).tooltip(Tooltip.create(Component.literal("Type /p invite " + r.name + " again")))
                        .bounds(bx, y, 56, 20).build());
                }
                y += ROW;
            }
            addRenderableWidget(Button.builder(Component.literal("Close lobby"), b -> lfg().close())
                .bounds(width / 2 - 154, height - 52, 100, 20).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 54, height - 52, 100, 20).build());
        shownKey = key();
    }

    private static String key() {
        ApiClient.Lobby l = lfg().hosting();
        StringBuilder k = new StringBuilder(l == null ? "-" : l.id + l.players);
        for (ApiClient.LobbyRequest r : lfg().requests()) k.append(r.id).append(r.status).append(',');
        return k.toString();
    }

    @Override
    public void tick() {
        super.tick();
        if (!key().equals(shownKey)) {
            boolean focused = rename != null && rename.isFocused();
            rebuildWidgets();
            if (focused && rename != null) setFocused(rename);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = Math.min(width - 20, 360), x = (width - w) / 2;
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        ApiClient.Lobby l = lfg().hosting();
        if (l == null) {
            g.centeredText(font, Component.literal("You're not hosting a lobby.").withStyle(ChatFormatting.GRAY), width / 2, 60, 0xFFFFFFFF);
            g.centeredText(font, Component.literal(lfg().status()).withStyle(ChatFormatting.GRAY), width / 2, 76, 0xFFFFFFFF);
            return;
        }
        int cx = LobbiesScreen.chip(g, font, l.tag, x, 30);
        if (l.locked) { LobbiesScreen.lock(g, cx + 3, 30); cx += 12; }
        long hours = Lfg.hoursLeft(l.expiresAt, System.currentTimeMillis());
        g.text(font, Component.literal(l.players + "/" + l.maxSize + " players · " + Lfg.regionLabel(l.region)
            + (hours >= 0 ? " · closes in " + hours + " h" : "")).withStyle(l.closesSoon ? ChatFormatting.GOLD : ChatFormatting.GRAY), cx + 6, 31, 0xFFFFFFFF, false);
        if (l.nameHidden) {
            g.text(font, Component.literal("Players see this lobby as \"Lobby\" after reports, until an admin looks at it.").withStyle(ChatFormatting.RED), x, 42, 0xFFFFFFFF, false);
        }
        g.text(font, Component.literal(lfg().requests().isEmpty() ? "No one has asked to join yet." : "Requests and players").withStyle(ChatFormatting.GRAY), x, 84, 0xFFFFFFFF, false);
        int y = 96;
        int max = Math.max(1, (height - 96 - 60) / ROW);
        List<ApiClient.LobbyRequest> rs = lfg().requests();
        for (int i = 0; i < rs.size() && i < max; i++) {
            ApiClient.LobbyRequest r = rs.get(i);
            g.fill(x - 4, y - 1, x + w + 4, y + ROW - 1, 0x55000000);
            g.text(font, r.name, x, y + 6, 0xFFFFFFFF, true);
            String st = "pending".equals(r.status) ? "wants to join" : "in the lobby";
            g.text(font, Component.literal(st).withStyle("pending".equals(r.status) ? ChatFormatting.YELLOW : ChatFormatting.GREEN), x + font.width(r.name) + 6, y + 6, 0xFFFFFFFF, false);
            y += ROW;
        }
        g.centeredText(font, Component.literal(lfg().status()).withStyle(ChatFormatting.GRAY), width / 2, height - 28 - 36, 0xFFFFFFFF);
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
