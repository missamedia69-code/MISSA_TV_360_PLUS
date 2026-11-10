# Usage : python3 outils-site/generer_accueil.py .   (depuis la racine du dépôt)
# Modifier les textes ici, puis relancer : site/index.html et site/en/index.html sont réécrits.
# Génère site/index.html (FR) et site/en/index.html (EN) : page d'accueil en onglets.
import sys
ROOT = sys.argv[1]
REL = "https://github.com/missamedia69-code/MISSA_TV_360_PLUS/releases/latest"
ICON = {
 "remote": '<path d="M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/><circle cx="12" cy="9" r="2.5"/><path d="M10 15h4M10 18h4"/>',
 "radio": '<path d="M4 9h16v11H4z"/><path d="M8 9l9-5"/><circle cx="15" cy="14.5" r="2.5"/><path d="M7 13h3M7 16h3"/>',
 "shield": '<path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
 "film": '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"/>',
 "music": '<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
 "live": '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M8 3l4 3 4-3"/><circle cx="12" cy="12" r="2"/>',
 "game": '<path d="M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6L14 15h-4l-1 .6A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z"/><path d="M7 11v3M5.5 12.5h3"/><circle cx="16" cy="12" r=".8"/><circle cx="18" cy="13.5" r=".8"/>',
}
def icon(k):
    return f'<svg class="ico" viewBox="0 0 24 24" aria-hidden="true">{ICON[k]}</svg>'
CHECK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l5 5L20 7"/></svg>'

L = {
 "fr": dict(
  lang="fr", base="", other="en/", other_label="EN", other_title="English version",
  title="MISSA TV 360 PLUS — L'application Android et Android TV",
  desc="MISSA TV 360 PLUS : une application claire pour téléphone, tablette et Android TV, pensée pour la télécommande. Radios et podcasts intégrés, contrôle parental. Essai gratuit 24 h.",
  skip="Aller au contenu", tabs_label="Sections du site", download="Télécharger",
  tabs=[("accueil","Accueil"),("univers","Univers"),("captures","Aperçu"),("tarifs","Tarifs"),("installer","Installer"),("faq","Questions")],
  aliases={"telecharger":"accueil","contenu":"accueil"},
  eyebrow="Essai gratuit 24 h", devices="Téléphone · Tablette · Android TV",
  h1a="Tout votre divertissement,", h1b="une seule application.",
  lead="Une interface claire, rangée en univers et pensée pour la télécommande. Radios et podcasts intégrés, musique, contrôle parental, et bientôt des jeux.",
  dl="Télécharger l'APK", how="Comment l'installer", meta="Android 6 et plus · Téléphone, tablette et TV",
  points=["Navigation à la télécommande","2 appareils par abonnement","Sans publicité"],
  shots=[("tv-radio.webp","Radio","L'univers Radio sur Android TV : filtres par région et lecteur Écoute Radio Directe"),
         ("tv-musique.webp","Musique","Le lecteur musical de l'application sur Android TV"),
         ("tv-podcasts.webp","Podcasts","L'univers Podcasts sur Android TV : filtres et recherche")],
  shot_note="Captures réelles de l'application sur Android TV. Les logos et visages de tiers sont floutés.",
  u_title="Tout est rangé, rien à chercher",
  u_lead="Chaque univers a sa place, comme dans l'application : vous savez toujours où vous êtes.",
  families=[("y","Vidéo",["Films &amp; Mangas","Séries","Documentaires","Courts-métrages","Enfants <small>code parental</small>"]),
            ("g","Audio",["Musiques","Podcasts <small>intégrés</small>","Radio directe <small>intégrée</small>"]),
            ("n","Direct",["TV en direct","Sports"]),
            ("g","Jeux",["Jeux MISSA <small>bientôt</small>"])],
  u_note="MISSA TV 360 PLUS est un lecteur : il ne fournit aucune source ni extension et ne contient aucun film, série ou chaîne. Les univers vidéo et direct affichent les contenus des sources que vous ajoutez vous-même.",
  feats=[("Pensée pour la télécommande","Un sélecteur vert toujours visible, et Retour revient là où vous étiez."),
         ("Radio et podcasts immédiats","Des stations et podcasts d'Afrique et du monde, sans rien configurer."),
         ("Espace Enfants protégé","Un code parental garde les plus jeunes dans leur univers.")],
  c_title="Sur votre TV, comme dans l'application", c_lead="Choisissez un univers pour le voir.",
  p_title="Essayez d'abord, abonnez-vous ensuite",
  p_lead="Un abonnement valable sur 2 appareils, par exemple votre téléphone et votre TV.",
  plans=[("n","Essai","Pour découvrir l'application.","Gratuit","pendant 24 h",["Toutes les fonctions","Téléphone, tablette ou TV"],None),
         ("g","De 1 à 9 mois","Payez le nombre de mois voulu.","1&nbsp;000","FCFA / mois",["2 appareils","Mises à jour incluses","Mobile Money ou carte"],None),
         ("y","1 an","12 mois pour le prix de 10.","10&nbsp;000","FCFA / an",["2 appareils","Mises à jour incluses","Mobile Money ou carte"],"Le plus avantageux")],
  p_cta="Commencer l'essai",
  p_fine="Au-delà de 9 mois, c'est la formule 1 an qui s'applique. L'abonnement donne accès aux fonctions de l'application : il ne comprend aucun film, série ni chaîne.",
  i_title="Installée en quelques minutes", i_lead="Directement depuis ce site, sur tous les appareils Android.",
  i_phone="Téléphone ou tablette",
  i_phone_steps=["Appuyez sur <strong>Télécharger l'APK</strong> depuis votre appareil.","Ouvrez le fichier téléchargé.","Si Android le demande, autorisez votre navigateur à <strong>installer des applications inconnues</strong>.","Appuyez sur <strong>Installer</strong>. C'est prêt."],
  i_tv="Android TV, box ou Fire TV",
  i_tv_steps=["Installez l'application gratuite <strong>Downloader</strong> depuis la boutique de la TV.","Dans Downloader, saisissez l'adresse :<br><code id=\"site-url\">missatv.pages.dev</code>","Sélectionnez <strong>Télécharger l'APK</strong> avec la télécommande.","Autorisez Downloader à installer des applications inconnues, puis <strong>Installer</strong>."],
  i_note="Les mises à jour s'installent par-dessus l'ancienne version : vos favoris et réglages sont conservés.",
  f_title="Questions fréquentes",
  faq=[("Combien coûte l'application ?","Vous l'essayez gratuitement pendant 24 heures. Ensuite, l'abonnement coûte 1 000 FCFA par mois (de 1 à 9 mois) ou 10 000 FCFA pour un an, pour 2 appareils, payable par Mobile Money ou carte via KPay."),
       ("Comment j'active l'application ?","À la fin de l'essai, l'application affiche un code et, sur TV, un QR code. Scannez-le ou ouvrez missatv.pages.dev/activer, choisissez la durée et payez : l'application se débloque toute seule en quelques secondes."),
       ("D'où viennent les films et les séries ?","MISSA TV 360 PLUS n'héberge et ne fournit aucun film, série ni chaîne, ni aucune source ou extension. Les univers vidéo se remplissent avec les extensions que vous choisissez d'ajouter, sous votre seule responsabilité. Radios et podcasts fonctionnent tout de suite."),
       ("Android affiche « application inconnue », est-ce normal ?","Oui : Android l'affiche pour toute application installée hors du Play Store. Téléchargez-la uniquement depuis ce site ou la page GitHub officielle."),
       ("Quels appareils sont compatibles ?","Téléphones, tablettes, Android TV, box Android et Fire TV sous Android 6 ou plus récent.")],
  foot="L'application de divertissement pour téléphone, tablette et Android TV.",
  legal=[("mentions-legales.html","Mentions légales"),("confidentialite.html","Confidentialité"),("contact.html","Contact"),("activer/","Activer mon appareil")],
  licence='Logiciel libre sous licence <a href="https://github.com/missamedia69-code/MISSA_TV_360_PLUS/blob/main/LICENSE">GPL-3.0</a>, basé sur <a href="https://github.com/recloudstream/cloudstream">CloudStream</a>.',
  prev="Capture précédente", next="Capture suivante",
 ),
 "en": dict(
  lang="en", base="../", other="../", other_label="FR", other_title="Version française",
  title="MISSA TV 360 PLUS — The Android and Android TV app",
  desc="MISSA TV 360 PLUS: a clear app for phones, tablets and Android TV, built for the remote. Built-in radio and podcasts, parental control. 24-hour free trial.",
  skip="Skip to content", tabs_label="Site sections", download="Download",
  tabs=[("home","Home"),("universes","Universes"),("preview","Preview"),("pricing","Pricing"),("install","Install"),("faq","FAQ")],
  aliases={"download":"home","content":"home"},
  eyebrow="24-hour free trial", devices="Phone · Tablet · Android TV",
  h1a="All your entertainment,", h1b="one single app.",
  lead="A clear interface, organised in universes and built for the remote. Built-in radio and podcasts, music, parental control, and games coming soon.",
  dl="Download the APK", how="How to install", meta="Android 6 and later · Phone, tablet and TV",
  points=["Remote-friendly navigation","2 devices per subscription","No ads"],
  shots=[("tv-radio.webp","Radio","The Radio universe on Android TV: region filters and the live radio player"),
         ("tv-musique.webp","Music","The app's music player on Android TV"),
         ("tv-podcasts.webp","Podcasts","The Podcasts universe on Android TV: filters and search")],
  shot_note="Real screenshots of the app on Android TV. Third-party logos and faces are blurred.",
  u_title="Everything in its place",
  u_lead="Each universe has its own spot, just like in the app: you always know where you are.",
  families=[("y","Video",["Movies &amp; Manga","Series","Documentaries","Short films","Kids <small>parental PIN</small>"]),
            ("g","Audio",["Music","Podcasts <small>built in</small>","Live radio <small>built in</small>"]),
            ("n","Live",["Live TV","Sports"]),
            ("g","Games",["MISSA games <small>coming soon</small>"])],
  u_note="MISSA TV 360 PLUS is a player: it provides no sources or extensions and contains no movies, series or channels. Video and live universes show content from the sources you add yourself.",
  feats=[("Built for the remote","A green selector always visible, and Back returns where you were."),
         ("Instant radio and podcasts","Stations and podcasts from Africa and the world, nothing to set up."),
         ("Protected Kids space","A parental PIN keeps the youngest in their universe.")],
  c_title="On your TV, just like in the app", c_lead="Pick a universe to see it.",
  p_title="Try first, subscribe later",
  p_lead="One subscription for 2 devices, for example your phone and your TV.",
  plans=[("n","Trial","To discover the app.","Free","for 24 hours",["All features","Phone, tablet or TV"],None),
         ("g","1 to 9 months","Pay for the months you want.","1,000","FCFA / month",["2 devices","Updates included","Mobile Money or card"],None),
         ("y","1 year","12 months for the price of 10.","10,000","FCFA / year",["2 devices","Updates included","Mobile Money or card"],"Best value")],
  p_cta="Start the trial",
  p_fine="Beyond 9 months, the 1-year plan applies. The subscription gives access to the app's features: it includes no movies, series or channels.",
  i_title="Installed in minutes", i_lead="Straight from this site, on any Android device.",
  i_phone="Phone or tablet",
  i_phone_steps=["Tap <strong>Download the APK</strong> on your device.","Open the downloaded file.","If Android asks, allow your browser to <strong>install unknown apps</strong>.","Tap <strong>Install</strong>. Done."],
  i_tv="Android TV, box or Fire TV",
  i_tv_steps=["Install the free <strong>Downloader</strong> app from your TV's store.","In Downloader, enter the address:<br><code id=\"site-url\">missatv.pages.dev/en</code>","Select <strong>Download the APK</strong> with the remote.","Allow Downloader to install unknown apps, then <strong>Install</strong>."],
  i_note="Updates install over the previous version: your favourites and settings are kept.",
  f_title="Frequently asked questions",
  faq=[("How much does the app cost?","Try it free for 24 hours. Then the subscription costs 1,000 FCFA per month (1 to 9 months) or 10,000 FCFA for a year, for 2 devices, payable by Mobile Money or card via KPay."),
       ("How do I activate the app?","When the trial ends, the app shows a code and, on TV, a QR code. Scan it or open missatv.pages.dev/activer, pick a duration and pay: the app unlocks by itself within seconds."),
       ("Where do movies and series come from?","MISSA TV 360 PLUS hosts and provides no movies, series or channels, and no sources or extensions. Video universes fill up with the extensions you choose to add, at your own responsibility. Radio and podcasts work right away."),
       ("Android says “unknown app”, is that normal?","Yes: Android shows it for any app installed outside the Play Store. Only download it from this site or the official GitHub page."),
       ("Which devices are supported?","Phones, tablets, Android TV, Android boxes and Fire TV running Android 6 or later.")],
  foot="The entertainment app for phones, tablets and Android TV.",
  legal=[("legal-notice.html","Legal notice"),("privacy.html","Privacy"),("contact.html","Contact"),("../activer/","Activate my device")],
  licence='Free software under the <a href="https://github.com/missamedia69-code/MISSA_TV_360_PLUS/blob/main/LICENSE">GPL-3.0</a> licence, based on <a href="https://github.com/recloudstream/cloudstream">CloudStream</a>.',
  prev="Previous screenshot", next="Next screenshot",
 ),
}

def page(t):
    b = t["base"]
    tabs = t["tabs"]
    tid = [x[0] for x in tabs]
    o = []
    a = o.append
    a(f'''<!DOCTYPE html>
<html lang="{t["lang"]}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{t["title"]}</title>
  <meta name="description" content="{t["desc"]}">
  <meta name="theme-color" content="#050608">
  <link rel="alternate" hreflang="fr" href="https://missatv.pages.dev/">
  <link rel="alternate" hreflang="en" href="https://missatv.pages.dev/en/">
  <meta property="og:title" content="MISSA TV 360 PLUS">
  <meta property="og:description" content="{t["desc"]}">
  <meta property="og:type" content="website">
  <meta property="og:image" content="https://missatv.pages.dev/assets/captures/tv-radio.webp">
  <link rel="icon" type="image/png" href="{b}assets/favicon.png">
  <link rel="apple-touch-icon" href="{b}assets/apple-touch-icon.png">
  <link rel="stylesheet" href="{b}accueil.css">
  <script>document.documentElement.classList.add("js");</script>
</head>
<body>
<a class="skip" href="#p-{tid[0]}">{t["skip"]}</a>
<div class="glow" aria-hidden="true"></div>

<header class="bar">
  <a class="logo" href="./" aria-label="MISSA TV 360 PLUS">
    <img src="{b}assets/favicon.png" alt="" width="36" height="36">
    <span>MISSA TV <b>360+</b></span>
  </a>
  <nav class="tabs" role="tablist" aria-label="{t["tabs_label"]}">
''')
    for i, (k, lab) in enumerate(tabs):
        a(f'    <a class="tab" role="tab" id="t-{k}" href="#{k}" aria-controls="p-{k}" aria-selected="{"true" if i == 0 else "false"}" data-i="{i}">{lab}</a>\n')
    a(f'''    <span class="tab-ink" aria-hidden="true"></span>
  </nav>
  <div class="bar-end">
    <a class="lang" href="{t["other"]}" hreflang="{"en" if t["lang"]=="fr" else "fr"}" title="{t["other_title"]}">{t["other_label"]}</a>
    <a class="btn btn-g btn-sm" href="{REL}" data-apk>{t["download"]}</a>
  </div>
</header>

<main class="stage" data-aliases='{str(t["aliases"]).replace("'", '"')}'>
''')
    # --- Accueil
    shots = t["shots"]
    a(f'''
  <section class="panel panel-home is-active" id="p-{tid[0]}" role="tabpanel" aria-labelledby="t-{tid[0]}">
    <div class="hero-copy">
      <p class="eyebrow"><span class="chip-g">{t["eyebrow"]}</span><span>{t["devices"]}</span></p>
      <h1>{t["h1a"]}<br><span class="grad">{t["h1b"]}</span></h1>
      <p class="lead">{t["lead"]}</p>
      <div class="cta">
        <a class="btn btn-primary" id="download-btn" href="{REL}">{t["dl"]}</a>
        <a class="btn btn-ghost" href="#{tid[4]}">{t["how"]}</a>
      </div>
      <p class="meta" id="release-meta" aria-live="polite">{t["meta"]}</p>
    </div>
    <div class="home-side">
      <div class="tv-wrap"><div class="tv" data-carousel>
''')
    for i, (f, lab, alt) in enumerate(shots):
        a(f'        <img class="tv-shot{" on" if i == 0 else ""}" src="{b}assets/captures/{f}" alt="{alt}" width="1304" height="786"{"" if i == 0 else " loading=\"lazy\""}>\n')
    a('      </div></div>\n      <ul class="points">\n')
    for p in t["points"]:
        a(f'        <li>{CHECK}{p}</li>\n')
    a('      </ul>\n    </div>\n    <div class="feats">\n')
    for i, (h, p) in enumerate(t["feats"]):
        a(f'      <article class="card feat acc-{"gyn"[i]}">{icon(["remote","radio","shield"][i])}<h2>{h}</h2><p>{p}</p></article>\n')
    names = [x for fam in t["families"] for x in fam[2]]
    row = "".join(f'<span>{x.split(" <small>")[0]}</span>' for x in names)
    a('    </div>\n    <div class="marquee" aria-hidden="true"><div class="track">' + row + row + '</div></div>\n  </section>\n')
    # --- Univers
    a(f'''
  <section class="panel" id="p-{tid[1]}" role="tabpanel" aria-labelledby="t-{tid[1]}">
    <div class="head"><h2>{t["u_title"]}</h2><p>{t["u_lead"]}</p></div>
    <div class="fams">
''')
    for c, name, items in t["families"]:
        ik = {"Vidéo":"film","Video":"film","Audio":"music","Direct":"live","Live":"live","Jeux":"game","Games":"game"}[name]
        a(f'      <section class="card fam acc-{c}">{icon(ik)}<h3>{name}</h3><ul>' + "".join(f"<li>{x}</li>" for x in items) + '</ul></section>\n')
    a(f'''    </div>
    <p class="note">{t["u_note"]}</p>
  </section>
''')
    # --- Aperçu
    a(f'''
  <section class="panel" id="p-{tid[2]}" role="tabpanel" aria-labelledby="t-{tid[2]}">
    <div class="head"><h2>{t["c_title"]}</h2><p>{t["c_lead"]}</p></div>
    <div class="viewer">
      <div class="picks" role="group">
''')
    for i, (f, lab, alt) in enumerate(shots):
        a(f'        <button type="button" class="pick{" on" if i == 0 else ""}" data-shot="{i}" aria-pressed="{"true" if i == 0 else "false"}">{lab}</button>\n')
    a('      </div>\n      <div class="tv tv-big" data-viewer>\n')
    for i, (f, lab, alt) in enumerate(shots):
        a(f'        <img class="tv-shot{" on" if i == 0 else ""}" src="{b}assets/captures/{f}" alt="{alt}" width="1304" height="786" loading="lazy">\n')
    a(f'''        <button type="button" class="nav nav-prev" aria-label="{t["prev"]}">‹</button>
        <button type="button" class="nav nav-next" aria-label="{t["next"]}">›</button>
      </div>
      <p class="note">{t["shot_note"]}</p>
    </div>
  </section>
''')
    # --- Tarifs
    a(f'''
  <section class="panel" id="p-{tid[3]}" role="tabpanel" aria-labelledby="t-{tid[3]}">
    <div class="head"><h2>{t["p_title"]}</h2><p>{t["p_lead"]}</p></div>
    <div class="plans">
''')
    for c, name, desc, amount, per, items, badge in t["plans"]:
        bd = f'<span class="badge">{badge}</span>' if badge else ""
        a(f'''      <article class="card plan plan-{c}">{bd}
        <h3>{name}</h3><p class="pdesc">{desc}</p>
        <p class="price"><b>{amount}</b> <span>{per}</span></p>
        <ul>{"".join(f"<li>{CHECK}{x}</li>" for x in items)}</ul>
        <a class="btn {"btn-primary" if c == "y" else "btn-ghost"}" href="{REL}" data-apk>{t["p_cta"]}</a>
      </article>
''')
    a(f'''    </div>
    <p class="note">{t["p_fine"]}</p>
  </section>
''')
    # --- Installer
    a(f'''
  <section class="panel" id="p-{tid[4]}" role="tabpanel" aria-labelledby="t-{tid[4]}">
    <div class="head"><h2>{t["i_title"]}</h2><p>{t["i_lead"]}</p></div>
    <div class="steps">
      <article class="card step-card acc-g"><h3>{t["i_phone"]}</h3><ol>{"".join(f"<li>{x}</li>" for x in t["i_phone_steps"])}</ol></article>
      <article class="card step-card acc-y"><h3>{t["i_tv"]}</h3><ol>{"".join(f"<li>{x}</li>" for x in t["i_tv_steps"])}</ol></article>
    </div>
    <div class="cta center">
      <a class="btn btn-primary" id="download-btn-2" href="{REL}">{t["dl"]}</a>
    </div>
    <p class="note">{t["i_note"]}</p>
  </section>
''')
    # --- FAQ
    a(f'''
  <section class="panel" id="p-{tid[5]}" role="tabpanel" aria-labelledby="t-{tid[5]}">
    <div class="head"><h2>{t["f_title"]}</h2></div>
    <div class="faq">
''')
    for q, r in t["faq"]:
        a(f'      <details><summary>{q}</summary><p>{r}</p></details>\n')
    a('    </div>\n  </section>\n</main>\n')
    # --- footer
    a(f'''
<footer class="foot">
  <p>{t["foot"]}</p>
  <nav>{" · ".join(f'<a href="{h}">{l}</a>' for h, l in t["legal"])}</nav>
  <p class="small">MISSA TV 360 PLUS · {t["licence"]}</p>
</footer>

<script src="{b}app.js" defer></script>
<script src="{b}accueil.js" defer></script>
</body>
</html>
''')
    return "".join(o)

open(ROOT + "/site/index.html", "w").write(page(L["fr"]))
open(ROOT + "/site/en/index.html", "w").write(page(L["en"]))
print("ok")
