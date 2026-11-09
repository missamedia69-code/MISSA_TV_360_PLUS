#!/usr/bin/env python3
"""Tient à jour game/catalogue.json, le catalogue de l'univers Jeux de MISSA TV.

L'application lit ce catalogue sur la branche main, puis télécharge à la demande :
  - le moteur d'émulation (une seule fois, partagé par toutes les consoles) ;
  - l'émulateur de la console du jeu (une seule fois par console) ;
  - le jeu lui-même.
Chaque fichier est vérifié par son empreinte SHA-256 avant d'être utilisé.

Usage :
  python3 game/outils/catalogue.py             remplit url, fileName, bytes, sha256
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

# Consoles prises en charge et cœur EmulatorJS attendu. Seuls des cœurs dont la licence
# autorise un usage commercial (MISSA TV est payante) : pas de Snes9x ni de Genesis Plus GX.
CONSOLES = {
    "nes": {"core": "fceumm", "extensions": {"nes"}},
    "gb": {"core": "gambatte", "extensions": {"gb", "gbc"}},
    "gba": {"core": "mgba", "extensions": {"gba"}},
}
TAILLE_MAX_JEU = 32 * 1024 * 1024


def lire(chemin):
    with open(os.path.join(RACINE, chemin), "rb") as f:
        return f.read()


def completer(entree, erreurs, nom):
    chemin = entree.get("path", "")
    if not chemin or not os.path.isfile(os.path.join(RACINE, chemin)):
        erreurs.append(f"{nom}: fichier introuvable ({chemin})")
        return entree
    octets = lire(chemin)
    entree["fileName"] = os.path.basename(chemin)
    entree["url"] = BASE_URL + chemin
    entree["bytes"] = len(octets)
    entree["sha256"] = hashlib.sha256(octets).hexdigest()
    for cle in ("licenceFile", "sourceArchive"):
        if entree.get(cle) and not os.path.isfile(os.path.join(RACINE, entree[cle])):
            erreurs.append(f"{nom}: {cle} introuvable ({entree[cle]})")
    if "GPL" in entree.get("licence", "") and not entree.get("sourceArchive"):
        erreurs.append(f"{nom}: sous GPL sans archive des sources (sourceArchive)")
    if not entree.get("licence") or not entree.get("licenceFile"):
        erreurs.append(f"{nom}: licence manquante")
    return entree


def contenu_zip(chemin, erreurs, nom):
    try:
        with zipfile.ZipFile(os.path.join(RACINE, chemin)) as z:
            if z.testzip() is not None:
                erreurs.append(f"{nom}: archive corrompue")
            return set(z.namelist())
    except (OSError, zipfile.BadZipFile) as e:
        erreurs.append(f"{nom}: archive illisible ({e})")
        return set()


def entete_valide(chemin, octets):
    ext = chemin.rsplit(".", 1)[-1].lower()
    if ext in ("gb", "gbc"):
        return octets[0x104:0x108] == bytes.fromhex("CEED6666")  # début du logo Nintendo
    if ext == "nes":
        return octets[:4] == b"NES\x1a"
    if ext == "gba":
        return len(octets) > 0xC0 and octets[3] == 0xEA
    return False


def main():
    verifier = "--verifier" in sys.argv[1:]
    with open(CATALOGUE, encoding="utf-8") as f:
        cat = json.load(f)
    erreurs = []

    moteur = completer(cat["moteur"], erreurs, "moteur")
    fichiers = contenu_zip(moteur.get("path", ""), erreurs, "moteur")
    for attendu in ("lanceur.html", "data/loader.js", "data/emulator.min.js", "data/compression/extract7z.js"):
        if attendu not in fichiers:
            erreurs.append(f"moteur: {attendu} absent de l'archive")

    systemes = set()
    for emu in cat["emulateurs"]:
        nom = "émulateur " + emu.get("system", "?")
        completer(emu, erreurs, nom)
        conf = CONSOLES.get(emu.get("system"))
        if not conf:
            erreurs.append(f"{nom}: console non prise en charge")
            continue
        if emu.get("core") != conf["core"]:
            erreurs.append(f"{nom}: cœur {emu.get('core')} au lieu de {conf['core']}")
        fichiers = contenu_zip(emu.get("path", ""), erreurs, nom)
        for attendu in (f"data/cores/{conf['core']}-wasm.data",
                        f"data/cores/{conf['core']}-legacy-wasm.data",
                        f"data/cores/reports/{conf['core']}.json"):
            if attendu not in fichiers:
                erreurs.append(f"{nom}: {attendu} absent de l'archive")
        systemes.add(emu.get("system"))

    ids = set()
    for jeu in cat["jeux"]:
        nom = jeu.get("id", "?")
        for cle in ("id", "title", "system", "genre", "author", "description", "path"):
            if not str(jeu.get(cle, "")).strip():
                erreurs.append(f"{nom}: champ « {cle} » manquant")
        if nom in ids:
            erreurs.append(f"{nom}: identifiant en double")
        ids.add(nom)
        completer(jeu, erreurs, nom)
        if jeu.get("cover"):
            if os.path.isfile(os.path.join(RACINE, jeu["cover"])):
                jeu["coverUrl"] = BASE_URL + jeu["cover"]
            else:
                erreurs.append(f"{nom}: pochette introuvable ({jeu['cover']})")
        systeme = jeu.get("system")
        if systeme not in systemes:
            erreurs.append(f"{nom}: aucun émulateur pour la console {systeme}")
        chemin = jeu.get("path", "")
        if os.path.isfile(os.path.join(RACINE, chemin)):
            ext = chemin.rsplit(".", 1)[-1].lower()
            if ext not in CONSOLES.get(systeme, {}).get("extensions", set()):
                erreurs.append(f"{nom}: extension .{ext} incompatible avec {systeme}")
            octets = lire(chemin)
            if not entete_valide(chemin, octets):
                erreurs.append(f"{nom}: en-tête de ROM invalide")
            if len(octets) > TAILLE_MAX_JEU:
                erreurs.append(f"{nom}: jeu trop lourd")

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
    print(f"{len(cat['jeux'])} jeux, {len(cat['emulateurs'])} émulateurs : OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
