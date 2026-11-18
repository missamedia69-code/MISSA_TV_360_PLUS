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
  var reduceMotion = !!(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

  // Apparition en cascade : chaque bloc du panneau reçoit son rang.
  ids.forEach(function (id, n) {
    var panel = panelOf(n);
    var blocks = panel.querySelectorAll(".head, .home-card, .home-side, .fam, .plan, .step-card, .faq details, .picks, .tv-big, .cta.center, .note");
    for (var i = 0; i < blocks.length; i++) {
      blocks[i].setAttribute("data-rise", "");
      blocks[i].style.setProperty("--d", i);
    }
  });

  // Blocs du bas de l'accueil (atouts, mur de chaînes, bandeau) : ils apparaissent
  // quand on les atteint en faisant défiler, au lieu d'être animés hors de l'écran.
  var lowBlocks = document.querySelectorAll(".points, .feat, .chan-head, .chan-rows, .chan-stats, .marquee");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("seen"); io.unobserve(en.target); }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });
    var featRank = 0;
    for (var b = 0; b < lowBlocks.length; b++) {
      var el = lowBlocks[b];
      el.classList.add("reveal");
      if (el.classList.contains("feat")) el.style.setProperty("--rd", featRank++);
      io.observe(el);
    }
  }

  // Tarifs : les prix « comptent » jusqu'à leur valeur à l'ouverture de l'onglet.
  var prices = Array.prototype.slice.call(document.querySelectorAll(".price b")).map(function (el) {
    var txt = el.textContent, n = parseInt(txt.replace(/[^0-9]/g, ""), 10);
    return isNaN(n) ? null : { el: el, txt: txt, n: n };
  }).filter(Boolean);
  function countPrices() {
    if (reduceMotion) return;
    prices.forEach(function (p, k) {
      var t0 = null, dur = 1100 + k * 200;
      p.el.classList.remove("done");
      var sep = p.txt.indexOf(",") !== -1 ? "," : "\u00a0";
      function fmt(v) { return String(v).replace(/\B(?=(\d{3})+(?!\d))/g, sep); }
      function step(ts) {
        if (t0 === null) t0 = ts;
        var x = Math.min(1, (ts - t0) / dur), e = 1 - Math.pow(1 - x, 3);
        p.el.textContent = x < 1 ? fmt(Math.round(p.n * e)) : p.txt;
        if (x < 1) requestAnimationFrame(step); else p.el.classList.add("done");
      }
      requestAnimationFrame(step);
    });
  }

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
    if (panelOf(index).id === "p-tarifs") countPrices();
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

  // ------------------------------------------------------------ effets à la souris
  // Projecteur qui suit le pointeur sur les cartes, et écran d'accueil qui s'incline.
  document.addEventListener("pointermove", function (e) {
    var card = e.target.closest ? e.target.closest(".card") : null;
    if (card) {
      var r = card.getBoundingClientRect();
      card.style.setProperty("--mx", (e.clientX - r.left) + "px");
      card.style.setProperty("--my", (e.clientY - r.top) + "px");
    }
  }, { passive: true });
  var wrap = document.querySelector(".tv-wrap");
  var side = document.querySelector(".home-side");
  if (wrap && side && window.matchMedia("(hover: hover) and (min-width: 961px)").matches) {
    side.addEventListener("pointermove", function (e) {
      var r = side.getBoundingClientRect();
      var x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5;
      wrap.style.setProperty("--ry", (x * 16 - 4) + "deg");
      wrap.style.setProperty("--rx", (-y * 10 + 2) + "deg");
    });
    side.addEventListener("pointerleave", function () {
      wrap.style.removeProperty("--ry");
      wrap.style.removeProperty("--rx");
    });
  }
})();
