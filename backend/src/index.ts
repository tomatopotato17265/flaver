import { Hono } from "hono";

export interface Env {
  DB: D1Database;
  TOKEN_SECRET: string;
  DEV_AUTH?: string;
}

type Vars = { uuid: string };
const PRESENCE_TTL_SECONDS = 300;
const CHALLENGE_TTL_SECONDS = 300;
const TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;

const now = () => Math.floor(Date.now() / 1000);
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const USERNAME_RE = /^[A-Za-z0-9_]{1,16}$/;
const CHALLENGE_RE = /^[0-9a-f]{32}$/;
const HOSTNAME_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const isUuid = (v: unknown): v is string => typeof v === "string" && UUID_RE.test(v);
const isUsername = (v: unknown): v is string => typeof v === "string" && USERNAME_RE.test(v);

function dashUuid(hex: string): string {
  const h = hex.toLowerCase().replace(/-/g, "");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function pair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}

const enc = new TextEncoder();

function b64urlEncode(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(s: string): Uint8Array {
  const p = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(p + "=".repeat((4 - (p.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

async function hmac(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(data));
  return b64urlEncode(new Uint8Array(sig));
}

/** Constant-time comparison so token checks do not leak the signature byte by byte. */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function signToken(secret: string, uuid: string): Promise<string> {
  const body = b64urlEncode(enc.encode(JSON.stringify({ u: uuid, e: now() + TOKEN_TTL_SECONDS })));
  return `v1.${body}.${await hmac(secret, body)}`;
}

async function verifyToken(secret: string, token: string): Promise<string | null> {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "v1") return null;
  const [, body, sig] = parts;
  if (!timingSafeEqual(sig, await hmac(secret, body))) return null;
  try {
    const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(body)));
    if (typeof payload.e !== "number" || payload.e < now()) return null;
    return isUuid(payload.u) ? payload.u : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// App + middleware
// ---------------------------------------------------------------------------

const app = new Hono<{ Bindings: Env; Variables: Vars }>();

const fail = (code: string, message: string, status: number) =>
  Response.json({ error: code, message }, { status });

app.onError((err, c) => {
  console.error("unhandled", err);
  return c.json({ error: "internal", message: "Internal error" }, 500);
});

app.notFound((c) => c.json({ error: "not_found", message: "No such endpoint" }, 404));

/** Everything under /api except the auth routes requires a valid session token. */
app.use("/api/*", async (c, next) => {
  if (c.req.path.startsWith("/api/auth/")) return next();

  const header = c.req.header("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) return fail("unauthorized", "Missing bearer token", 401);

  const uuid = await verifyToken(c.env.TOKEN_SECRET, token);
  if (!uuid) return fail("unauthorized", "Invalid or expired token", 401);

  c.set("uuid", uuid);
  await next();
});

app.get("/", (c) => c.json({ service: "flaver-backend", ok: true }));

// ---------------------------------------------------------------------------
// Auth — Mojang session proof
// ---------------------------------------------------------------------------

/** Records a player as seen, keeping their username current if they renamed. */
async function upsertPlayer(env: Env, uuid: string, username: string): Promise<void> {
  const t = now();
  await env.DB.prepare(
    `INSERT INTO players (uuid, username, username_lower, created_at, updated_at)
     VALUES (?1, ?2, ?3, ?4, ?4)
     ON CONFLICT(uuid) DO UPDATE SET username = ?2, username_lower = ?3, updated_at = ?4`,
  )
    .bind(uuid, username, username.toLowerCase(), t)
    .run();
}

/**
 * Step 1 of the handshake: hand out a nonce. The mod passes it to Mojang's
 * joinServer as the server id, which is what lets us verify ownership of the
 * account in step 2 without ever seeing the player's credentials.
 */
app.post("/api/auth/challenge", async (c) => {
  const serverId = Array.from(crypto.getRandomValues(new Uint8Array(16)))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  const t = now();
  await c.env.DB.prepare(
    "INSERT INTO auth_challenges (server_id, created_at, expires_at) VALUES (?1, ?2, ?3)",
  )
    .bind(serverId, t, t + CHALLENGE_TTL_SECONDS)
    .run();
  return c.json({ serverId, expiresIn: CHALLENGE_TTL_SECONDS });
});

/**
 * Step 2: the mod has called joinServer with the challenge. Asking Mojang
 * hasJoined for the same challenge proves the caller holds a valid session for
 * that username, and returns the account's real UUID.
 */
app.post("/api/auth/verify", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const { username, serverId } = body as { username?: unknown; serverId?: unknown };

  if (!isUsername(username)) return fail("bad_request", "Invalid username", 400);
  if (typeof serverId !== "string" || !CHALLENGE_RE.test(serverId)) {
    return fail("bad_request", "Invalid serverId", 400);
  }

  // Single use: consuming the row here means a replayed challenge cannot mint a
  // second token.
  const consumed = await c.env.DB.prepare(
    "DELETE FROM auth_challenges WHERE server_id = ?1 AND expires_at > ?2",
  )
    .bind(serverId, now())
    .run();
  if (!consumed.meta.changes) {
    return fail("challenge_invalid", "Challenge unknown, already used, or expired", 400);
  }

  const url =
    "https://sessionserver.mojang.com/session/minecraft/hasJoined" +
    `?username=${encodeURIComponent(username)}&serverId=${encodeURIComponent(serverId)}`;

  let profile: { id?: string; name?: string } | null = null;
  try {
    const res = await fetch(url);
    // Mojang answers 204 with an empty body when the session does not check out.
    if (res.ok && res.status !== 204) profile = await res.json();
  } catch (e) {
    console.error("hasJoined failed", e);
    return fail("upstream_unavailable", "Could not reach Mojang session servers", 502);
  }

  if (!profile?.id || !profile.name) {
    return fail("auth_failed", "Mojang did not confirm this session", 401);
  }

  const uuid = dashUuid(profile.id);
  await upsertPlayer(c.env, uuid, profile.name);
  return c.json({ token: await signToken(c.env.TOKEN_SECRET, uuid), uuid, username: profile.name });
});

/**
 * Development only. The Fabric dev runtime runs offline-mode with a fake
 * profile, so hasJoined can never succeed there. Gated on DEV_AUTH, which lives
 * in .dev.vars and is therefore never present in a deployed Worker.
 */
app.post("/api/auth/dev", async (c) => {
  if (c.env.DEV_AUTH !== "true") return fail("not_found", "No such endpoint", 404);

  const body = await c.req.json().catch(() => ({}));
  const { uuid, username } = body as { uuid?: unknown; username?: unknown };
  if (!isUuid(uuid)) return fail("bad_request", "Invalid uuid", 400);
  if (!isUsername(username)) return fail("bad_request", "Invalid username", 400);

  await upsertPlayer(c.env, uuid, username);
  return c.json({ token: await signToken(c.env.TOKEN_SECRET, uuid), uuid, username, dev: true });
});

app.get("/api/me", async (c) => {
  const row = await c.env.DB.prepare(
    "SELECT uuid, username, created_at FROM players WHERE uuid = ?1",
  )
    .bind(c.get("uuid"))
    .first<{ uuid: string; username: string; created_at: number }>();
  if (!row) return fail("not_found", "Player not found", 404);
  return c.json({ uuid: row.uuid, username: row.username, createdAt: row.created_at });
});

// ---------------------------------------------------------------------------
// Servers + presence
// ---------------------------------------------------------------------------

interface ServerRow {
  server_id: string;
  name: string;
  last_seen: number | null;
  mc_version: string | null;
  tunnel_hostname: string | null;
}

const isOnline = (lastSeen: number | null): boolean =>
  lastSeen !== null && lastSeen > now() - PRESENCE_TTL_SECONDS;

/** Registers a world the first time it is opened to friends. */
app.post("/api/servers", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const name = typeof (body as any).name === "string" ? (body as any).name.trim() : "";
  if (!name || name.length > 64) return fail("bad_request", "Name must be 1-64 characters", 400);

  const serverId = crypto.randomUUID();
  await c.env.DB.prepare(
    "INSERT INTO servers (server_id, owner_uuid, name, created_at) VALUES (?1, ?2, ?3, ?4)",
  )
    .bind(serverId, c.get("uuid"), name, now())
    .run();
  return c.json({ serverId, name }, 201);
});

app.get("/api/servers", async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT s.server_id, s.name, p.last_seen, p.mc_version, p.tunnel_hostname
       FROM servers s LEFT JOIN presence p ON p.server_id = s.server_id
      WHERE s.owner_uuid = ?1
      ORDER BY s.created_at`,
  )
    .bind(c.get("uuid"))
    .all<ServerRow>();

  return c.json({
    servers: results.map((r) => ({
      serverId: r.server_id,
      name: r.name,
      online: isOnline(r.last_seen),
      mcVersion: r.mc_version,
      // Safe to return to the owner: it is their own tunnel.
      tunnelHostname: isOnline(r.last_seen) ? r.tunnel_hostname : null,
    })),
  });
});

app.delete("/api/servers/:id", async (c) => {
  const res = await c.env.DB.prepare(
    "DELETE FROM servers WHERE server_id = ?1 AND owner_uuid = ?2",
  )
    .bind(c.req.param("id"), c.get("uuid"))
    .run();
  if (!res.meta.changes) return fail("not_found", "No such server", 404);
  return c.json({ ok: true });
});

/**
 * Heartbeat. The mod calls this when a tunnel comes up and every couple of
 * minutes after. The hostname is refreshed each time because quick tunnels get
 * a new one on every restart.
 */
app.post("/api/presence", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const { serverId, tunnelHostname, mcVersion } = body as {
    serverId?: unknown;
    tunnelHostname?: unknown;
    mcVersion?: unknown;
  };

  if (typeof serverId !== "string" || !serverId) {
    return fail("bad_request", "Missing serverId", 400);
  }
  if (
    typeof tunnelHostname !== "string" ||
    tunnelHostname.length > 253 ||
    !HOSTNAME_RE.test(tunnelHostname)
  ) {
    return fail("bad_request", "Invalid tunnelHostname", 400);
  }
  const version =
    typeof mcVersion === "string" && mcVersion.length <= 32 ? mcVersion : null;

  const owned = await c.env.DB.prepare(
    "SELECT 1 FROM servers WHERE server_id = ?1 AND owner_uuid = ?2",
  )
    .bind(serverId, c.get("uuid"))
    .first();
  if (!owned) return fail("not_found", "No such server", 404);

  await c.env.DB.prepare(
    `INSERT INTO presence (server_id, tunnel_hostname, mc_version, last_seen)
     VALUES (?1, ?2, ?3, ?4)
     ON CONFLICT(server_id) DO UPDATE SET tunnel_hostname = ?2, mc_version = ?3, last_seen = ?4`,
  )
    .bind(serverId, tunnelHostname, version, now())
    .run();

  return c.json({ ok: true, expiresIn: PRESENCE_TTL_SECONDS });
});

/** Clean shutdown, so friends see the server drop immediately rather than in 5 minutes. */
app.delete("/api/presence/:serverId", async (c) => {
  await c.env.DB.prepare(
    `DELETE FROM presence WHERE server_id = ?1
       AND server_id IN (SELECT server_id FROM servers WHERE owner_uuid = ?2)`,
  )
    .bind(c.req.param("serverId"), c.get("uuid"))
    .run();
  return c.json({ ok: true });
});

// ---------------------------------------------------------------------------
// Friends
// ---------------------------------------------------------------------------

async function areFriends(env: Env, a: string, b: string): Promise<boolean> {
  const [x, y] = pair(a, b);
  const row = await env.DB.prepare(
    "SELECT 1 FROM friends WHERE a_uuid = ?1 AND b_uuid = ?2",
  )
    .bind(x, y)
    .first();
  return row !== null;
}

async function addFriendship(env: Env, a: string, b: string): Promise<void> {
  const [x, y] = pair(a, b);
  await env.DB.batch([
    env.DB.prepare(
      "INSERT OR IGNORE INTO friends (a_uuid, b_uuid, created_at) VALUES (?1, ?2, ?3)",
    ).bind(x, y, now()),
    // Clear any pending request in either direction.
    env.DB.prepare(
      `DELETE FROM friend_requests
        WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)`,
    ).bind(a, b),
  ]);
}

/**
 * The one call the friends screen polls. Returns the whole screen in a single
 * round trip: friends with their servers, plus pending requests both ways.
 *
 * Deliberately omits tunnel hostnames — those are handed out only at join time
 * by GET /api/friends/:uuid/servers, so a hostname is exposed once per join
 * rather than to every friend every 15 seconds.
 */
app.get("/api/friends", async (c) => {
  const me = c.get("uuid");

  const [friendRows, serverRows, incoming, outgoing] = await Promise.all([
    c.env.DB.prepare(
      `SELECT p.uuid, p.username, f.created_at
         FROM friends f
         JOIN players p
           ON p.uuid = CASE WHEN f.a_uuid = ?1 THEN f.b_uuid ELSE f.a_uuid END
        WHERE f.a_uuid = ?1 OR f.b_uuid = ?1
        ORDER BY p.username COLLATE NOCASE`,
    )
      .bind(me)
      .all<{ uuid: string; username: string; created_at: number }>(),

    // Every server belonging to any friend, resolved in one query rather than
    // one per friend.
    c.env.DB.prepare(
      `SELECT s.owner_uuid, s.server_id, s.name, pr.last_seen, pr.mc_version
         FROM servers s
         JOIN friends f
           ON (f.a_uuid = ?1 AND f.b_uuid = s.owner_uuid)
           OR (f.b_uuid = ?1 AND f.a_uuid = s.owner_uuid)
         LEFT JOIN presence pr ON pr.server_id = s.server_id
        ORDER BY s.created_at`,
    )
      .bind(me)
      .all<{
        owner_uuid: string;
        server_id: string;
        name: string;
        last_seen: number | null;
        mc_version: string | null;
      }>(),

    c.env.DB.prepare(
      `SELECT p.uuid, p.username, r.created_at
         FROM friend_requests r JOIN players p ON p.uuid = r.from_uuid
        WHERE r.to_uuid = ?1 ORDER BY r.created_at DESC`,
    )
      .bind(me)
      .all<{ uuid: string; username: string; created_at: number }>(),

    c.env.DB.prepare(
      `SELECT p.uuid, p.username, r.created_at
         FROM friend_requests r JOIN players p ON p.uuid = r.to_uuid
        WHERE r.from_uuid = ?1 ORDER BY r.created_at DESC`,
    )
      .bind(me)
      .all<{ uuid: string; username: string; created_at: number }>(),
  ]);

  const byOwner = new Map<string, Array<Record<string, unknown>>>();
  for (const s of serverRows.results) {
    const list = byOwner.get(s.owner_uuid) ?? [];
    list.push({
      serverId: s.server_id,
      name: s.name,
      online: isOnline(s.last_seen),
      mcVersion: s.mc_version,
    });
    byOwner.set(s.owner_uuid, list);
  }

  const friends = friendRows.results.map((f) => {
    const servers = byOwner.get(f.uuid) ?? [];
    return {
      uuid: f.uuid,
      username: f.username,
      online: servers.some((s) => s.online === true),
      servers,
      since: f.created_at,
    };
  });

  const asRequest = (r: { uuid: string; username: string; created_at: number }) => ({
    uuid: r.uuid,
    username: r.username,
    createdAt: r.created_at,
  });

  return c.json({
    friends,
    incoming: incoming.results.map(asRequest),
    outgoing: outgoing.results.map(asRequest),
  });
});

/**
 * Send a friend request by username.
 *
 * Usernames resolve against our own players table rather than Mojang's API:
 * the target must be a registered Flaver user for a friendship to mean anything,
 * their username is already known to us from their own session proof, and it
 * avoids a rate-limited upstream call on a hot path.
 */
app.post("/api/friends/request", async (c) => {
  const me = c.get("uuid");
  const body = await c.req.json().catch(() => ({}));
  const username = (body as any).username;
  if (!isUsername(username)) return fail("bad_request", "Invalid username", 400);

  const target = await c.env.DB.prepare(
    "SELECT uuid, username FROM players WHERE username_lower = ?1",
  )
    .bind(username.toLowerCase())
    .first<{ uuid: string; username: string }>();

  if (!target) {
    return fail(
      "player_not_registered",
      "That player has not used Flaver yet. They need to install the mod and launch the game once.",
      404,
    );
  }
  if (target.uuid === me) return fail("bad_request", "You cannot add yourself", 400);
  if (await areFriends(c.env, me, target.uuid)) {
    return fail("already_friends", "You are already friends", 409);
  }

  // If they already asked us, treat this as an accept rather than leaving two
  // crossed requests pending.
  const reverse = await c.env.DB.prepare(
    "SELECT 1 FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2",
  )
    .bind(target.uuid, me)
    .first();

  if (reverse) {
    await addFriendship(c.env, me, target.uuid);
    return c.json({ status: "accepted", uuid: target.uuid, username: target.username });
  }

  await c.env.DB.prepare(
    "INSERT OR IGNORE INTO friend_requests (from_uuid, to_uuid, created_at) VALUES (?1, ?2, ?3)",
  )
    .bind(me, target.uuid, now())
    .run();

  return c.json({ status: "requested", uuid: target.uuid, username: target.username });
});

app.post("/api/friends/accept", async (c) => {
  const me = c.get("uuid");
  const body = await c.req.json().catch(() => ({}));
  const from = (body as any).uuid;
  if (!isUuid(from)) return fail("bad_request", "Invalid uuid", 400);

  const pending = await c.env.DB.prepare(
    "SELECT 1 FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2",
  )
    .bind(from, me)
    .first();
  if (!pending) return fail("not_found", "No pending request from that player", 404);

  await addFriendship(c.env, me, from);
  return c.json({ ok: true });
});

app.post("/api/friends/reject", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const from = (body as any).uuid;
  if (!isUuid(from)) return fail("bad_request", "Invalid uuid", 400);
  await c.env.DB.prepare(
    "DELETE FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2",
  )
    .bind(from, c.get("uuid"))
    .run();
  return c.json({ ok: true });
});

app.post("/api/friends/cancel", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const to = (body as any).uuid;
  if (!isUuid(to)) return fail("bad_request", "Invalid uuid", 400);
  await c.env.DB.prepare(
    "DELETE FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2",
  )
    .bind(c.get("uuid"), to)
    .run();
  return c.json({ ok: true });
});

app.delete("/api/friends/:uuid", async (c) => {
  const other = c.req.param("uuid");
  if (!isUuid(other)) return fail("bad_request", "Invalid uuid", 400);
  const [x, y] = pair(c.get("uuid"), other);
  await c.env.DB.prepare("DELETE FROM friends WHERE a_uuid = ?1 AND b_uuid = ?2")
    .bind(x, y)
    .run();
  return c.json({ ok: true });
});

app.get("/api/friends/:uuid/servers", async (c) => {
  const me = c.get("uuid");
  const other = c.req.param("uuid");
  if (!isUuid(other)) return fail("bad_request", "Invalid uuid", 400);
  if (!(await areFriends(c.env, me, other))) {
    return fail("not_friends", "You are not friends with that player", 403);
  }

  const { results } = await c.env.DB.prepare(
    `SELECT s.server_id, s.name, p.last_seen, p.mc_version, p.tunnel_hostname
       FROM servers s LEFT JOIN presence p ON p.server_id = s.server_id
      WHERE s.owner_uuid = ?1
      ORDER BY s.created_at`,
  )
    .bind(other)
    .all<ServerRow>();

  return c.json({
    servers: results.map((r) => {
      const online = isOnline(r.last_seen);
      return {
        serverId: r.server_id,
        name: r.name,
        online,
        mcVersion: r.mc_version,
        tunnelHostname: online ? r.tunnel_hostname : null,
      };
    }),
  });
});

export default {
  fetch: app.fetch,

  async scheduled(_event: ScheduledController, env: Env): Promise<void> {
    const t = now();
    const res = await env.DB.batch([
      env.DB.prepare("DELETE FROM auth_challenges WHERE expires_at <= ?1").bind(t),
      env.DB.prepare("DELETE FROM presence WHERE last_seen <= ?1").bind(t - PRESENCE_TTL_SECONDS),
    ]);
    console.log(
      `sweep: ${res[0].meta.changes} challenges, ${res[1].meta.changes} presence rows`,
    );
  },
} satisfies ExportedHandler<Env>;
