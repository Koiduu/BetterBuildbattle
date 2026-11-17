export async function latestDownloads(fetcher = fetch) {
  try {
    const response = await fetcher("/bb/v1/mod/latest", { signal: AbortSignal.timeout(6000) });
    if (!response.ok) return [];
    const release = await response.json();
    return ["fabric", "forge"].flatMap((loader) => {
      const asset = release?.[loader];
      if (!asset || typeof asset.version !== "string" || !asset.version.trim()
          || typeof asset.sha256 !== "string" || !/^[a-f0-9]{64}$/i.test(asset.sha256)
          || asset.download !== `/download/${loader}`) return [];
      const size = Number.isFinite(asset.size) && asset.size > 0
        ? `${(asset.size / (1024 * 1024)).toFixed(1)} MiB` : null;
      return [{ loader, version: asset.version, size }];
    });
  } catch {
    return [];
  }
}
