package net.aspir3.betterbattle.forge;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.forge.ui.PingWheelScreen;
import net.aspir3.betterbattle.pings.TeammatePings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovingObjectPosition;

/** Teams pings on Forge 1.8.9: the wheel and block-ping keys, and the marker a teammate's block ping leaves. */
final class Pings {
    private Pings() {}

    /** How far away a block can be marked. */
    private static final double REACH = 64.0;

    private static int markX, markY, markZ;
    private static long markUntil;
    private static int ticks;

    /** Every client tick. Key presses outside a Teams game are used up and do nothing. */
    static void tick(Minecraft mc, BetterBattle core, KeyBinding wheel, KeyBinding block) {
        boolean wheelPressed = false, blockPressed = false;
        while (wheel.isPressed()) wheelPressed = true;
        while (block.isPressed()) blockPressed = true;
        boolean active = core.pingsActive() && mc.thePlayer != null && mc.theWorld != null;
        if (active && mc.currentScreen == null) {
            if (wheelPressed) mc.displayGuiScreen(new PingWheelScreen());
            else if (blockPressed) pingLookedAt(mc, core);
        }
        if (!active) markUntil = 0;
        drawMarker(mc);
    }

    private static void pingLookedAt(Minecraft mc, BetterBattle core) {
        MovingObjectPosition hit = mc.thePlayer.rayTrace(REACH, 1.0F);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.getBlockPos() == null) {
            core.ping(TeammatePings.Kind.BLOCK, null);   // says to look at a block
            return;
        }
        BlockPos pos = hit.getBlockPos();
        core.ping(TeammatePings.Kind.BLOCK, new int[] {pos.getX(), pos.getY(), pos.getZ()});
    }

    static void mark(int x, int y, int z, long untilMs) {
        markX = x;
        markY = y;
        markZ = z;
        markUntil = untilMs;
    }

    /** A rising column of sparks on the marked block, until the marker runs out. Only you see it. */
    private static void drawMarker(Minecraft mc) {
        if (markUntil == 0 || mc.theWorld == null) return;
        if (System.currentTimeMillis() > markUntil) {
            markUntil = 0;
            return;
        }
        if (ticks++ % 2 != 0) return;
        double x = markX + 0.5, y = markY + 1.05, z = markZ + 0.5;
        mc.theWorld.spawnParticle(EnumParticleTypes.FIREWORKS_SPARK, x, y, z, 0.0, 0.08, 0.0);
        for (int i = 0; i < 4; i++) {
            double a = (ticks / 2 + i) * Math.PI / 2;
            mc.theWorld.spawnParticle(EnumParticleTypes.VILLAGER_HAPPY, x + Math.cos(a) * 0.55, y - 0.5, z + Math.sin(a) * 0.55, 0.0, 0.0, 0.0);
        }
    }
}
