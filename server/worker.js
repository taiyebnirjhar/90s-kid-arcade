// Serves the private arcade-roms bucket to the 90s Kid Arcade app.
// Every request needs the key: "Authorization: Bearer <key>".
//
// Setup (Cloudflare dashboard): an R2 bucket with public access off, this
// Worker with an R2 binding named ROMS to it, and a secret named ARCADE_KEY.
export default {
  async fetch(request, env) {
    if (!env.ARCADE_KEY || request.headers.get("Authorization") !== `Bearer ${env.ARCADE_KEY}`) {
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
        const page = await env.ROMS.list({ cursor });
        for (const o of page.objects) files.push({ name: o.key, size: o.size });
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
      return Response.json({ files });
    }
    // One file, e.g. /kof97.zip
    const name = decodeURIComponent(url.pathname.slice(1));
    const obj = await env.ROMS.get(name);
    if (!obj) return new Response("Not found", { status: 404 });
    return new Response(obj.body, {
      headers: {
        "Content-Type": "application/zip",
        "Content-Length": String(obj.size),
        "Content-Disposition": `attachment; filename="${name}"`,
      },
    });
  },
};
