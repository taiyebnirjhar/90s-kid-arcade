// POST {"room": "K7Q2MX"} with the player's Supabase token: relay (TURN)
// servers for an hour, for a member of that open room. The rules, and the
// hard stop before the free 1,000 GB a month, are in relay.ts.
//
// Secrets (Supabase: Edge Functions -> Secrets; docs/online-setup.md):
// CF_TURN_KEY_ID, CF_TURN_KEY_TOKEN, CF_ANALYTICS_TOKEN, CF_ACCOUNT_ID.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import { type Deps, handle, type IceServer, parseUsage, usageQuery } from "./relay.ts";

const env = (name: string) => Deno.env.get(name) ?? "";
const db = createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), { auth: { persistSession: false } });
const turn = `https://rtc.live.cloudflare.com/v1/turn/keys/${env("CF_TURN_KEY_ID")}`;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const deps: Deps = {
  now: () => new Date(),
  async inRoom(user, room) {
    const { data } = await db.from("rooms").select("status, room_members!inner(member)")
      .eq("code", room).eq("status", "open").eq("room_members.member", user).maybeSingle();
    return data != null;
  },
  async readUsage(month) {
    const { data, error } = await db.from("relay_usage").select("bytes, checked_at").eq("month", month).maybeSingle();
    if (error) throw error;
    return data ? { bytes: Number(data.bytes), checkedAt: new Date(data.checked_at) } : null;
  },
  async writeUsage(month, bytes, at) {
    const { error } = await db.from("relay_usage").upsert({ month, bytes, checked_at: at.toISOString() });
    if (error) throw error;
  },
  async askCloudflare(now) {
    const res = await fetch("https://api.cloudflare.com/client/v4/graphql", {
      method: "POST",
      headers: { Authorization: `Bearer ${env("CF_ANALYTICS_TOKEN")}`, "Content-Type": "application/json" },
      body: JSON.stringify(usageQuery(env("CF_ACCOUNT_ID"), now)),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return parseUsage(await res.json());
  },
  async issue(room, ttlSeconds) {
    const res = await fetch(`${turn}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { Authorization: `Bearer ${env("CF_TURN_KEY_TOKEN")}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: ttlSeconds, customIdentifier: room }),
    });
    if (!res.ok) throw new Error(`Cloudflare TURN: HTTP ${res.status}`);
    return (await res.json()).iceServers as IceServer[];
  },
  async remember(username, user, room, expiresAt) {
    const { error } = await db.from("relay_credentials").insert({ username, member: user, room, expires_at: expiresAt.toISOString() });
    if (error) throw error;
  },
  async live(now) {
    const { data, error } = await db.from("relay_credentials").select("username").eq("revoked", false).gt("expires_at", now.toISOString());
    if (error) throw error;
    return (data ?? []).map((r) => r.username as string);
  },
  async revoke(username) {
    const res = await fetch(`${turn}/credentials/${encodeURIComponent(username)}/revoke`, {
      method: "POST",
      headers: { Authorization: `Bearer ${env("CF_TURN_KEY_TOKEN")}` },
    });
    // Gone already is as good as revoked.
    if (!res.ok && res.status !== 404) throw new Error(`HTTP ${res.status}`);
  },
  async markRevoked(username) {
    await db.from("relay_credentials").update({ revoked: true }).eq("username", username);
  },
  log: (text) => console.log(text),
};

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "POST only" });
  for (const name of ["CF_TURN_KEY_ID", "CF_TURN_KEY_TOKEN", "CF_ANALYTICS_TOKEN", "CF_ACCOUNT_ID"]) {
    if (!env(name)) return json(503, { error: `The server isn't set up yet (${name})` });
  }
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data } = await db.auth.getUser(token);
  let room: string | null = null;
  try {
    room = (await req.json())?.room ?? null;
  } catch {
    // No body: "Which room?"
  }
  try {
    const answer = await handle(data?.user?.id ?? null, room, deps);
    return json(answer.status, answer.body);
  } catch (e) {
    console.log(`turn-credentials: ${e instanceof Error ? e.message : e}`);
    return json(500, { error: "Something went wrong: try again" });
  }
});
