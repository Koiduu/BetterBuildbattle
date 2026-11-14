// The 1200×630 profile card, drawn on any 2D canvas context. Shared by the site's Share card button
// (browser canvas) and the server's link-preview image (@napi-rs/canvas), so both look the same.
// No DOM, no imports: everything it needs is passed in.

export const CARD_W = 1200;
export const CARD_H = 630;

/**
 * data: { name, title: { name, hex }, level|null, stats: [[label, value] × 6], url }
 * face: an image to draw as the player's head, or null for a letter placeholder
 * fonts: { display, body } CSS font families
 */
export function drawCard(g, data, face, fonts) {
  const W = CARD_W, H = CARD_H;
  const display = (size, weight = 700) => `${weight} ${size}px ${fonts.display}`;
  const body = (size) => `${size}px ${fonts.body}`;

  // Panel, like the site's cards: dark fill, black outline, light bevel
  g.fillStyle = "#2a2a30";
  g.fillRect(0, 0, W, H);
  g.fillStyle = "#000";
  g.fillRect(40, 40, W - 80, H - 80);
  g.fillStyle = "#1f1f24";
  g.fillRect(46, 46, W - 92, H - 92);
  g.fillStyle = "#3a3a42";
  g.fillRect(46, 46, W - 92, 4);

  // Face in a black frame; a plain slot with the first letter if there's no image
  g.fillStyle = "#000";
  g.fillRect(86, 86, 168, 168);
  g.fillStyle = "#3a3a42";
  g.fillRect(90, 90, 160, 160);
  if (face) {
    g.imageSmoothingEnabled = false;
    g.drawImage(face, 90, 90, 160, 160);
  } else {
    g.fillStyle = "#9d9da6";
    g.font = display(96);
    const initial = data.name[0].toUpperCase();
    g.fillText(initial, 170 - g.measureText(initial).width / 2, 205);
  }

  g.fillStyle = "#f0f0f0";
  g.font = display(72);
  g.fillText(data.name, 290, 160);
  g.font = display(36, 600);
  g.fillStyle = data.title.hex ?? "#c6c6c6";
  g.fillText(data.title.name, 290, 212);
  if (data.level != null) {
    g.fillStyle = "#80ff20";
    g.fillText(`Level ${data.level}`, 290 + g.measureText(data.title.name).width + 36, 212);
  }

  const colW = (W - 172) / 3;
  data.stats.slice(0, 6).forEach(([label, value], i) => {
    const x = 86 + (i % 3) * colW, y = 284 + Math.floor(i / 3) * 112;
    g.fillStyle = "#141416";
    g.fillRect(x, y, colW - 20, 96);
    g.fillStyle = "#9d9da6";
    g.font = body(22);
    g.fillText(label, x + 20, y + 34);
    g.fillStyle = i === 0 ? "#ffaa00" : "#f0f0f0";
    g.font = display(44);
    g.fillText(String(value), x + 20, y + 80);
  });

  g.fillStyle = "#ff8fc0";
  g.font = display(28);
  g.fillText("Better Battle", 86, H - 72);
  g.fillStyle = "#9d9da6";
  g.font = body(20);
  g.fillText(data.url, W - 86 - g.measureText(data.url).width, H - 72);
}
