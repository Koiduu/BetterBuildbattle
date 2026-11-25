package net.aspir3.betterbattle.fabric.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Minecraft 26.1 still reads the keyboard through GLFW. The 26.3 build has its own copy of this class (SDL). */
public final class Keys {
    private Keys() {}

    /** The B key, rebindable in Controls. */
    public static KeyMapping menuKey(String name, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category);
    }
}
