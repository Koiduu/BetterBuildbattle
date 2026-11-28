package net.aspir3.betterbattle.fabric.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLScancode;

/** Minecraft 26.3 reads the keyboard through SDL. The 26.2 build has its own copy of this class (GLFW). */
public final class Keys {
    private Keys() {}

    /** The B key, rebindable in Controls. */
    public static KeyMapping menuKey(String name, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_B, category);
    }

    /** Teams: G opens the ping wheel, rebindable in Controls. */
    public static KeyMapping pingWheelKey(String name, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_G, category);
    }

    /** Teams: V marks the block you look at for your teammate, rebindable in Controls. */
    public static KeyMapping blockPingKey(String name, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_V, category);
    }
}
