package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Every achievement in 1.8.9's own scrolling list, unlocked first. */
public final class AchievementsScreen extends GuiScreen {
    private static final String[] FILTERS = {"All", "Unlocked", "Locked"};

    private final GuiScreen parent;
    private Map<String, Long> unlocked = Collections.emptyMap();
    private final List<Catalog.Entry> rows = new ArrayList<Catalog.Entry>();
    private int filter;
    private int ap;
    private AchievementList list;

    public AchievementsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        try {
            if (BetterBattle.get().db() != null) unlocked = BetterBattle.get().db().achievementTimes();
        } catch (Exception ignored) {
        }
        ap = 0;
        for (String id : unlocked.keySet()) {
            Catalog.Entry e = Catalog.get(id);
            if (e != null) ap += e.points;
        }
        fill();
        list = new AchievementList(mc);
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 154, height - 28, 150, 20, "Show: " + FILTERS[filter]));
        buttonList.add(new GuiButton(1, width / 2 + 4, height - 28, 150, 20, "Done"));
    }

    private void fill() {
        List<Catalog.Entry> done = new ArrayList<Catalog.Entry>();
        List<Catalog.Entry> todo = new ArrayList<Catalog.Entry>();
        for (Catalog.Entry e : Catalog.list()) {
            if (unlocked.containsKey(e.id)) done.add(e);
            else todo.add(e);
        }
        Collections.sort(done, new Comparator<Catalog.Entry>() {
            public int compare(Catalog.Entry a, Catalog.Entry b) {
                return Long.compare(unlocked.get(b.id), unlocked.get(a.id));
            }
        });
        rows.clear();
        if (filter != 2) rows.addAll(done);
        if (filter != 1) rows.addAll(todo);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            filter = (filter + 1) % FILTERS.length;
            button.displayString = "Show: " + FILTERS[filter];
            fill();
            list.scrollBy(-100000);
        } else {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        list.handleMouseInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        list.drawScreen(mouseX, mouseY, partialTicks);
        String title = "Achievements  §7" + unlocked.size() + "/" + Catalog.all().size() + "  ·  " + ap + " AP";
        drawCenteredString(fontRendererObj, title, width / 2, 16, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private final class AchievementList extends GuiSlot {
        AchievementList(Minecraft mc) {
            super(mc, AchievementsScreen.this.width, AchievementsScreen.this.height, 32, AchievementsScreen.this.height - 36, 28);
        }

        @Override
        protected int getSize() {
            return rows.size();
        }

        @Override
        protected void elementClicked(int index, boolean doubleClick, int mouseX, int mouseY) {
        }

        @Override
        protected boolean isSelected(int index) {
            return false;
        }

        @Override
        protected void drawBackground() {
        }

        @Override
        public int getListWidth() {
            return Math.min(320, width - 40);
        }

        @Override
        protected int getScrollBarX() {
            return width / 2 + getListWidth() / 2 + 6;
        }

        @Override
        protected void drawSlot(int index, int x, int y, int slotHeight, int mouseX, int mouseY) {
            Catalog.Entry e = rows.get(index);
            boolean done = unlocked.containsKey(e.id);
            boolean hidden = !done && e.secret;
            int w = getListWidth() - 4;

            ItemStack icon = Icons.of(e);
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            mc.getRenderItem().renderItemAndEffectIntoGUI(icon, x + 2, y + 4);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            if (!done) drawRect(x + 2, y + 4, x + 18, y + 20, 0xAA000000);

            String name = hidden ? "Secret achievement" : e.name;
            String desc = hidden ? "Keep playing to find out." : e.description;
            fontRendererObj.drawStringWithShadow(name, x + 24, y + 3, done ? 0xFFFF55 : 0xAAAAAA);
            String points = "+" + e.points + " AP";
            fontRendererObj.drawStringWithShadow(points, x + w - fontRendererObj.getStringWidth(points), y + 3, done ? 0xFFAA00 : 0x555555);
            String fitted = fontRendererObj.trimStringToWidth(desc, w - 28);
            if (fitted.length() < desc.length()) fitted = fontRendererObj.trimStringToWidth(desc, w - 36) + "...";
            fontRendererObj.drawString(fitted, x + 24, y + 14, 0x777777);
        }
    }
}
