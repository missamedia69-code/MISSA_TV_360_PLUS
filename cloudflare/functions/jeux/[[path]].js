// Jeux de MISSA TV servis depuis Cloudflare R2 : https://missatv.pages.dev/jeux/<chemin>
//
// Le seau R2 « missatv-jeux » contient une copie de game/rom/ et game/neogeo/ du dépôt
// (envoyée par .github/workflows/site-cloudflare.yml). L'application essaie d'abord cette
// adresse, puis GitHub si elle échoue ; chaque fichier est vérifié par son empreinte SHA-256.
//
// Tant que R2 n'est pas activé sur le compte Cloudflare, le seau n'est pas relié (variable
// JEUX absente) : on répond 503 et l'application passe directement par GitHub.

const ALLOWED = /^(rom|neogeo)\/[A-Za-z0-9._\-\/]+$/;

export async function onRequest({ request, params, env }) {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Méthode non permise", { status: 405 });
  }
  if (!env.JEUX) {
    return new Response("Stockage des jeux non activé", { status: 503 });
  }
  const key = (Array.isArray(params.path) ? params.path : [params.path || ""]).join("/");
  if (!ALLOWED.test(key) || key.includes("..")) {
    return new Response("Introuvable", { status: 404 });
  }
  const object = request.method === "HEAD" ? await env.JEUX.head(key) : await env.JEUX.get(key);
  if (!object) {
    return new Response("Introuvable", { status: 404 });
  }
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("content-length", String(object.size));
  headers.set("content-type", "application/octet-stream");
  headers.set("cache-control", "public, max-age=86400");
  return new Response(request.method === "HEAD" ? null : object.body, { headers });
}
