#!/usr/bin/env python3
"""Dépôt d'extensions MISSA TV : publie vos propres fichiers .cs3.

Déposez vos extensions dans extensions/depot/ (fichiers .cs3), décrivez-les dans
extensions/depot/extensions.json, puis lancez :

  python3 extensions/outils/depot.py            génère repo.json, plugins.json et met à jour
                                                extensions/config.json
  python3 extensions/outils/depot.py --verifier échoue si ces fichiers ne sont pas à jour (CI)

L'application lit extensions/config.json au démarrage : elle ajoute ce dépôt, installe chaque
extension et applique ses univers. Changer le numéro « version » d'une extension (dans son
manifest, donc en la recompilant) la fait mettre à jour sur tous les appareils.
"""
import hashlib
import json
import os
import sys
import zipfile

RACINE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEPOT = os.path.join(RACINE, "extensions", "depot")
META = os.path.join(DEPOT, "extensions.json")
REPO_JSON = os.path.join(DEPOT, "repo.json")
PLUGINS_JSON = os.path.join(DEPOT, "plugins.json")
CONFIG = os.path.join(RACINE, "extensions", "config.json")
# Adresse principale : le site Cloudflare (serveurs en Afrique). L'application se rabat
# automatiquement sur la même adresse chez GitHub (branche main) si le site ne répond pas.
BASE_URL = "https://missatv.pages.dev/extensions/depot/"
REPO_URL = BASE_URL + "repo.json"
UNIVERS = {"films", "series", "shorts", "sports", "podcasts", "none"}
TYPES = {"Movie", "AnimeMovie", "TvSeries", "Cartoon", "Anime", "OVA", "Torrent", "Documentary",
         "AsianDrama", "Live", "Others", "Music", "AudioBook", "Audio", "Podcast", "Video"}


def lire_manifest(chemin):
    with zipfile.ZipFile(chemin) as z:
        if z.testzip() is not None:
            raise ValueError("archive corrompue")
        noms = set(z.namelist())
        if "manifest.json" not in noms:
            raise ValueError("pas de manifest.json : ce n'est pas une extension CloudStream")
        if not any(n.endswith(".dex") for n in noms):
            raise ValueError("pas de classes.dex : extension non compilée")
        return json.loads(z.read("manifest.json").decode("utf-8"))


def charger_json(chemin, defaut):
    if not os.path.isfile(chemin):
        return defaut
    with open(chemin, encoding="utf-8") as f:
        return json.load(f)


def texte(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def main():
    verifier = "--verifier" in sys.argv[1:]
    meta = charger_json(META, [])
    erreurs, plugins, entrees = [], [], []
    fichiers = sorted(f for f in os.listdir(DEPOT) if f.endswith(".cs3")) if os.path.isdir(DEPOT) else []
    decrits = {m.get("fichier") for m in meta}
    for f in fichiers:
        if f not in decrits:
            erreurs.append(f"{f} : à décrire dans extensions/depot/extensions.json")
    for m in meta:
        f = m.get("fichier", "")
        chemin = os.path.join(DEPOT, f)
        if not f.endswith(".cs3") or not os.path.isfile(chemin):
            erreurs.append(f"{f or '?'} : fichier .cs3 introuvable dans extensions/depot/")
            continue
        try:
            man = lire_manifest(chemin)
        except Exception as e:  # zip illisible, manifest absent...
            erreurs.append(f"{f} : {e}")
            continue
        interne = str(man.get("name") or os.path.splitext(f)[0]).strip()
        version = man.get("version")
        if not isinstance(version, int) or version < 1:
            erreurs.append(f"{f} : « version » du manifest absente ou invalide")
            continue
        univers = m.get("univers", [])
        if any(u not in UNIVERS for u in univers):
            erreurs.append(f"{f} : univers inconnu (permis : {', '.join(sorted(UNIVERS))})")
        types = m.get("types", [])
        if any(t not in TYPES for t in types):
            erreurs.append(f"{f} : type inconnu (permis : {', '.join(sorted(TYPES))})")
        with open(chemin, "rb") as fh:
            octets = fh.read()
        plugins.append({
            "url": BASE_URL + f,
            "status": 1,
            "version": version,
            "apiVersion": 1,
            "name": m.get("nom") or interne,
            "internalName": interne,
            "authors": m.get("auteurs") or ["MISSA Media"],
            "description": m.get("description"),
            "repositoryUrl": REPO_URL,
            "tvTypes": types or None,
            "language": m.get("langue") or "fr",
            "iconUrl": m.get("icone"),
            "fileSize": len(octets),
            "fileHash": hashlib.sha256(octets).hexdigest(),
        })
        entrees.append({"depot": REPO_URL, "nomDepot": "MISSA TV", "extension": interne,
                        "nom": m.get("nom") or interne, "univers": univers})

    repo = {"name": "MISSA TV", "description": "Extensions officielles de MISSA TV 360 PLUS",
            "manifestVersion": 1, "pluginLists": [BASE_URL + "plugins.json"], "iconUrl": None}

    # config.json : on remplace les entrées de ce dépôt, on garde celles des autres dépôts.
    config = charger_json(CONFIG, {"version": 1, "extensions": []})
    # Le dépôt MISSA, sous l'une ou l'autre de ses adresses (site ou GitHub), est remplacé.
    anciens = {REPO_URL, "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/extensions/depot/repo.json"}
    autres = [e for e in config.get("extensions", []) if e.get("depot") not in anciens]
    # « exclusif » conservé tel quel (export complet de l'appli) ; absent = ajout seulement :
    # les appareils installent ces extensions sans retirer les autres.
    config = {"version": 1, "exclusif": bool(config.get("exclusif", False)), "extensions": autres + entrees}

    sorties = {REPO_JSON: texte(repo), PLUGINS_JSON: texte(plugins)}
    if plugins or os.path.isfile(CONFIG):
        sorties[CONFIG] = texte(config)
    if not plugins and not os.path.isfile(REPO_JSON):
        sorties = {}  # rien à publier tant qu'aucune extension n'est déposée

    if verifier:
        for chemin, contenu in sorties.items():
            if charger_json(chemin, None) is None or open(chemin, encoding="utf-8").read() != contenu:
                erreurs.append(f"{os.path.relpath(chemin, RACINE)} n'est pas à jour : lancez python3 extensions/outils/depot.py")
    if erreurs:
        print("\n".join("ERREUR " + e for e in erreurs))
        return 1
    if not verifier:
        for chemin, contenu in sorties.items():
            with open(chemin, "w", encoding="utf-8") as fh:
                fh.write(contenu)
    print(f"{len(plugins)} extension(s) MISSA : OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
