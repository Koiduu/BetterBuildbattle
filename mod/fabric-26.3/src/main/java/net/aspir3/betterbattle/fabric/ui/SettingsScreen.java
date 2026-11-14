package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** On/off switches for everything Better Battle adds, two per row. Saved to config.json right away. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public SettingsScreen(Screen parent) {
        super(Component.literal("Better Battle Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        Config config = BetterBattle.get().config();
        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(4));
        LinearLayout row = null;
        for (int i = 0; i < Config.TOGGLES.size(); i++) {
            Config.Toggle t = Config.TOGGLES.get(i);
            if (i % 2 == 0) row = rows.addChild(LinearLayout.horizontal().spacing(8));
            row.addChild(Button.builder(label(t, config), b -> {
                t.flip(config);
                b.setMessage(label(t, config));
            }).width(170).tooltip(Tooltip.create(Component.literal(t.help))).build());
        }
        layout.addToFooter(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private static Component label(Config.Toggle t, Config c) {
        boolean on = t.get(c);
        return Component.literal(t.label + ": ").append(Component.literal(on ? "ON" : "OFF")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, Component.literal("Turn anything off to play Build Battle normally. Your games are still saved.")
            .withStyle(ChatFormatting.DARK_GRAY), width / 2, layout.getHeaderHeight() + layout.getContentHeight() - 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
