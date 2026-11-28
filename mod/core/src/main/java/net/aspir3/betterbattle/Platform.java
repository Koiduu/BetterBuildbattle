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

    /** A chat line that opens url when clicked. Called on the main thread. */
    default void chatLink(String message, String url) {
        chat(message + " §b" + url);
    }

    /** Open a text page (What's New, Last Game...). Called on the main thread. */
    void openPage(net.aspir3.betterbattle.pages.Pages.Page page);

    /** Save a picture of the game screen to this file (a round screenshot). Called on the main thread; Forge does nothing. */
    default void screenshot(java.io.File file) {
    }

    // ---- LFG lobbies. Called on the main thread.

    /** A pop-up in the corner for lobby news. */
    default void lobbyToast(String title, String line) {
    }

    /** A chat line followed by clickable buttons; each button is {label, command to run}. */
    default void chatButtons(String message, String[][] buttons) {
        chat(message);
    }

    /** Opens the chat box with this text typed in. The player presses Enter (or doesn't) themselves. */
    default void fillChat(String text) {
    }

    /** Lobby state changed: an open lobby screen redraws. */
    default void lobbiesChanged() {
    }

    /** A teammate ping: a small toast like the achievement one. Called on the main thread. */
    default void showPing(String title, String text) {
        chat("§d§lBetter Battle §8» §f" + title + "§7: " + text);
    }

    /** A teammate marked this block: show a marker on it for a few seconds. Called on the main thread. */
    default void showBlockPing(String from, int x, int y, int z, long untilMs) {
    }
}
