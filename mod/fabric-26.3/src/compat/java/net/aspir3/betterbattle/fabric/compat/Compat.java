package net.aspir3.betterbattle.fabric.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The calls that differ between Minecraft versions. Minecraft 26.2 and 26.3 share this API; the 26.1 build has its own copy. */
public final class Compat {
    private Compat() {}

    public static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    public static Screen screen(Minecraft client) { return client.gui.screen(); }

    public static boolean hudHidden(Minecraft client) { return client.gui.hud.isHidden(); }

    public static ToastManager toasts(Minecraft client) { return client.gui.toastManager(); }

    public static net.minecraft.client.multiplayer.chat.ChatListener chatListener(Minecraft client) { return client.gui.chatListener(); }

    public static Item wool(DyeColor c) { return Items.WOOL.pick(c); }

    public static Item terracottaItem(DyeColor c) { return Items.DYED_TERRACOTTA.pick(c); }

    public static Item bed(DyeColor c) { return Items.BED.pick(c); }

    public static Block terracotta(DyeColor c) { return Blocks.DYED_TERRACOTTA.pick(c); }
}
