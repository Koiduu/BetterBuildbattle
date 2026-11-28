package net.aspir3.betterbattle;

/**
 * The colours of battle.aspir3.net (web/style.css), as ARGB for the mod's own screens.
 * Used while the "Better Battle look" setting is on; the built-in UI pack uses the same palette.
 */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xFF17171A;
    public static final int CARD = 0xFF1F1F24;
    public static final int CARD_2 = 0xFF141416;
    public static final int CARD_HI = 0xFF34343C;
    public static final int CARD_LO = 0xFF121215;
    public static final int LINE = 0xFF3A3A42;
    public static final int EDGE = 0xFF000000;

    public static final int TEXT = 0xFFF0F0F0;
    public static final int SOFT = 0xFFC6C6C6;
    public static final int MUTED = 0xFF9D9DA6;

    public static final int PINK = 0xFFFF66AA;
    public static final int PINK_TEXT = 0xFFFF8FC0;
    public static final int PINK_HI = 0xFFFF9CC8;
    public static final int PINK_LO = 0xFF9A2F61;
    public static final int GOLD = 0xFFFFAA00;
    public static final int GREEN = 0xFF55FF55;

    /** Container titles ("Inventory", chest names): vanilla draws them dark grey, unreadable on dark panels. */
    public static final int LABEL = 0xFFD8D8DE;

    public static boolean on() {
        BetterBattle core = BetterBattle.get();
        return core != null && core.config().uiTheme;
    }
}
