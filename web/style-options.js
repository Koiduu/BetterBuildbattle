// The profile looks a supporter can pick. Shared by the site (to draw them) and the API (to check them).
// Presets rather than free colours and uploads: every option is readable on the dark theme, and there
// is nothing to moderate.

export const ACCENTS = {
  gold: "#ffaa00", pink: "#ff66aa", aqua: "#55ffff", green: "#55ff55", red: "#ff5555",
  yellow: "#ffff55", purple: "#ff55ff", blue: "#7a8cff", orange: "#ff8c1a", white: "#ffffff",
};

/** Banners are CSS gradients, named after places in the game. */
export const BANNERS = {
  sunset: "linear-gradient(120deg, #ff8c1a, #ff5555 55%, #7a2a6a)",
  ocean: "linear-gradient(120deg, #0b3a6a, #1f7fbf 55%, #55ffff)",
  forest: "linear-gradient(120deg, #123a12, #2f7a1c 55%, #80ff20)",
  nether: "linear-gradient(120deg, #2a0808, #8a1c1c 55%, #ff8c1a)",
  end: "linear-gradient(120deg, #0d0a1a, #3a2a6a 55%, #d8b4ff)",
  amethyst: "linear-gradient(120deg, #2a1040, #7a3fbf 55%, #ff66aa)",
  gold: "linear-gradient(120deg, #3a2800, #b87400 55%, #ffe680)",
  snow: "linear-gradient(120deg, #3a4a5a, #9fb8cf 55%, #ffffff)",
};

/** Share-card themes: page, panel, tile and accent colours for web/card.js. */
export const CARD_THEMES = {
  classic: { page: "#2a2a30", panel: "#1f1f24", tile: "#141416", bevel: "#3a3a42", accent: "#ffaa00", brand: "#ff8fc0" },
  midnight: { page: "#0b0d1a", panel: "#12152b", tile: "#0a0c1a", bevel: "#2a3160", accent: "#7a8cff", brand: "#aab4ff" },
  nether: { page: "#1a0808", panel: "#2a0f0f", tile: "#160606", bevel: "#5a1c1c", accent: "#ff8c1a", brand: "#ff5555" },
  ocean: { page: "#06202e", panel: "#0b3044", tile: "#061c28", bevel: "#145a7a", accent: "#55ffff", brand: "#7fd4ff" },
  forest: { page: "#0c1a0c", panel: "#142a14", tile: "#0a160a", bevel: "#2a5a1c", accent: "#80ff20", brand: "#b4ff80" },
  amethyst: { page: "#1a0c26", panel: "#26123a", tile: "#150a20", bevel: "#4a2a6a", accent: "#ff66aa", brand: "#d8b4ff" },
};
