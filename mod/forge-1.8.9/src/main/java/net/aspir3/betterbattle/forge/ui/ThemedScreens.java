package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 1.8.9 draws container titles ("Inventory", chest names, creative tab names) dark grey, which can't be read on
 * the Better Battle look's dark panels. While the look is on, the chest, inventory and creative screens open as
 * these copies, which only change the title colour. Everything else is the vanilla screen.
 */
public final class ThemedScreens {
    private static final int LABEL = Theme.LABEL & 0xFFFFFF;

    @SubscribeEvent
    public void onOpen(GuiOpenEvent event) {
        GuiScreen gui = event.gui;
        if (gui == null || !Theme.on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        Class<?> type = gui.getClass();
        if (type == GuiChest.class && ((GuiChest) gui).inventorySlots instanceof ContainerChest) {
            IInventory chest = ((ContainerChest) ((GuiChest) gui).inventorySlots).getLowerChestInventory();
            event.gui = new Chest(mc.thePlayer.inventory, chest);
        } else if (type == GuiContainerCreative.class) {
            event.gui = new Creative(mc.thePlayer);
        } else if (type == GuiInventory.class) {
            event.gui = new Inventory(mc.thePlayer);
        }
    }

    static final class Chest extends GuiChest {
        private final IInventory upper, lower;

        Chest(IInventory upper, IInventory lower) {
            super(upper, lower);
            this.upper = upper;
            this.lower = lower;
        }

        @Override
        protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
            fontRendererObj.drawString(lower.getDisplayName().getUnformattedText(), 8, 6, LABEL);
            fontRendererObj.drawString(upper.getDisplayName().getUnformattedText(), 8, ySize - 96 + 2, LABEL);
        }
    }

    static final class Creative extends GuiContainerCreative {
        Creative(EntityPlayer player) {
            super(player);
        }

        @Override
        protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
            int index = getSelectedTabIndex();
            CreativeTabs tab = index >= 0 && index < CreativeTabs.creativeTabArray.length ? CreativeTabs.creativeTabArray[index] : null;
            if (tab != null && tab.drawInForegroundOfTab()) {
                GlStateManager.disableBlend();
                fontRendererObj.drawString(I18n.format(tab.getTranslatedTabLabel()), 8, 6, LABEL);
            }
        }
    }

    static final class Inventory extends GuiInventory {
        Inventory(EntityPlayer player) {
            super(player);
        }

        @Override
        protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
            fontRendererObj.drawString(I18n.format("container.crafting"), 86, 16, LABEL);
        }
    }
}
