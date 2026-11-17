package net.aspir3.betterbattle.fabric.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.achieve.AchievementBrowser;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** A scrolling ledger with a separate, scrollable page for each achievement. */
public final class AchievementsScreen extends Screen {
    private final Screen parent;
    private AchievementBrowser view;
    private AchievementBrowser.Show show = AchievementBrowser.Show.ALL;
    private int modeIndex;
    private EntryList list;
    private DetailList detail;
    private Catalog.Entry selected;
    private Button pin, back;
    private int listWidth, panelX, panelWidth, visibleCount, totalCount, recommendationCount;
    private boolean narrow, detailOpen;

    public AchievementsScreen(Screen parent) {
        super(Component.literal("Achievements"));
        this.parent = parent;
    }

    private Config config() { return BetterBattle.get().config(); }
    private String mode() { return AchievementBrowser.MODES.get(modeIndex); }

    @Override
    protected void init() {
        try {
            view = AchievementBrowser.load(BetterBattle.get().db(), config(), minecraft.getGameProfile().id().toString());
        } catch (Exception ignored) {
            view = new AchievementBrowser(minecraft.getGameProfile().id().toString(), java.time.LocalDate.now().toEpochDay());
        }
        narrow = width < 420;
        listWidth = narrow ? width - 16 : (width - 24) / 2;
        panelX = narrow ? 8 : listWidth + 16;
        panelWidth = narrow ? width - 16 : width - panelX - 8;
        list = addRenderableWidget(new EntryList(minecraft, listWidth, Math.max(36, height - 80), 40));
        list.setX(8);
        detail = addRenderableWidget(new DetailList(minecraft, panelWidth - 12, Math.max(20, height - 134), 70));
        detail.setX(panelX + 6);
        pin = addRenderableWidget(Button.builder(Component.literal("Pin"), b -> {
            if (selected != null) view.togglePin(selected, config());
            fill();
        }).bounds(panelX + 8, height - 62, narrow ? (panelWidth - 24) / 2 : panelWidth - 16, 20).build());
        back = addRenderableWidget(Button.builder(Component.literal("Back to list"), b -> {
            detailOpen = false;
            updateVisibility();
        }).bounds(panelX + panelWidth / 2 + 4, height - 62, (panelWidth - 24) / 2, 20).build());
        int gap = 6, buttonWidth = Math.min(150, (width - 28) / 3), start = (width - buttonWidth * 3 - gap * 2) / 2;
        addRenderableWidget(Button.builder(showLabel(), b -> cycleShow(b, 1)).bounds(start, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(modeLabel(), b -> cycleMode(b, 1)).bounds(start + buttonWidth + gap, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(start + (buttonWidth + gap) * 2, height - 28, buttonWidth, 20).build());
        fill();
    }

    private Component showLabel() { return Component.literal("Show: " + AchievementBrowser.modeName(show.name().toLowerCase(java.util.Locale.ROOT))); }
    private Component modeLabel() { return Component.literal("Mode: " + AchievementBrowser.modeName(mode())); }

    private void cycleShow(Button button, int direction) {
        show = AchievementBrowser.Show.values()[Math.floorMod(show.ordinal() + direction, AchievementBrowser.Show.values().length)];
        button.setMessage(showLabel());
        fill();
    }

    private void cycleMode(Button button, int direction) {
        modeIndex = Math.floorMod(modeIndex + direction, AchievementBrowser.MODES.size());
        button.setMessage(modeLabel());
        fill();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            for (var child : children()) if (child instanceof Button button && button.isMouseOver(event.x(), event.y())) {
                if (button.getMessage().getString().startsWith("Show:")) { cycleShow(button, -1); return true; }
                if (button.getMessage().getString().startsWith("Mode:")) { cycleMode(button, -1); return true; }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void fill() {
        String previous = selected == null ? null : selected.id;
        list.clear();
        List<Catalog.Entry> entries = view.entries(show, mode(), config());
        List<Catalog.Entry> recommendations = view.recommended(config(), mode());
        recommendationCount = show == AchievementBrowser.Show.ALL || show == AchievementBrowser.Show.RECOMMENDED ? recommendations.size() : 0;
        list.setY(recommendationCount > 0 ? 56 : 40);
        list.setHeight(height - 40 - list.getY());
        visibleCount = entries.size();
        totalCount = 0;
        for (Catalog.Entry e : Catalog.list()) if (AchievementBrowser.inMode(e, mode())) totalCount++;
        Row first = null, keep = null;
        for (Catalog.Entry e : entries) {
            int rank = (show == AchievementBrowser.Show.ALL || show == AchievementBrowser.Show.RECOMMENDED) ? recommendations.indexOf(e) + 1 : 0;
            Row row = new Row(e, rank);
            list.add(row);
            if (first == null) first = row;
            if (e.id.equals(previous)) keep = row;
        }
        list.setSelected(keep == null ? first : keep);
        selected = list.getSelected() == null ? null : list.getSelected().achievement;
        list.setScrollAmount(0);
        updateDetail();
    }

    private void updateDetail() {
        detail.clear();
        if (selected != null) {
            detail.addText(view.name(selected), 0xFFFFDD88);
            detail.addText("", 0xFFFFFFFF);
            detail.addText(view.description(selected), 0xFFE0E0E0);
            detail.addText("", 0xFFFFFFFF);
            detail.addText("+" + selected.points + " Aspiring Points", 0xFFFFAA00);
            if (!view.hidden(selected)) detail.addText(AchievementBrowser.modes(selected), 0xFFAAAAAA);
            detail.addText("", 0xFFFFFFFF);
            Long time = view.unlocked.get(selected.id);
            detail.addText(time == null ? "Locked" : "Unlocked " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(time)), time == null ? 0xFFAAAAAA : 0xFFAAFFAA);
            String progress = view.progress(selected);
            if (!progress.isEmpty()) detail.addText(progress, 0xFFFFFFFF);
        }
        detail.setScrollAmount(0);
        boolean pinned = selected != null && config().pinnedAchievements().contains(selected.id);
        boolean full = config().pinnedAchievements().size() >= 3;
        pin.setMessage(Component.literal(pinned ? "Unpin" : full ? "Pins full (3/3)" : "Pin achievement"));
        pin.active = selected != null && !view.hidden(selected) && !view.unlocked.containsKey(selected.id) && (pinned || !full);
        pin.setTooltip(Tooltip.create(Component.literal("Keep up to 3 goals on your HUD. Unpin one to make room.")));
        updateVisibility();
    }

    private void updateVisibility() {
        boolean panel = !narrow || detailOpen;
        list.visible = !narrow || !detailOpen;
        detail.visible = pin.visible = panel;
        back.visible = narrow && detailOpen;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(visibleCount + "/" + totalCount + " · " + AchievementBrowser.modeName(mode())), width / 2, 23, 0xFFAAAAAA);
        if (list.visible && recommendationCount > 0) {
            graphics.text(font, list.scrollAmount() < recommendationCount * 38 ? "Recommended for you" : "More achievements", 18, 44, 0xFF88BBAA, false);
        }
        if (!narrow || detailOpen) {
            graphics.fill(panelX, 40, panelX + panelWidth, 68, 0xB0000000);
            graphics.fill(panelX, 40, panelX + 2, 68, 0xFFFFAA00);
            if (selected != null) {
                graphics.fakeItem(view.hidden(selected) ? new ItemStack(Items.PAPER) : Icons.of(selected), panelX + 9, 46);
                graphics.text(font, "Achievement details", panelX + 31, 50, 0xFFAAAAAA, false);
            } else graphics.text(font, "No achievements here", panelX + 9, 50, 0xFFAAAAAA, false);
        }
        if (visibleCount == 0) graphics.centeredText(font, Component.literal("No achievements match these filters."), width / 2, height / 2, 0xFFAAAAAA);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    final class EntryList extends ObjectSelectionList<Row> {
        EntryList(Minecraft client, int width, int height, int top) { super(client, width, height, top, 38); }
        void clear() { clearEntries(); }
        void add(Row row) { addEntry(row); }
        @Override public int getRowWidth() { return listWidth - 16; }
        @Override public void setSelected(Row row) {
            super.setSelected(row);
            if (detail != null && pin != null && row != null) { selected = row.achievement; updateDetail(); }
        }
    }

    final class Row extends ObjectSelectionList.Entry<Row> {
        final Catalog.Entry achievement;
        final int rank;
        Row(Catalog.Entry achievement, int rank) { this.achievement = achievement; this.rank = rank; }
        @Override public Component getNarration() { return Component.literal(view.name(achievement) + ". " + view.description(achievement)); }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
            list.setSelected(this);
            if (narrow) { detailOpen = true; updateVisibility(); }
            return true;
        }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX(), y = getContentY(), w = getContentWidth();
            boolean done = view.unlocked.containsKey(achievement.id), hidden = view.hidden(achievement);
            if (list.getSelected() == this) graphics.fill(x, y, x + w, y + 36, 0x554D3C20);
            graphics.fakeItem(hidden ? new ItemStack(Items.PAPER) : Icons.of(achievement), x + 4, y + 9);
            String points = "+" + achievement.points + " AP";
            graphics.text(font, fit(view.name(achievement), w - 34 - font.width(points)), x + 26, y + 4, done ? 0xFFFFFF55 : 0xFFDDDDDD, false);
            graphics.text(font, points, x + w - font.width(points) - 3, y + 4, 0xFFFFAA00, false);
            graphics.text(font, fit(view.description(achievement), w - 32), x + 26, y + 15, 0xFF999999, false);
            String label = rank > 0 ? rank + ". Daily pick" : config().pinnedAchievements().contains(achievement.id) ? "Pinned" : done ? "Unlocked" : "Locked";
            graphics.text(font, fit(label, w - 32), x + 26, y + 26, rank > 0 ? 0xFF88BBAA : 0xFF777777, false);
            if (hovered) graphics.setTooltipForNextFrame(font.split(Component.literal(view.description(achievement)), Math.min(300, width - 30)), mouseX, mouseY);
        }
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(1, width - font.width("..."))) + "...";
    }

    final class DetailList extends ObjectSelectionList<DetailLine> {
        DetailList(Minecraft client, int width, int height, int top) { super(client, width, height, top, 12); }
        void clear() { clearEntries(); }
        void addText(String text, int color) {
            if (text.isEmpty()) addEntry(new DetailLine(FormattedCharSequence.EMPTY, color));
            else for (FormattedCharSequence line : font.split(Component.literal(text), getRowWidth() - 8)) addEntry(new DetailLine(line, color));
        }
        @Override public int getRowWidth() { return panelWidth - 28; }
        @Override protected boolean entriesCanBeSelected() { return false; }
    }

    final class DetailLine extends ObjectSelectionList.Entry<DetailLine> {
        private final FormattedCharSequence text;
        private final int color;
        DetailLine(FormattedCharSequence text, int color) { this.text = text; this.color = color; }
        @Override public Component getNarration() { return Component.empty(); }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            graphics.text(font, text, getContentX() + 4, getContentY() + 2, color, false);
        }
    }
}
