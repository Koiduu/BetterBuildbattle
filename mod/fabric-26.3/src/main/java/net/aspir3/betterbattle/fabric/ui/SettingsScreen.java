package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.fabric.ThemePack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * On/off switches for everything Better Battle adds, two per row. Saved to config.json right away.
 * When they don't all fit at the current GUI scale, they are split into pages with ‹ and › buttons.
 */
public final class SettingsScreen extends Screen {
    private static final int ROW = 22;          // button height 20 + spacing 2
    private final Screen parent;
    private HeaderAndFooterLayout layout;
    private int page, perPage;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Better Battle Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        int total = Config.TOGGLES.size();
        perPage = perPage();
        int pages = (total + perPage - 1) / perPage;
        page = Math.max(0, Math.min(page, pages - 1));
        layout.addTitleHeader(pages > 1 ? Component.literal(title.getString() + " (" + (page + 1) + "/" + pages + ")") : title, font);
        Config config = BetterBattle.get().config();
        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(2));
        LinearLayout row = null;
        int from = page * perPage, to = Math.min(total, from + perPage);
        for (int i = from; i < to; i++) {
            Config.Toggle t = Config.TOGGLES.get(i);
            if ((i - from) % 2 == 0) row = rows.addChild(LinearLayout.horizontal().spacing(8));
            row.addChild(Button.builder(label(t, config), b -> {
                t.flip(config);
                b.setMessage(label(t, config));
                if (t.is("uiTheme")) ThemePack.apply(minecraft, config.uiTheme);
            }).width(170).tooltip(Tooltip.create(Component.literal(t.help))).build());
        }
        Button done = Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).width(pages > 1 ? 120 : 200).build();
        if (pages > 1) {
            LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
            Button prev = footer.addChild(Button.builder(Component.literal("‹"), b -> { page--; rebuildWidgets(); }).width(36).build());
            footer.addChild(done);
            Button next = footer.addChild(Button.builder(Component.literal("›"), b -> { page++; rebuildWidgets(); }).width(36).build());
            prev.active = page > 0;
            next.active = page < pages - 1;
        } else {
            layout.addToFooter(done);
        }
        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
    }

    /** Toggles that fit on one page: whole rows between the header and footer, leaving room for the note line. */
    private int perPage() {
        int space = height - 33 - 33 - 14;
        int rowsFit = Math.max(2, (space + 2) / ROW);
        return Math.max(2, Math.min(Config.TOGGLES.size(), rowsFit * 2));
    }

    private static Component label(Config.Toggle t, Config c) {
        boolean on = t.get(c);
        return Component.literal(t.label + ": ").append(Component.literal(on ? "ON" : "OFF")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    @Override
    protected void repositionElements() {
        if (perPage() != perPage) rebuildWidgets();   // the window or GUI scale changed how many rows fit
        else layout.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, Component.literal("Turn anything off to play Build Battle normally. Your games are still saved.")
            .withStyle(ChatFormatting.DARK_GRAY), width / 2, layout.getHeaderHeight() + layout.getContentHeight() - 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
