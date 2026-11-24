/**
 * Build Battle Wrapped stays closed until this moment, for everyone, plans included. What's in it is a
 * surprise until then, so the API refuses it and the page shows only the date.
 * WRAPPED_OPENS (an ISO time) moves the date, e.g. to try it out on a test server.
 */
export function wrappedOpensAt(): Date {
  const custom = Date.parse(process.env.WRAPPED_OPENS ?? "");
  return new Date(Number.isFinite(custom) ? custom : Date.parse("2026-12-27T00:00:00Z"));
}

export const wrappedOpen = () => Date.now() >= wrappedOpensAt().getTime();
