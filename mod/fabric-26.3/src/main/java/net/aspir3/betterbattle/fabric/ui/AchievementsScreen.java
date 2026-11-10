package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Every achievement, unlocked first. Scrolls like the vanilla lists. */
public final class AchievementsScreen extends Screen {
    private enum Filter { ALL, UNLOCKED, LOCKED }

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private Map<String, Long> unlocked = Collections.emptyMap();
    private Filter filter = Filter.ALL;
    private EntryList list;

    public AchievementsScreen(Screen parent) {
        super(Component.literal("Achievements"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        try {
            if (BetterBattle.get().db() != null) unlocked = BetterBattle.get().db().achievementTimes();
        } catch (Exception ignored) {
        }
        int ap = 0;
        for (String id : unlocked.keySet()) {
            Catalog.Entry e = Catalog.get(id);
            if (e != null) ap += e.points;
        }
        layout.addTitleHeader(Component.literal("Achievements  ")
            .append(Component.literal(unlocked.size() + "/" + Catalog.all().size() + "  ·  " + ap + " AP").withStyle(ChatFormatting.GRAY)), font);

        list = layout.addToContents(new EntryList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));
        fill();

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(filterLabel(), b -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            b.setMessage(filterLabel());
            fill();
        }).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private Component filterLabel() {
        String name = switch (filter) {
            case ALL -> "All";
            case UNLOCKED -> "Unlocked";
            case LOCKED -> "Locked";
        };
        return Component.literal("Show: " + name);
    }

    private void fill() {
        List<Catalog.Entry> done = new ArrayList<>();
        List<Catalog.Entry> todo = new ArrayList<>();
        for (Catalog.Entry e : Catalog.list()) {
            if (unlocked.containsKey(e.id)) done.add(e);
            else todo.add(e);
        }
        done.sort((a, b) -> Long.compare(unlocked.get(b.id), unlocked.get(a.id)));
        list.clear();
        if (filter != Filter.LOCKED) for (Catalog.Entry e : done) list.add(new Row(e, true));
        if (filter != Filter.UNLOCKED) for (Catalog.Entry e : todo) list.add(new Row(e, false));
        list.setScrollAmount(0);
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) list.updateSize(width, layout);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    final class EntryList extends AbstractSelectionList<Row> {
        EntryList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 28);
        }

        void clear() {
            clearEntries();
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, width - 40);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    final class Row extends AbstractSelectionList.Entry<Row> {
        private final Catalog.Entry achievement;
        private final boolean done;
        private final ItemStack icon;

        Row(Catalog.Entry achievement, boolean done) {
            this.achievement = achievement;
            this.done = done;
            this.icon = Icons.of(achievement);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            int w = getContentWidth();
            boolean hidden = !done && achievement.secret;

            graphics.fakeItem(icon, x + 2, y + 4);
            if (!done) graphics.fill(x + 2, y + 4, x + 18, y + 20, 0xAA000000);

            String name = hidden ? "Secret achievement" : achievement.name;
            String desc = hidden ? "Keep playing to find out." : achievement.description;
            graphics.text(font, Component.literal(name).withStyle(done ? ChatFormatting.YELLOW : ChatFormatting.GRAY), x + 24, y + 3, 0xFFFFFFFF, false);
            String points = "+" + achievement.points + " AP";
            graphics.text(font, Component.literal(points).withStyle(done ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY),
                x + w - font.width(points), y + 3, 0xFFFFFFFF, false);
            String fitted = font.plainSubstrByWidth(desc, w - 28);
            if (fitted.length() < desc.length()) fitted = font.plainSubstrByWidth(desc, w - 36) + "...";
            graphics.text(font, Component.literal(fitted).withStyle(ChatFormatting.DARK_GRAY), x + 24, y + 14, 0xFFFFFFFF, false);
        }
    }
}
