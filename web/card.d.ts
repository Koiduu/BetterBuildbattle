// Types for card.js, so the API (TypeScript) can import the same drawing code the site uses.
export const CARD_W: number;
export const CARD_H: number;
export interface CardData {
  name: string;
  title: { name: string; hex?: string | null };
  level: number | null;
  stats: [string, string | number][];
  url: string;
  supporter?: string | null;
}
export function drawCard(g: unknown, data: CardData, face: unknown, fonts: { display: string; body: string }, theme?: Partial<import("./style-options.js").CardTheme>): void;
