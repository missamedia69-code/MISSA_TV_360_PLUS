// MISSA TV 360 PLUS — site vitrine.
// Lit la dernière version publiée sur GitHub pour pointer le bouton directement sur l'APK
// et afficher son numéro, sa taille et sa date. Sans réponse de GitHub (hors ligne, limite
// de requêtes), les boutons gardent leur lien vers la page « dernière version » : rien ne casse.

(function () {
  "use strict";

  var REPO = "missamedia69-code/MISSA_TV_360_PLUS";
  var EN = (document.documentElement.lang || "fr").toLowerCase().indexOf("en") === 0;
  var T = EN
    ? { locale: "en-GB", mb: " MB", version: "Version ", published: "released on ", android: "Android 6 and later",
        downloads: function (n) { return n + (n === "1" ? " download" : " downloads"); } }
    : { locale: "fr-FR", mb: " Mo", version: "Version ", published: "publiée le ", android: "Android 6 et plus",
        downloads: function (n) { return n + (n === "1" ? " téléchargement" : " téléchargements"); } };
  var API = "https://api.github.com/repos/" + REPO + "/releases/latest";

  // L'adresse de la page, à saisir dans l'application Downloader sur une TV.
  var siteUrl = document.getElementById("site-url");
  if (siteUrl && location.host) {
    siteUrl.textContent = (location.host + location.pathname).replace(/index\.html$/, "").replace(/\/$/, "");
  }

  function formatSize(bytes) {
    if (!bytes) return "";
    var mb = (bytes / (1024 * 1024)).toFixed(1);
    return (EN ? mb : mb.replace(".", ",")) + T.mb;
  }

  function formatDate(iso) {
    try {
      return new Date(iso).toLocaleDateString(T.locale, { day: "numeric", month: "long", year: "numeric" });
    } catch (e) {
      return "";
    }
  }

  if (!window.fetch) return;

  fetch(API, { headers: { Accept: "application/vnd.github+json" } })
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (release) {
      if (!release || !release.assets) return;
      var apk = null;
      for (var i = 0; i < release.assets.length; i++) {
        if (/\.apk$/i.test(release.assets[i].name)) { apk = release.assets[i]; break; }
      }
      if (!apk) return;

      // Les boutons gardent leur lien /api/dl : le serveur compte le téléchargement puis
      // renvoie vers l'APK de la dernière version (GitHub). Rien à changer ici.

      var meta = document.getElementById("release-meta");
      if (meta) {
        var parts = [T.version + String(release.tag_name || "").replace(/^v/i, "")];
        var size = formatSize(apk.size);
        if (size) parts.push(size);
        var date = formatDate(release.published_at);
        if (date) parts.push(T.published + date);
        parts.push(T.android);
        meta.textContent = parts.join(" · ");
        meta.setAttribute("data-base", meta.textContent);
        showDownloads();
      }
    })
    .catch(function () { /* on garde le lien de secours */ });

  // Nombre de téléchargements (compté par le serveur à chaque appui sur « Télécharger »).
  var downloads = null;
  function showDownloads() {
    var meta = document.getElementById("release-meta");
    if (!meta || downloads === null || downloads < 1) return;
    var base = meta.getAttribute("data-base") || meta.textContent;
    meta.setAttribute("data-base", base);
    meta.textContent = base + " · " + T.downloads(downloads.toLocaleString(T.locale));
  }
  fetch("/api/stats")
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (st) {
      if (!st || typeof st.downloads !== "number") return;
      downloads = st.downloads;
      showDownloads();
    })
    .catch(function () {});
})();
