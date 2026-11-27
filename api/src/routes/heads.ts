// Custom heads: players paint them on the website, the owner approves each one, and the mod shows the
// ready ones under "My heads" in the Head Database. See services/customHeads.ts and jobs/skinUploader.ts.
import { z } from "zod";
import { Router } from "../http";
import { rateLimit, requireModToken, requireWebSession } from "../plugins/auth";
import { requireAdmin } from "./admin";
import {
  approveHead, deleteHead, myHeads, queueCounts, readyHeads, rejectHead, retryHead, reviewQueue, submitHead,
} from "../services/customHeads";
import { skinUploaderStatus } from "../jobs/skinUploader";

const Id = z.object({ id: z.coerce.number().int().positive() });

export function headRoutes(app: Router) {
  // ---- the player, on the website
  app.get("/me/heads", [requireWebSession], async (req) => myHeads(req.user.id));

  app.post("/me/heads", [requireWebSession, rateLimit(10, 60_000, "/me/heads")], async (req) => {
    const body = z.object({ name: z.string().min(1).max(64), png: z.string().min(20).max(100_000) }).parse(req.body);
    return submitHead(req.user.id, body.name, body.png);
  });

  app.delete("/me/heads/:id", [requireWebSession, rateLimit(30, 60_000, "/me/heads/:id")], async (req) => {
    const { id } = Id.parse(req.params);
    return deleteHead(req.user.id, id);
  });

  // ---- the mod: ready heads for "My heads" (also sent with /mod/bootstrap)
  app.get("/mod/heads", [requireModToken, rateLimit(20, 60_000, "/mod/heads")], async (req) => ({
    heads: await readyHeads(req.user.id),
  }));

  // ---- the owner's review queue
  app.get("/admin/heads", [requireAdmin], async (req) => {
    const { status } = z.object({
      status: z.enum(["pending", "approved", "ready", "rejected"]).default("pending"),
    }).parse(req.query);
    const [heads, counts, uploader] = await Promise.all([reviewQueue(status), queueCounts(), skinUploaderStatus()]);
    return { heads, counts, uploader };
  });

  app.post("/admin/heads/:id/approve", [requireAdmin], async (req) => approveHead(Id.parse(req.params).id));

  app.post("/admin/heads/:id/reject", [requireAdmin], async (req) => {
    const { reason } = z.object({ reason: z.string().max(200).nullable().default(null) }).parse(req.body ?? {});
    return rejectHead(Id.parse(req.params).id, reason);
  });

  app.post("/admin/heads/:id/retry", [requireAdmin], async (req) => retryHead(Id.parse(req.params).id));
}
