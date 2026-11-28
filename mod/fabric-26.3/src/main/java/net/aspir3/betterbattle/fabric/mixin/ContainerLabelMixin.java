package net.aspir3.betterbattle.fabric.mixin;

import net.aspir3.betterbattle.Theme;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Container titles ("Inventory", chest and creative tab names) are drawn dark grey. With the Better Battle look
 * the panels are dark, so the titles are drawn light instead. require = 0: if a Minecraft update moves the
 * colour, the titles just stay grey instead of the game failing to start.
 */
@Mixin({AbstractContainerScreen.class, CreativeModeInventoryScreen.class})
public abstract class ContainerLabelMixin {
    @ModifyConstant(method = "extractLabels", constant = @Constant(intValue = 0xFF404040), require = 0)
    private int betterbattle$labelColour(int colour) {
        return Theme.on() ? Theme.LABEL : colour;
    }
}
