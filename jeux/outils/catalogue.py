#!/usr/bin/env python3
"""Tient à jour retro-catalog.json, le catalogue des jeux de l'univers Jeux de MISSA TV.

Pour ajouter un jeu :
  1. déposez la ROM dans jeux/roms/<console>/ et sa licence dans jeux/licences/ ;
  2. ajoutez une entrée dans retro-catalog.json avec au moins id, title, systemId,
     platformLabel, genre, description, path, sourceUrl, licenceNote, licenceFile ;
  3. lancez :  python3 jeux/outils/catalogue.py
     Le script remplit fileName, downloadUrl, estimatedBytes, sha256 et sourceArchiveUrl.

En CI :  python3 jeux/outils/catalogue.py --verifier
  échoue si un fichier manque, si une ROM n'a pas un en-tête valide ou si le
  catalogue n'est pas à jour (taille ou empreinte différente du fichier réel).
"""
import hashlib
import json
import os
import sys

RACINE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOGUE = os.path.join(RACINE, "retro-catalog.json")
# L'appli lit le catalogue et télécharge les ROM depuis la branche main.
BASE_URL = "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/"

SYSTEMES = {
    "gb": {"gb", "gbc"},
    "nes": {"nes"},
    "snes": {"sfc", "smc"},
    "gba": {"gba"},
    "sega": {"md", "gen", "sms", "gg"},
}
OBLIGATOIRES = ["id", "title", "systemId", "platformLabel", "genre", "description",
                "path", "sourceUrl", "licenceNote", "licenceFile"]
TAILLE_MAX = 64 * 1024 * 1024  # même plafond que l'appli (MAX_ROM_DOWNLOAD_BYTES)


def entete_valide(chemin, octets):
    ext = chemin.rsplit(".", 1)[-1].lower()
    if ext in ("gb", "gbc"):
        return octets[0x104:0x108] == bytes.fromhex("CEED6666")  # début du logo Nintendo
    if ext == "nes":
        return octets[:4] == b"NES\x1a"
    if ext == "gba":
        return len(octets) > 0xC0 and octets[3] == 0xEA  # instruction de saut ARM
    if ext in ("md", "gen"):
        return octets[0x100:0x104] == b"SEGA" or octets[0x100:0x104] == b" SEG"
    return len(octets) > 0


def construire(entree, erreurs):
    jeu = dict(entree)
    nom = jeu.get("id", "?")
    for cle in OBLIGATOIRES:
        if not str(jeu.get(cle, "")).strip():
            erreurs.append(f"{nom}: champ « {cle} » manquant")
    chemin = jeu.get("path", "")
    complet = os.path.join(RACINE, chemin)
    if not os.path.isfile(complet):
        erreurs.append(f"{nom}: ROM introuvable ({chemin})")
        return jeu
    ext = chemin.rsplit(".", 1)[-1].lower()
    if ext not in SYSTEMES.get(jeu.get("systemId"), set()):
        erreurs.append(f"{nom}: l'extension .{ext} ne correspond pas à systemId={jeu.get('systemId')}")
    octets = open(complet, "rb").read()
    if not entete_valide(chemin, octets):
        erreurs.append(f"{nom}: en-tête de ROM invalide ({chemin})")
    if len(octets) > TAILLE_MAX:
        erreurs.append(f"{nom}: ROM trop lourde pour l'appli ({len(octets)} octets)")
    for cle in ("licenceFile", "sourceArchive"):
        if jeu.get(cle) and not os.path.isfile(os.path.join(RACINE, jeu[cle])):
            erreurs.append(f"{nom}: fichier {cle} introuvable ({jeu[cle]})")
    if "GPL" in jeu.get("licenceNote", "") and not jeu.get("sourceArchive"):
        erreurs.append(f"{nom}: jeu sous GPL sans archive des sources (sourceArchive)")
    jeu["fileName"] = os.path.basename(chemin)
    jeu["downloadUrl"] = BASE_URL + chemin
    jeu["estimatedBytes"] = len(octets)
    jeu["sha256"] = hashlib.sha256(octets).hexdigest()
    if jeu.get("sourceArchive"):
        jeu["sourceArchiveUrl"] = BASE_URL + jeu["sourceArchive"]
    return jeu


def main():
    verifier = "--verifier" in sys.argv[1:]
    with open(CATALOGUE, encoding="utf-8") as f:
        entrees = json.load(f)
    erreurs = []
    jeux = [construire(e, erreurs) for e in entrees]
    vus = set()
    for jeu in jeux:
        for cle in ("id", "fileName"):
            valeur = (cle, jeu.get(cle))
            if valeur in vus:
                erreurs.append(f"{jeu.get('id')}: {cle} en double")
            vus.add(valeur)
    texte = "[\n" + ",\n".join("  " + json.dumps(j, ensure_ascii=False) for j in jeux) + "\n]\n"
    if verifier:
        with open(CATALOGUE, encoding="utf-8") as f:
            if f.read() != texte:
                erreurs.append("retro-catalog.json n'est pas à jour : lancez python3 jeux/outils/catalogue.py")
    if erreurs:
        print("\n".join("ERREUR " + e for e in erreurs))
        return 1
    if not verifier:
        with open(CATALOGUE, "w", encoding="utf-8") as f:
            f.write(texte)
    print(f"{len(jeux)} jeux OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
