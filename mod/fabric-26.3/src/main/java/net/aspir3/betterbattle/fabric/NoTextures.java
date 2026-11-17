package net.aspir3.betterbattle.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import net.aspir3.betterbattle.BetterBattle;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** The "no textures" challenge. Block textures are whitened while they load, so switching it reloads them. */
public final class NoTextures {
    private static Boolean applied;

    private NoTextures() {}

    public static boolean on() {
        BetterBattle core = BetterBattle.get();
        return core != null && core.config().challengeNoTextures;
    }

    /** Keeps each pixel's transparency so leaves, glass and flowers keep their shape. */
    public static void whiten(Identifier name, NativeImage image) {
        if (image == null || name == null || !name.getPath().startsWith("block/") || !on()) return;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int c = image.getPixel(x, y);
                image.setPixel(x, y, (c & 0xFF000000) | 0x00FFFFFF);
            }
        }
    }

    /** Called every tick: reloads textures when the setting changes. */
    public static void tick(Minecraft client) {
        boolean now = on();
        if (applied == null) applied = now;   // the first load already used the setting
        if (now != applied) {
            applied = now;
            client.reloadResourcePacks();
        }
    }
}
