// The relay's rules (docs/online-plan.md, D6 and D8), apart from the
// network and the database so they can be tested: who gets relay
// credentials, and the hard stop before Cloudflare's free 1,000 GB a month.

export const GB = 1_000_000_000;
/** From here no new credentials (90%: a margin for the analytics' delay). */
export const STOP_NEW = 900 * GB;
/** From here every live credential is revoked too: relayed games end. */
export const REVOKE_ALL = 950 * GB;
/** Cloudflare's analytics are asked at most this often. */
export const CHECK_EVERY_MS = 5 * 60_000;
/** Without a reading this fresh, it doesn't hand out credentials: never past the allowance blind. */
export const TRUST_FOR_MS = 60 * 60_000;
/** How long a credential lasts; the app asks for fresh ones before then. */
export const TTL_SECONDS = 3600;

export const PAUSED_MESSAGE =
  "Online play through the relay is paused until the 1st. Nearby play and direct connections still work.";

/** The month [now] is in, as Cloudflare bills it (UTC): "2026-10-01". */
export function monthOf(now: Date): string {
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}-01`;
}

/** The 1st of the next month: "2026-11-01". */
export function nextMonth(now: Date): string {
  return monthOf(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1)));
}

/** Today in UTC: "2026-10-29". */
export function dayOf(now: Date): string {
  return now.toISOString().slice(0, 10);
}

/** This month's relay use from Cloudflare's GraphQL API: one sum, as their docs advise for billing. */
export function usageQuery(account: string, now: Date): { query: string; variables: Record<string, string> } {
  return {
    query: `query relayUse($account: string!, $from: Date!, $to: Date!) {
  viewer {
    accounts(filter: { accountTag: $account }) {
      callsTurnUsageAdaptiveGroups(limit: 1, filter: { date_geq: $from, date_leq: $to }) {
        sum { egressBytes ingressBytes }
      }
    }
  }
}`,
    variables: { account, from: monthOf(now), to: dayOf(now) },
  };
}

/** The bytes in an answer to [usageQuery]: both ways counted, to stay on the safe side. */
export function parseUsage(answer: unknown): number {
  // deno-lint-ignore no-explicit-any
  const a = answer as any;
  if (a?.errors?.length) throw new Error(`Cloudflare analytics: ${a.errors.map((e: { message?: string }) => e.message).join("; ")}`);
  const accounts = a?.data?.viewer?.accounts;
  if (!Array.isArray(accounts) || accounts.length === 0) throw new Error("Cloudflare analytics: no account in the answer");
  let bytes = 0;
  for (const group of accounts[0].callsTurnUsageAdaptiveGroups ?? []) {
    bytes += Number(group?.sum?.egressBytes ?? 0) + Number(group?.sum?.ingressBytes ?? 0);
  }
  if (!Number.isFinite(bytes)) throw new Error("Cloudflare analytics: not a number");
  return bytes;
}

export type Verdict = "ok" | "paused" | "revoke";

/** What this month's [bytes] allow. */
export function verdict(bytes: number): Verdict {
  if (bytes >= REVOKE_ALL) return "revoke";
  if (bytes >= STOP_NEW) return "paused";
  return "ok";
}

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

/** What the function needs from the world; faked in the tests. */
export interface Deps {
  now(): Date;
  /** Whether [user] is in the open room [room]. */
  inRoom(user: string, room: string): Promise<boolean>;
  /** This month's last reading, if any. */
  readUsage(month: string): Promise<{ bytes: number; checkedAt: Date } | null>;
  writeUsage(month: string, bytes: number, at: Date): Promise<void>;
  /** This month's use so far, from Cloudflare. */
  askCloudflare(now: Date): Promise<number>;
  /** New credentials, tagged with [room]. */
  issue(room: string, ttlSeconds: number): Promise<IceServer[]>;
  remember(username: string, user: string, room: string, expiresAt: Date): Promise<void>;
  /** Credentials handed out that haven't expired or been revoked. */
  live(now: Date): Promise<string[]>;
  revoke(username: string): Promise<void>;
  markRevoked(username: string): Promise<void>;
  log(text: string): void;
}

export interface Answer {
  status: number;
  body: Record<string, unknown>;
}

/**
 * This month's use, as fresh as the rules want: asked again when the last
 * reading is over [CHECK_EVERY_MS] old. Null when there's none fresh enough
 * to trust ([TRUST_FOR_MS]).
 */
export async function usageNow(deps: Deps): Promise<number | null> {
  const now = deps.now();
  const month = monthOf(now);
  const last = await deps.readUsage(month);
  if (last && now.getTime() - last.checkedAt.getTime() < CHECK_EVERY_MS) return last.bytes;
  try {
    const bytes = await deps.askCloudflare(now);
    // Analytics only grow within a month; a lower answer is sampling noise.
    const kept = Math.max(bytes, last?.bytes ?? 0);
    await deps.writeUsage(month, kept, now);
    return kept;
  } catch (e) {
    deps.log(`Couldn't read the relay's use: ${e instanceof Error ? e.message : e}`);
    if (last && now.getTime() - last.checkedAt.getTime() < TRUST_FOR_MS) return last.bytes;
    return null;
  }
}

/** One request for relay credentials, from [user] (null: not signed in) for [room]. */
export async function handle(user: string | null, room: string | null, deps: Deps): Promise<Answer> {
  if (!user) return { status: 401, body: { error: "Sign in first" } };
  const code = (room ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!code) return { status: 400, body: { error: "Which room?" } };
  if (!(await deps.inRoom(user, code))) return { status: 403, body: { error: "You're not in that room" } };

  const now = deps.now();
  const paused = { status: 503, body: { paused: true, until: nextMonth(now), message: PAUSED_MESSAGE } };
  const bytes = await usageNow(deps);
  if (bytes === null) {
    // Can't tell how much is left: none handed out (never past the allowance blind).
    return { status: 503, body: { paused: true, message: "Online play through the relay is unavailable for a moment. Nearby play and direct connections still work." } };
  }
  switch (verdict(bytes)) {
    case "revoke":
      for (const username of await deps.live(now)) {
        try {
          await deps.revoke(username);
          await deps.markRevoked(username);
        } catch (e) {
          deps.log(`Couldn't revoke a credential: ${e instanceof Error ? e.message : e}`);
        }
      }
      return paused;
    case "paused":
      return paused;
    case "ok": {
      const iceServers = await deps.issue(code, TTL_SECONDS);
      const expiresAt = new Date(now.getTime() + TTL_SECONDS * 1000);
      for (const s of iceServers) if (s.username) await deps.remember(s.username, user, code, expiresAt);
      return { status: 200, body: { iceServers, ttl: TTL_SECONDS } };
    }
  }
}
