import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, readdir, rename, unlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { GITHUB_API } from "../lib/upstream";

export type Loader = "fabric" | "forge";
export type Asset = { file: string; version: string; sha256: string | null; size: number; url: string };
export type LatestRelease = { version: string; builtAt: string; fabric: Asset | null; forge: Asset | null };
export type Jar = { file: string; sha256: string; body: Buffer };

type GitHubAsset = { name?: string; size?: number; browser_download_url?: string };
type GitHubRelease = { published_at?: string; assets?: GitHubAsset[] };

const RELEASE_TTL_MS = 10 * 60_000;
const MAX_JAR_BYTES = 50 * 1024 * 1024;
const MAX_SUMS_BYTES = 1024 * 1024;
const jarPatterns: Record<Loader, RegExp> = {
  fabric: /^betterbattle-fabric-26\.3-(.+)\.jar$/i,
  forge: /^betterbattle-forge-1\.8\.9-(.+)\.jar$/i,
};
const downloadsInFlight = new Map<string, Promise<Jar | null>>();
let cachedRelease: { expiresAt: number; value: LatestRelease | null } | null = null;
let lastGoodRelease: LatestRelease | null = null;
let releaseInFlight: Promise<LatestRelease | null> | null = null;

function apiHeaders() {
  return { "user-agent": "BetterBattleDownloads", accept: "application/vnd.github+json" };
}

async function readLimited(response: Response, maxBytes: number) {
  const declaredSize = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredSize) && declaredSize > maxBytes) throw new Error("asset exceeds size limit");
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  const chunks: Buffer[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        await reader.cancel();
        throw new Error("asset exceeds size limit");
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, size);
}

function checksumsFrom(text: string) {
  const checksums = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^([a-f0-9]{64})\s+(.+)$/i);
    if (match) checksums.set(match[2].trim(), match[1].toLowerCase());
  }
  return checksums;
}

function assetFor(loader: Loader, assets: GitHubAsset[], checksums: Map<string, string>): Asset | null {
  const match = assets
    .filter((asset): asset is GitHubAsset & { name: string; browser_download_url: string } =>
      typeof asset.name === "string" && typeof asset.browser_download_url === "string")
    .map((asset) => ({ asset, version: asset.name.match(jarPatterns[loader])?.[1] }))
    .find((item) => item.version);
  if (!match) return null;
  return {
    file: match.asset.name,
    version: match.version!,
    sha256: checksums.get(match.asset.name) ?? null,
    size: Number.isFinite(match.asset.size) ? Number(match.asset.size) : 0,
    url: match.asset.browser_download_url,
  };
}

async function fetchLatestRelease(): Promise<LatestRelease | null> {
  const repo = process.env.MOD_RELEASE_REPO ?? "Koiduu/BetterBuildbattle";
  const api = GITHUB_API.replace(/\/$/, "");
  const response = await fetch(`${api}/repos/${repo}/releases/tags/dev`, {
    headers: apiHeaders(),
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error(`GitHub returned ${response.status}`);
  const release = await response.json() as GitHubRelease;
  if (!release || typeof release !== "object" || !Array.isArray(release.assets)) {
    throw new Error("GitHub returned an invalid release");
  }

  const sumsAsset = release.assets.find((asset) => asset.name === "SHA256SUMS");
  let checksums = new Map<string, string>();
  if (typeof sumsAsset?.browser_download_url === "string") {
    const sumsResponse = await fetch(sumsAsset.browser_download_url, {
      headers: apiHeaders(),
      signal: AbortSignal.timeout(5000),
    });
    if (!sumsResponse.ok) throw new Error(`GitHub checksum download returned ${sumsResponse.status}`);
    checksums = checksumsFrom((await readLimited(sumsResponse, MAX_SUMS_BYTES)).toString("utf8"));
  }

  const fabric = assetFor("fabric", release.assets, checksums);
  const forge = assetFor("forge", release.assets, checksums);
  const publishedAt = typeof release.published_at === "string" ? release.published_at : "";
  const value = {
    version: fabric?.version ?? "",
    builtAt: publishedAt,
    fabric,
    forge,
  };
  lastGoodRelease = value;
  return value;
}

export async function latestRelease(): Promise<LatestRelease | null> {
  if (cachedRelease && cachedRelease.expiresAt > Date.now()) return cachedRelease.value;
  if (releaseInFlight) return releaseInFlight;
  releaseInFlight = fetchLatestRelease()
    .catch((error) => {
      console.warn("Could not read Better Battle GitHub release:", error instanceof Error ? error.message : error);
      return lastGoodRelease;
    })
    .then((value) => {
      cachedRelease = { expiresAt: Date.now() + RELEASE_TTL_MS, value };
      return value;
    })
    .finally(() => { releaseInFlight = null; });
  return releaseInFlight;
}

function digest(body: Buffer) {
  return createHash("sha256").update(body).digest("hex");
}

function cacheDirectory() {
  return process.env.MOD_CACHE_DIR ?? path.join(os.tmpdir(), "betterbattle-mod");
}

async function removeOtherJars(directory: string, keep: string) {
  const files = await readdir(directory);
  await Promise.all(files
    .filter((file) => file.endsWith(".jar") && file !== keep)
    .map((file) => unlink(path.join(directory, file)).catch(() => {})));
}

async function downloadJar(asset: Asset): Promise<Jar | null> {
  if (!asset.sha256) {
    console.warn(`No SHA-256 checksum published for ${asset.file}`);
    return null;
  }
  const directory = cacheDirectory();
  const cacheName = `${asset.sha256}.jar`;
  const cachePath = path.join(directory, cacheName);
  await mkdir(directory, { recursive: true });
  try {
    const body = await readFile(cachePath);
    if (digest(body) === asset.sha256) return { file: asset.file, sha256: asset.sha256, body };
    console.warn(`Cached jar checksum mismatch for ${asset.file}`);
    await unlink(cachePath).catch(() => {});
  } catch {}

  try {
    const response = await fetch(asset.url, { signal: AbortSignal.timeout(60_000) });
    if (!response.ok) throw new Error(`GitHub returned ${response.status}`);
    const body = await readLimited(response, MAX_JAR_BYTES);
    if (digest(body) !== asset.sha256) {
      console.warn(`Downloaded jar checksum mismatch for ${asset.file}`);
      return null;
    }
    const temporaryPath = path.join(directory, `${cacheName}.tmp-${randomUUID()}`);
    try {
      await writeFile(temporaryPath, body);
      await rename(temporaryPath, cachePath);
    } catch (error) {
      await unlink(temporaryPath).catch(() => {});
      throw error;
    }
    await removeOtherJars(directory, cacheName);
    return { file: asset.file, sha256: asset.sha256, body };
  } catch (error) {
    console.warn(`Could not download ${asset.file}:`, error instanceof Error ? error.message : error);
    return null;
  }
}

export async function jarFor(loader: Loader): Promise<Jar | null> {
  const release = await latestRelease();
  const asset = release?.[loader];
  if (!asset) return null;
  if (!asset.sha256) {
    console.warn(`No SHA-256 checksum published for ${asset.file}`);
    return null;
  }
  let inFlight = downloadsInFlight.get(asset.sha256);
  if (!inFlight) {
    inFlight = downloadJar(asset).finally(() => downloadsInFlight.delete(asset.sha256!));
    downloadsInFlight.set(asset.sha256, inFlight);
  }
  return inFlight;
}
