import { HttpError } from "./cors.ts";
import { serviceClient } from "./clients.ts";

// The caller identity always comes from the verified token, never from the request body.
export async function authenticate(req: Request): Promise<{ userId: string; jwt: string }> {
  const header = req.headers.get("Authorization") ?? "";
  const jwt = header.replace(/^Bearer\s+/i, "");
  if (!jwt) throw new HttpError(401, "missing token");

  const { data, error } = await serviceClient().auth.getUser(jwt);
  if (error || !data.user) throw new HttpError(401, "invalid token");
  return { userId: data.user.id, jwt };
}

export function requireWorkerSecret(req: Request) {
  const expected = Deno.env.get("WORKER_SECRET");
  if (!expected || req.headers.get("x-worker-secret") !== expected) {
    throw new HttpError(401, "unauthorized");
  }
}
