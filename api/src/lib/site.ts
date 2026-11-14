/** The public address of the site, for links in previews and images. Never taken from the Host header. */
export const siteOrigin = () => (process.env.PUBLIC_URL ?? "https://battle.aspir3.net").replace(/\/$/, "");
