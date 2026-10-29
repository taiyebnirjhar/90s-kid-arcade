// The relay's rules, with the world faked (node --test, server/test.sh).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  CHECK_EVERY_MS, type Deps, GB, handle, monthOf, nextMonth, parseUsage, TRUST_FOR_MS, usageQuery, verdict,
} from "./relay.ts";

function world(over: Partial<Deps> & { bytes?: number; members?: string[] } = {}) {
  const state = {
    now: new Date("2026-10-29T12:00:00Z"),
    usage: new Map<string, { bytes: number; checkedAt: Date }>(),
    asked: 0,
    issued: 0,
    remembered: [] as string[],
    revoked: [] as string[],
    live: ["old-1", "old-2"],
  };
  const deps: Deps = {
    now: () => state.now,
    inRoom: async (user, room) => (over.members ?? ["rafi"]).includes(user) && room === "K7Q2MX",
    readUsage: async (month) => state.usage.get(month) ?? null,
    writeUsage: async (month, bytes, at) => void state.usage.set(month, { bytes, checkedAt: at }),
    askCloudflare: async () => {
      state.asked++;
      return over.bytes ?? 10 * GB;
    },
    issue: async (room) => {
      state.issued++;
      return [{ urls: ["stun:stun.cloudflare.com:3478"] }, { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: `u${state.issued}-${room}`, credential: "c" }];
    },
    remember: async (username) => void state.remembered.push(username),
    live: async () => state.live,
    revoke: async (username) => void state.revoked.push(username),
    markRevoked: async () => {},
    log: () => {},
    ...over,
  };
  return { deps, state };
}

test("a member gets credentials for an hour, tagged with the room", async () => {
  const { deps, state } = world();
  const a = await handle("rafi", "k7q-2mx", deps);
  assert.equal(a.status, 200);
  assert.equal(a.body.ttl, 3600);
  assert.deepEqual(state.remembered, ["u1-K7Q2MX"]);
});

test("strangers and the signed out get nothing", async () => {
  const { deps, state } = world();
  assert.equal((await handle(null, "K7Q2MX", deps)).status, 401);
  assert.equal((await handle("tanu", "K7Q2MX", deps)).status, 403);
  assert.equal((await handle("rafi", "", deps)).status, 400);
  assert.equal(state.issued, 0);
});

test("at 900 GB none are handed out, until the 1st", async () => {
  const { deps, state } = world({ bytes: 900 * GB });
  const a = await handle("rafi", "K7Q2MX", deps);
  assert.equal(a.status, 503);
  assert.equal(a.body.paused, true);
  assert.equal(a.body.until, "2026-11-01");
  assert.match(String(a.body.message), /paused until the 1st/);
  assert.equal(state.issued, 0);
  assert.deepEqual(state.revoked, []);
});

test("at 950 GB the live ones are revoked too", async () => {
  const { deps, state } = world({ bytes: 951 * GB });
  assert.equal((await handle("rafi", "K7Q2MX", deps)).status, 503);
  assert.deepEqual(state.revoked, ["old-1", "old-2"]);
  assert.equal(state.issued, 0);
});

test("just under 900 GB still plays", async () => {
  const { deps } = world({ bytes: 900 * GB - 1 });
  assert.equal((await handle("rafi", "K7Q2MX", deps)).status, 200);
});

test("Cloudflare is asked at most every 5 minutes", async () => {
  const { deps, state } = world();
  await handle("rafi", "K7Q2MX", deps);
  state.now = new Date(state.now.getTime() + CHECK_EVERY_MS - 1);
  await handle("rafi", "K7Q2MX", deps);
  assert.equal(state.asked, 1);
  state.now = new Date(state.now.getTime() + 1);
  await handle("rafi", "K7Q2MX", deps);
  assert.equal(state.asked, 2);
});

test("a lower reading than the last one keeps the higher (sampling noise)", async () => {
  let bytes = 899 * GB;
  const { deps, state } = world({ askCloudflare: async () => bytes });
  await handle("rafi", "K7Q2MX", deps);
  bytes = 100 * GB;
  state.now = new Date(state.now.getTime() + CHECK_EVERY_MS);
  await handle("rafi", "K7Q2MX", deps);
  assert.equal(state.usage.get("2026-10-01")!.bytes, 899 * GB);
});

test("if Cloudflare can't say, an hour-old reading is trusted, an older one isn't", async () => {
  let up = true;
  const { deps, state } = world({
    askCloudflare: async () => {
      if (!up) throw new Error("down");
      return 10 * GB;
    },
  });
  assert.equal((await handle("rafi", "K7Q2MX", deps)).status, 200);
  up = false;
  state.now = new Date(state.now.getTime() + TRUST_FOR_MS - 1);
  assert.equal((await handle("rafi", "K7Q2MX", deps)).status, 200);
  state.now = new Date(state.now.getTime() + 2);
  const a = await handle("rafi", "K7Q2MX", deps);
  assert.equal(a.status, 503, "blind: none handed out");
});

test("a new month starts afresh", async () => {
  const { deps, state } = world();
  state.usage.set("2026-10-01", { bytes: 960 * GB, checkedAt: state.now });
  state.now = new Date("2026-11-01T00:00:01Z");
  assert.equal((await handle("rafi", "K7Q2MX", deps)).status, 200);
});

test("months, the query and its answer", () => {
  assert.equal(monthOf(new Date("2026-12-31T23:59:59Z")), "2026-12-01");
  assert.equal(nextMonth(new Date("2026-12-31T23:59:59Z")), "2027-01-01");
  const q = usageQuery("acct", new Date("2026-10-29T12:00:00Z"));
  assert.deepEqual(q.variables, { account: "acct", from: "2026-10-01", to: "2026-10-29" });
  assert.match(q.query, /callsTurnUsageAdaptiveGroups/);
  const answer = { data: { viewer: { accounts: [{ callsTurnUsageAdaptiveGroups: [{ sum: { egressBytes: 7, ingressBytes: 3 } }] }] } } };
  assert.equal(parseUsage(answer), 10);
  assert.equal(parseUsage({ data: { viewer: { accounts: [{ callsTurnUsageAdaptiveGroups: [] }] } } }), 0);
  assert.throws(() => parseUsage({ errors: [{ message: "not authorized" }] }), /not authorized/);
  assert.throws(() => parseUsage({ data: { viewer: { accounts: [] } } }));
  assert.equal(verdict(899 * GB), "ok");
});
