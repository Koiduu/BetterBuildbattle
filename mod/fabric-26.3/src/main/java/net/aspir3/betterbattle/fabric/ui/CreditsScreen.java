package net.aspir3.betterbattle.fabric.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

public final class CreditsScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public CreditsScreen(Screen parent) {
        super(Component.literal("Credits"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String version = FabricLoader.getInstance().getModContainer("betterbattle")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        int cx = width / 2;
        int y = layout.getHeaderHeight() + 20;
        Component[] lines = {
            Component.literal("Better Battle").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            Component.literal("Version " + version).withStyle(ChatFormatting.DARK_GRAY),
            Component.empty(),
            Component.literal("Created by ").withStyle(ChatFormatting.GRAY).append(Component.literal("Koidu").withStyle(ChatFormatting.GOLD)),
            Component.literal("for the Aspiring guild and every Build Battle player").withStyle(ChatFormatting.GRAY),
            Component.empty(),
            Component.literal("Stats from the Hypixel API").withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("Not affiliated with Hypixel or Mojang").withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("battle.aspir3.net").withStyle(ChatFormatting.AQUA),
        };
        for (Component line : lines) {
            graphics.centeredText(font, line, cx, y, 0xFFFFFFFF);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
