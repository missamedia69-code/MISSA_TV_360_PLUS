// MISSA TV 360 PLUS — page d'accueil en onglets.
// Onglets liés à l'adresse (#tarifs, #installer…), transitions animées, carrousel de
// captures. Sans JavaScript, toutes les sections restent affichées l'une sous l'autre.
(function () {
  "use strict";
  var stage = document.querySelector(".stage");
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".tab"));
  var ink = document.querySelector(".tab-ink");
  if (!stage || !tabs.length) return;
  var ids = tabs.map(function (t) { return t.getAttribute("href").slice(1); });
  function panelOf(i) { return document.getElementById(tabs[i].getAttribute("aria-controls")); }
  var aliases = {};
  try { aliases = JSON.parse(stage.getAttribute("data-aliases") || "{}"); } catch (e) { /* rien */ }
  // Anciennes ancres du site (avant les onglets).
  ["captures", "preview"].forEach(function (k) { if (ids.indexOf(k) === -1) aliases[k] = ids[2]; });
  var current = -1;

  // Apparition en cascade : chaque bloc du panneau reçoit son rang.
  ids.forEach(function (id, n) {
    var panel = panelOf(n);
    var blocks = panel.querySelectorAll(".head, .home-card, .home-side, .feat, .fam, .plan, .step-card, .faq details, .picks, .tv-big, .cta.center, .note");
    for (var i = 0; i < blocks.length; i++) {
      blocks[i].setAttribute("data-rise", "");
      blocks[i].style.setProperty("--d", i);
    }
  });

  function moveInk() {
    var t = tabs[current];
    if (!ink || !t) return;
    ink.style.width = t.offsetWidth + "px";
    ink.style.transform = "translateX(" + t.offsetLeft + "px)";
  }

  function show(index, push) {
    if (index < 0 || index >= ids.length) index = 0;
    if (index === current) return;
    var dir = index > current ? "enter-r" : "enter-l";
    tabs.forEach(function (t, i) {
      var on = i === index;
      t.setAttribute("aria-selected", on ? "true" : "false");
      t.tabIndex = on ? 0 : -1;
      var p = panelOf(i);
      p.classList.remove("enter-r", "enter-l");
      if (on) {
        p.hidden = false;
        p.classList.add("is-active");
        if (current !== -1) { void p.offsetWidth; p.classList.add(dir); }
      } else {
        p.classList.remove("is-active");
        p.hidden = true;
      }
    });
    current = index;
    moveInk();
    // Onglet visible dans la barre (écrans étroits), sans faire défiler la page.
    var bar = tabs[index].parentNode, t = tabs[index];
    if (t.offsetLeft < bar.scrollLeft) bar.scrollLeft = t.offsetLeft - 8;
    else if (t.offsetLeft + t.offsetWidth > bar.scrollLeft + bar.clientWidth) bar.scrollLeft = t.offsetLeft + t.offsetWidth - bar.clientWidth + 8;
    if (push) {
      if (history.pushState) history.pushState(null, "", "#" + ids[index]);
      else location.hash = ids[index];
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  }

  function fromHash() {
    var h = decodeURIComponent((location.hash || "").slice(1));
    if (aliases[h]) h = aliases[h];
    var i = ids.indexOf(h);
    show(i === -1 ? 0 : i, false);
  }

  tabs.forEach(function (t, i) {
    t.addEventListener("click", function (e) { e.preventDefault(); show(i, true); });
    t.addEventListener("keydown", function (e) {
      var k = e.key, n = null;
      if (k === "ArrowRight") n = (i + 1) % tabs.length;
      else if (k === "ArrowLeft") n = (i - 1 + tabs.length) % tabs.length;
      else if (k === "Home") n = 0;
      else if (k === "End") n = tabs.length - 1;
      if (n !== null) { e.preventDefault(); show(n, true); tabs[n].focus(); }
    });
  });

  // Liens internes vers un onglet (« Comment l'installer », etc.).
  document.addEventListener("click", function (e) {
    var a = e.target.closest ? e.target.closest('a[href^="#"]') : null;
    if (!a || a.classList.contains("tab") || a.classList.contains("skip")) return;
    var h = a.getAttribute("href").slice(1);
    if (aliases[h]) h = aliases[h];
    var i = ids.indexOf(h);
    if (i !== -1) { e.preventDefault(); show(i, true); }
  });

  window.addEventListener("popstate", fromHash);
  window.addEventListener("hashchange", fromHash);
  window.addEventListener("resize", moveInk);
  fromHash();
  // Le navigateur a pu sauter jusqu'à l'ancre (#tarifs…) : on repart du haut de la page.
  if (location.hash) {
    if ("scrollRestoration" in history) history.scrollRestoration = "manual";
    window.scrollTo(0, 0);
    setTimeout(function () { window.scrollTo(0, 0); }, 0);
  }
  // La police peut changer la largeur des onglets après le chargement.
  window.addEventListener("load", moveInk);

  // ------------------------------------------------------------ captures
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function slider(box) {
    var shots = box.querySelectorAll(".tv-shot");
    var i = 0;
    return {
      count: shots.length,
      go: function (n) {
        if (!shots.length) return 0;
        n = (n + shots.length) % shots.length;
        shots[i].classList.remove("on");
        shots[n].classList.add("on");
        i = n;
        return n;
      },
      index: function () { return i; }
    };
  }

  // Accueil : défilement automatique, en pause au survol et quand l'onglet est caché.
  var hero = document.querySelector("[data-carousel]");
  if (hero && !reduce) {
    var hs = slider(hero), paused = false;
    hero.addEventListener("mouseenter", function () { paused = true; });
    hero.addEventListener("mouseleave", function () { paused = false; });
    setInterval(function () {
      if (!paused && !document.hidden && current === 0) hs.go(hs.index() + 1);
    }, 4200);
  }

  // Aperçu : choix de l'univers, flèches, glissement du doigt.
  var viewer = document.querySelector("[data-viewer]");
  if (viewer) {
    var vs = slider(viewer);
    var picks = Array.prototype.slice.call(document.querySelectorAll(".pick"));
    var pick = function (n) {
      n = vs.go(n);
      picks.forEach(function (p, j) {
        p.classList.toggle("on", j === n);
        p.setAttribute("aria-pressed", j === n ? "true" : "false");
      });
    };
    picks.forEach(function (p, j) { p.addEventListener("click", function () { pick(j); }); });
    viewer.querySelector(".nav-prev").addEventListener("click", function () { pick(vs.index() - 1); });
    viewer.querySelector(".nav-next").addEventListener("click", function () { pick(vs.index() + 1); });
    var x0 = null;
    viewer.addEventListener("touchstart", function (e) { x0 = e.touches[0].clientX; }, { passive: true });
    viewer.addEventListener("touchend", function (e) {
      if (x0 === null) return;
      var dx = e.changedTouches[0].clientX - x0;
      if (Math.abs(dx) > 40) pick(vs.index() + (dx < 0 ? 1 : -1));
      x0 = null;
    });
  }
})();
