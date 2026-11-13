#!/usr/bin/env python3
"""Tient à jour game/catalogue.json, le catalogue de l'univers Jeux (Neo Geo) de MISSA TV.

L'application lit ce catalogue sur la branche main à chaque démarrage, puis télécharge à la
demande, la première fois qu'un jeu est lancé :
  - le moteur Neo Geo de l'architecture de l'appareil (une seule fois) ;
  - le BIOS Neo Geo (une seule fois) ;
  - le jeu (.neo).
Chaque fichier est vérifié par son empreinte SHA-256 avant d'être utilisé.

Usage :
  python3 game/outils/catalogue.py             remplit url, fileName, bytes, sha256, bannerUrl
  python3 game/outils/catalogue.py --verifier  échoue si le catalogue n'est pas à jour (CI)
"""
import hashlib
import json
import os
import sys
import zipfile

RACINE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOGUE = os.path.join(RACINE, "game", "catalogue.json")
BASE_URL = "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/"

# Architectures Android pour lesquelles un moteur peut être fourni.
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
# Fichiers attendus dans chaque paquet moteur.
FICHIERS_MOTEUR = ("liblibretrodroid.so", "libgeolith.so")
# BIOS reconnus par Geolith : neogeo.zip (mode MVS, arcade) et aes.zip (mode AES, console).
BIOS_CONNUS = {"neogeo.zip": "mvs", "aes.zip": "aes"}
TAILLE_MAX = 160 * 1024 * 1024


def lire(chemin):
    with open(os.path.join(RACINE, chemin), "rb") as f:
        return f.read()


def externe(entree):
    """Fichier hébergé hors du dépôt (Release GitHub, stockage privé...) : pas de « path »,
    mais une adresse HTTPS, sa taille et son empreinte, renseignées à la main."""
    return not entree.get("path") and str(entree.get("url", "")).startswith("https://")


def completer(entree, erreurs, nom, garder_nom=False):
    if externe(entree):
        if not entree.get("fileName"):
            entree["fileName"] = entree["url"].rsplit("/", 1)[-1].split("?")[0]
        sha = str(entree.get("sha256", ""))
        if len(sha) != 64 or not all(c in "0123456789abcdef" for c in sha.lower()):
            erreurs.append(f"{nom}: fichier externe sans empreinte sha256 valide")
        if not isinstance(entree.get("bytes"), int) or not 0 < entree["bytes"] <= TAILLE_MAX:
            erreurs.append(f"{nom}: fichier externe sans taille (bytes) valide")
        return entree
    chemin = entree.get("path", "")
    if not chemin or not os.path.isfile(os.path.join(RACINE, chemin)):
        erreurs.append(f"{nom}: fichier introuvable ({chemin})")
        return entree
    octets = lire(chemin)
    if len(octets) > TAILLE_MAX:
        erreurs.append(f"{nom}: fichier trop lourd ({len(octets)} octets)")
    if not garder_nom:
        entree["fileName"] = os.path.basename(chemin)
    entree["url"] = BASE_URL + chemin
    entree["bytes"] = len(octets)
    entree["sha256"] = hashlib.sha256(octets).hexdigest()
    return entree


def main():
    verifier = "--verifier" in sys.argv[1:]
    with open(CATALOGUE, encoding="utf-8") as f:
        cat = json.load(f)
    erreurs = []

    if cat.get("console") != "neogeo":
        erreurs.append("console : seule « neogeo » est prise en charge")

    moteur = cat.get("moteur", {})
    abis = moteur.get("abis", {})
    if not abis:
        erreurs.append("moteur : aucune architecture")
    for abi, paquet in abis.items():
        nom = f"moteur {abi}"
        if abi not in ABIS:
            erreurs.append(f"{nom}: architecture inconnue")
        completer(paquet, erreurs, nom)
        try:
            with zipfile.ZipFile(os.path.join(RACINE, paquet.get("path", ""))) as z:
                noms = set(z.namelist())
                if z.testzip() is not None:
                    erreurs.append(f"{nom}: archive corrompue")
            for attendu in FICHIERS_MOTEUR:
                if attendu not in noms:
                    erreurs.append(f"{nom}: {attendu} absent de l'archive")
        except (OSError, zipfile.BadZipFile) as e:
            erreurs.append(f"{nom}: archive illisible ({e})")
    for cle in ("licences", "sources"):
        for chemin in moteur.get(cle, []):
            if not os.path.isfile(os.path.join(RACINE, chemin)):
                erreurs.append(f"moteur: {cle} introuvable ({chemin})")

    mode = cat.get("mode", "mvs")
    if mode not in ("mvs", "aes"):
        erreurs.append("mode : « mvs » ou « aes »")
    noms_bios = set()
    for bios in cat.get("bios", []):
        nom_fichier = bios.get("fileName") or os.path.basename(bios.get("path", ""))
        bios["fileName"] = nom_fichier
        nom = f"BIOS {nom_fichier}"
        completer(bios, erreurs, nom, garder_nom=True)
        if nom_fichier not in BIOS_CONNUS:
            erreurs.append(f"{nom}: nom inattendu (neogeo.zip ou aes.zip)")
        if not bios.get("licence"):
            erreurs.append(f"{nom}: indiquez la licence ou le contrat qui autorise sa diffusion")
        noms_bios.add(nom_fichier)
    bios_requis = "neogeo.zip" if mode == "mvs" else "aes.zip"
    if cat.get("jeux") and bios_requis not in noms_bios:
        erreurs.append(f"jeux présents mais BIOS {bios_requis} absent (mode {mode})")

    ids = set()
    for jeu in cat.get("jeux", []):
        nom = jeu.get("id", "?")
        for cle in ("id", "title", "genre", "author", "description", "licence"):
            if not str(jeu.get(cle, "")).strip():
                erreurs.append(f"{nom}: champ « {cle} » manquant")
        if nom in ids:
            erreurs.append(f"{nom}: identifiant en double")
        ids.add(nom)
        chemin = jeu.get("path", "")
        if not (chemin or jeu.get("url", "").split("?")[0]).lower().endswith(".neo"):
            erreurs.append(f"{nom}: le jeu doit être au format .neo")
        completer(jeu, erreurs, nom)
        if chemin and os.path.isfile(os.path.join(RACINE, chemin)) and lire(chemin)[:4] != b"NEO\x01":
            erreurs.append(f"{nom}: en-tête .neo invalide (attendu « NEO » + 1)")
        if jeu.get("banner"):
            if os.path.isfile(os.path.join(RACINE, jeu["banner"])):
                jeu["bannerUrl"] = BASE_URL + jeu["banner"]
            else:
                erreurs.append(f"{nom}: bannière introuvable ({jeu['banner']})")

    texte = json.dumps(cat, ensure_ascii=False, indent=2) + "\n"
    if verifier:
        with open(CATALOGUE, encoding="utf-8") as f:
            if f.read() != texte:
                erreurs.append("game/catalogue.json n'est pas à jour : lancez python3 game/outils/catalogue.py")
    if erreurs:
        print("\n".join("ERREUR " + e for e in erreurs))
        return 1
    if not verifier:
        with open(CATALOGUE, "w", encoding="utf-8") as f:
            f.write(texte)
    print(f"{len(cat.get('jeux', []))} jeux, {len(cat.get('bios', []))} BIOS, {len(abis)} moteurs : OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
