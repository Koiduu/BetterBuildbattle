import pg from "pg";

export const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 10 });
// BIGINT (users.id, xp) comes back as string by default; our values fit in a JS number.
pg.types.setTypeParser(20, (v) => Number(v));
