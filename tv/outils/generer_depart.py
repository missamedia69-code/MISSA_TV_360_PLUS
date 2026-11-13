#!/usr/bin/env python3
"""
Génère tv/chaines.m3u, la liste de départ de l'univers TV en direct.

Source : les listes publiques du projet iptv-org (https://github.com/iptv-org/iptv), qui ne
référencent que des flux diffusés librement par les chaînes elles-mêmes. On garde :
  - les chaînes des pays d'Afrique francophone (et quelques voisins) ;
  - quelques chaînes d'information internationales en français et en anglais.
Les chaînes marquées « Geo-blocked » (bloquées hors de leur pays) ou « Not 24/7 »
(pas toujours en direct) sont écartées.

Chaque chaîne reçoit :
  - group-title : sa catégorie, en français (Actualités, Musique...) ;
  - tvg-country : le code ISO de son pays (CM, CI...), pour l'onglet « Pays ».

Usage :  python3 tv/outils/generer_depart.py
Ce fichier peut ensuite être modifié ou remplacé à la main : l'application lit simplement
tv/chaines.m3u sur la branche main.
"""
import re
import sys
import urllib.request
from pathlib import Path

PAYS = ["CM", "CI", "SN", "CD", "CG", "GA", "BJ", "TG", "BF", "ML", "GN", "NE", "TD", "CF",
        "HT", "MA", "DZ", "TN", "NG", "GH", "RW", "BI", "MG"]

INFOS_INTERNATIONALES = {
    "France24.fr@French": "FR", "France24.fr@English": "FR", "TV5MondeInfo.fr@SD": "FR",
    "EuronewsFrench.fr@SD": "FR", "EuronewsEnglish.fr@SD": "FR", "AfricanewsFrench.fr@SD": "FR",
    "AfricanewsEnglish.fr@SD": "FR", "DW.de@English": "DE", "AlJazeera.qa@English": "QA",
    "CGTNFrench.cn@SD": "CN", "i24NEWSFrench.il@SD": "IL", "Franceinfo.fr@SD": "FR",
}

CATEGORIES = {
    "News": "Actualités", "General": "Généraliste", "Music": "Musique", "Religious": "Religion",
    "Movies": "Films", "Entertainment": "Divertissement", "Sports": "Sport", "Kids": "Jeunesse",
    "Culture": "Culture", "Documentary": "Documentaires", "Education": "Éducation",
    "Series": "Séries", "Lifestyle": "Art de vivre", "Business": "Économie", "Comedy": "Humour",
    "Legislative": "Institutions", "Animation": "Jeunesse", "Family": "Famille",
    "Classic": "Classiques", "Cooking": "Cuisine", "Travel": "Voyage", "Outdoor": "Nature",
    "Auto": "Auto", "Science": "Sciences", "Weather": "Météo", "Shop": "Téléachat",
}

SORTIE = Path(__file__).resolve().parents[1] / "chaines.m3u"


def lire(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def entrees(texte):
    """Rend (ligne EXTINF, lignes d'options, url) pour chaque chaîne."""
    lignes = [l.strip() for l in texte.splitlines()]
    extinf, options = None, []
    for l in lignes:
        if l.startswith("#EXTINF"):
            extinf, options = l, []
        elif l.startswith("#EXTVLCOPT") and extinf:
            options.append(l)
        elif l and not l.startswith("#") and extinf:
            yield extinf, options, l
            extinf, options = None, []


def attribut(extinf, nom):
    m = re.search(r'%s="([^"]*)"' % re.escape(nom), extinf)
    return m.group(1) if m else ""


def nettoyer_nom(nom):
    nom = re.sub(r"\s*\((\d{3,4}p|\d+p)\)", "", nom)
    nom = re.sub(r"\s*\[[^\]]*\]", "", nom)
    return nom.strip()


def convertir(extinf, options, url, pays):
    nom_brut = extinf.rsplit(",", 1)[-1]
    if "Geo-blocked" in nom_brut or "Not 24/7" in nom_brut:
        return None
    nom = nettoyer_nom(nom_brut)
    groupes = [CATEGORIES.get(g.strip(), g.strip()) for g in attribut(extinf, "group-title").split(";") if g.strip()]
    groupe = groupes[0] if groupes else "Généraliste"
    if groupe == "Undefined":
        groupe = "Généraliste"
    attrs = [
        'tvg-id="%s"' % attribut(extinf, "tvg-id"),
        'tvg-logo="%s"' % attribut(extinf, "tvg-logo"),
        'tvg-country="%s"' % pays,
        'group-title="%s"' % groupe,
    ]
    for opt in ("http-referrer", "http-user-agent"):
        v = attribut(extinf, opt)
        if v:
            attrs.append('%s="%s"' % (opt, v))
    lignes = ["#EXTINF:-1 %s,%s" % (" ".join(attrs), nom)] + options + [url]
    return nom.lower(), "\n".join(lignes)


def main():
    blocs, vus = [], set()
    for code in PAYS:
        try:
            texte = lire("https://iptv-org.github.io/iptv/countries/%s.m3u" % code.lower())
        except Exception as e:  # pays absent de l'annuaire
            print("  %s : %s" % (code, e), file=sys.stderr)
            continue
        n = 0
        for extinf, options, url in entrees(texte):
            r = convertir(extinf, options, url, code)
            if r and url not in vus:
                vus.add(url)
                blocs.append(r[1])
                n += 1
        print("%s : %d chaînes" % (code, n))
    infos = lire("https://iptv-org.github.io/iptv/categories/news.m3u")
    n = 0
    for extinf, options, url in entrees(infos):
        tid = attribut(extinf, "tvg-id")
        if tid in INFOS_INTERNATIONALES and url not in vus:
            r = convertir(extinf, options, url, INFOS_INTERNATIONALES[tid])
            if r:
                vus.add(url)
                blocs.append(r[1].replace('group-title="Actualités"', 'group-title="Info internationale"'))
                n += 1
    print("Info internationale : %d chaînes" % n)
    entete = ("#EXTM3U\n"
              "# MISSA TV 360 PLUS — liste des chaînes de l'univers TV en direct.\n"
              "# Modifiez ce fichier sur la branche main : l'application le relit à chaque démarrage.\n")
    SORTIE.write_text(entete + "\n".join(blocs) + "\n", encoding="utf-8")
    print("%d chaînes écrites dans %s" % (len(blocs), SORTIE))


if __name__ == "__main__":
    main()
