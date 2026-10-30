// Serves the private arcade-roms bucket to the 90s Kid Arcade app.
// Every request needs the key: "Authorization: Bearer <key>".
//
// Setup (Cloudflare dashboard): an R2 bucket with public access off, this
// Worker with an R2 binding named ROMS to it, and a secret named ARCADE_KEY.
// Compares in constant time, so response timing gives away nothing of the key.
async function sameKey(given, key) {
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(key)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

// R2 now and then fails a call (an internal error): asked again twice, a
// moment apart, before the Worker says so (503, not a bare 500).
async function retried(call) {
  for (let i = 0; ; i++) {
    try {
      return await call();
    } catch (e) {
      if (i >= 2) throw e;
      await new Promise((r) => setTimeout(r, 300 * (i + 1)));
    }
  }
}

export default {
  async fetch(request, env) {
    try {
      return await serve(request, env);
    } catch (e) {
      return new Response(`The library's storage didn't answer: ${e && e.message}`, { status: 503 });
    }
  },
};

async function serve(request, env) {
  {
    const given = request.headers.get("Authorization") || "";
    if (!env.ARCADE_KEY || !(await sameKey(given, `Bearer ${env.ARCADE_KEY}`))) {
      return new Response("Wrong key", { status: 401 });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Not allowed", { status: 405 });
    }
    const url = new URL(request.url);
    // The list of games in the library.
    if (url.pathname === "/" || url.pathname === "/list") {
      const files = [];
      let cursor;
      do {
        const page = await retried(() => env.ROMS.list({ cursor }));
        for (const o of page.objects) files.push({ name: o.key, size: o.size });
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
      return Response.json({ files });
    }
    // One file, e.g. /kof97.zip
    let name;
    try {
      name = decodeURIComponent(url.pathname.slice(1));
    } catch {
      return new Response("Bad file name", { status: 400 });
    }
    const obj = await retried(() => env.ROMS.get(name));
    if (!obj) return new Response("Not found", { status: 404 });
    return new Response(obj.body, {
      headers: {
        "Content-Type": "application/zip",
        "Content-Length": String(obj.size),
        // RFC 6266: any name (quotes, non-ASCII) survives percent-encoded.
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
      },
    });
  }
}
