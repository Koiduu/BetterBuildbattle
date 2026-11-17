package net.aspir3.betterbattle.game;

public final class JumpTracker {
    private boolean grounded;

    public boolean update(boolean onGround, double velocityY, boolean jumpPressed,
                          boolean climbing, boolean inLiquid, boolean flying) {
        boolean eligible = !climbing && !inLiquid && !flying;
        boolean jumped = eligible && (onGround && jumpPressed || grounded && !onGround && velocityY > 0);
        grounded = onGround && eligible;
        return jumped;
    }

    public void reset() { grounded = false; }
}
