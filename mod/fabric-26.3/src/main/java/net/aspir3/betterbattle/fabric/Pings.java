package net.aspir3.betterbattle.fabric;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.fabric.ui.PingWheelScreen;
import net.aspir3.betterbattle.pings.TeammatePings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Teams pings on Fabric: the wheel and block-ping keys, and the marker a teammate's block ping leaves. */
final class Pings {
    private Pings() {}

    /** How far away a block can be marked. */
    private static final double REACH = 64.0;

    private static int markX, markY, markZ;
    private static long markUntil;
    private static int ticks;

    /** Every client tick. Key presses outside a Teams game are used up and do nothing. */
    static void tick(Minecraft client, BetterBattle core, KeyMapping wheel, KeyMapping block) {
        boolean wheelPressed = false, blockPressed = false;
        while (wheel.consumeClick()) wheelPressed = true;
        while (block.consumeClick()) blockPressed = true;
        boolean active = core.pingsActive() && client.player != null && client.level != null;
        if (active && Compat.screen(client) == null) {
            if (wheelPressed) Compat.setScreen(client, new PingWheelScreen());
            else if (blockPressed) pingLookedAt(client, core);
        }
        if (!active) markUntil = 0;
        drawMarker(client);
    }

    private static void pingLookedAt(Minecraft client, BetterBattle core) {
        HitResult hit = client.player.pick(REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
            core.ping(TeammatePings.Kind.BLOCK, null);   // says to look at a block
            return;
        }
        BlockPos pos = b.getBlockPos();
        core.ping(TeammatePings.Kind.BLOCK, new int[] {pos.getX(), pos.getY(), pos.getZ()});
    }

    static void mark(int x, int y, int z, long untilMs) {
        markX = x;
        markY = y;
        markZ = z;
        markUntil = untilMs;
    }

    /** A rising column of sparks on the marked block, until the marker runs out. Only you see it. */
    private static void drawMarker(Minecraft client) {
        if (markUntil == 0 || client.level == null) return;
        if (System.currentTimeMillis() > markUntil) {
            markUntil = 0;
            return;
        }
        if (ticks++ % 2 != 0) return;
        double x = markX + 0.5, y = markY + 1.05, z = markZ + 0.5;
        client.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0.0, 0.08, 0.0);
        for (int i = 0; i < 4; i++) {
            double a = (ticks / 2 + i) * Math.PI / 2;
            client.level.addParticle(ParticleTypes.GLOW, x + Math.cos(a) * 0.55, y - 0.5, z + Math.sin(a) * 0.55, 0.0, 0.0, 0.0);
        }
    }
}
