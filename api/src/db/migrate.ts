import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { pool } from "./pool";

const migrationsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../db/migrations");

export async function migrate() {
  // Migration files from 003 onward must be safe to re-run on every boot.
  const files = (await fs.readdir(migrationsDir))
    .filter((name) => {
      const number = name.match(/^(\d+)_.*\.sql$/)?.[1];
      return number !== undefined && Number(number) >= 3;
    })
    .sort((a, b) => Number(a.match(/^(\d+)_/)![1]) - Number(b.match(/^(\d+)_/)![1]));
  for (const file of files) {
    await pool.query(await fs.readFile(path.join(migrationsDir, file), "utf8"));
  }
}
