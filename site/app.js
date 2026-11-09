// MISSA TV 360 PLUS — site vitrine.
// Lit la dernière version publiée sur GitHub pour pointer le bouton directement sur l'APK
// et afficher son numéro, sa taille et sa date. Sans réponse de GitHub (hors ligne, limite
// de requêtes), les boutons gardent leur lien vers la page « dernière version » : rien ne casse.
(function () {
  "use strict";

  var REPO = "missamedia69-code/MISSA_TV_360_PLUS";
  var API = "https://api.github.com/repos/" + REPO + "/releases/latest";

  // L'adresse de la page, à saisir dans l'application Downloader sur une TV.
  var siteUrl = document.getElementById("site-url");
  if (siteUrl && location.host) {
    siteUrl.textContent = (location.host + location.pathname).replace(/index\.html$/, "").replace(/\/$/, "");
  }

  function formatSize(bytes) {
    if (!bytes) return "";
    return (bytes / (1024 * 1024)).toFixed(1).replace(".", ",") + " Mo";
  }

  function formatDate(iso) {
    try {
      return new Date(iso).toLocaleDateString("fr-FR", { day: "numeric", month: "long", year: "numeric" });
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

      ["download-btn", "download-btn-2"].forEach(function (id) {
        var btn = document.getElementById(id);
        if (btn) btn.href = apk.browser_download_url;
      });

      var meta = document.getElementById("release-meta");
      if (meta) {
        var parts = ["Version " + String(release.tag_name || "").replace(/^v/i, "")];
        var size = formatSize(apk.size);
        if (size) parts.push(size);
        var date = formatDate(release.published_at);
        if (date) parts.push("publiée le " + date);
        parts.push("Android 6 et plus");
        meta.textContent = parts.join(" · ");
      }
    })
    .catch(function () { /* on garde le lien de secours */ });
})();
