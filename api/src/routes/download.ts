import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { fail, Raw, Router } from "../http";
import { rateLimit } from "../plugins/auth";
import { jarFor, latestRelease, type Asset, type Loader } from "../services/modRelease";

const NOTES_TTL_MS = 60_000;
const defaultWebDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../web");
let cachedNotes: { expiresAt: number; byVersion: Map<string, string> } | null = null;

function publicAsset(asset: Asset | null, loader: Loader) {
  if (!asset) return null;
  return {
    file: asset.file,
    version: asset.version,
    sha256: asset.sha256,
    size: asset.size,
    download: `/download/${loader}`,
  };
}

async function releaseNotes(version: string) {
  if (!cachedNotes || cachedNotes.expiresAt <= Date.now()) {
    const byVersion = new Map<string, string>();
    try {
      const file = path.join(process.env.WEB_DIR ?? defaultWebDir, "updates.json");
      const data = JSON.parse(await readFile(file, "utf8")) as {
        updates?: Array<{ version?: unknown; title?: unknown }>;
      };
      for (const update of data.updates ?? []) {
        if (typeof update.version === "string" && typeof update.title === "string") {
          byVersion.set(update.version, update.title);
        }
      }
    } catch {}
    cachedNotes = { expiresAt: Date.now() + NOTES_TTL_MS, byVersion };
  }
  return cachedNotes.byVersion.get(version);
}

export function downloadRoutes(api: Router, downloads: Router) {
  api.get("/mod/latest", async ({ query }) => {
    const release = await latestRelease();
    if (query.loader !== undefined) {
      const loader = query.loader;
      const expectedMc = loader === "fabric" ? "26.3" : loader === "forge" ? "1.8.9" : null;
      if (!expectedMc || query.mc !== expectedMc || !release) return fail(404, "not_found");
      const asset = release[loader as Loader];
      if (!asset || !/^\d+(?:\.\d+)*$/.test(asset.version)) return fail(404, "not_found");
      const result: { version: string; sha256?: string; notes?: string } = { version: asset.version };
      if (asset.sha256) result.sha256 = asset.sha256;
      const notes = await releaseNotes(asset.version);
      if (notes !== undefined) result.notes = notes;
      return result;
    }
    if (!release) return fail(503, "download_unavailable");
    return {
      version: release.version,
      builtAt: release.builtAt,
      fabric: publicAsset(release.fabric, "fabric"),
      forge: publicAsset(release.forge, "forge"),
    };
  });

  // A jar is ~14 MB read from disk and hashed, so downloads are limited per address.
  downloads.get("/download/:loader", [rateLimit(12, 60_000, "/download")], async ({ params }) => {
    const loader = params.loader;
    if (loader !== "fabric" && loader !== "forge") return fail(404, "not_found");
    const jar = await jarFor(loader);
    if (!jar) return fail(503, "download_unavailable");
    return new Raw(jar.body, "application/java-archive", {
      "content-disposition": `attachment; filename="${jar.file.replace(/[^A-Za-z0-9._+-]/g, "_")}"`,
      "x-checksum-sha256": jar.sha256,
      "cache-control": "no-cache",
    });
  });
}
