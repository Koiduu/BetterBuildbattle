package net.aspir3.betterbattle;

import net.aspir3.betterbattle.achieve.Catalog;

/** What the core needs from the game. One implementation per Minecraft version. */
public interface Platform {
    /** Ask Mojang's session server to vouch for this player (runs on the sync thread). */
    void joinServer(String serverId) throws Exception;

    void runOnMainThread(Runnable task);

    /** Show the achievement toast. Called on the main thread. */
    void showAchievement(Catalog.Entry achievement);

    /** Print a chat line. Uses § colour codes. Called on the main thread. */
    void chat(String message);

    /** Open a text page (What's New, Last Game...). Called on the main thread. */
    void openPage(net.aspir3.betterbattle.pages.Pages.Page page);
}
