#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Construit `retro-catalog.json`, le catalogue que consulte l'univers « Jeux rétro »
de MISSA TV 360+.

    python3 tools/build_retro_catalog.py

Le catalogue vient de deux endroits :

1. **les jeux déposés dans `games/`** — un fichier ROM, plus une fiche `nom_du_jeu.json`
   facultative à côté de lui pour le titre, la description, la pochette… C'est comme
   cela qu'on ajoute un jeu : on dépose le fichier, on lance le script (ou on laisse
   faire GitHub Actions), et la vignette apparaît dans l'application.
2. **les jeux hébergés chez leur auteur** (`tools/retro-catalog-upstream.json`) — quand
   un jeu libre est déjà téléchargeable chez son auteur, on ne le recopie pas : on met
   simplement son adresse. Cela évite de gonfler le dépôt et de devoir suivre les mises
   à jour à la main.

Le fichier produit est écrit à la racine, parce que l'application lit exactement :
    https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/retro-catalog.json

RAPPEL : n'ajoutez dans `games/` que des jeux que vous avez le droit de distribuer —
vos créations, ou des jeux libres / homebrew sous licence ouverte. Les ROMs de jeux
commerciaux sont protégées : GitHub les supprime sur simple signalement.
"""

import json
import os
import re
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GAMES_DIR = ROOT / "games"
COVERS_DIR = GAMES_DIR / "covers"
UPSTREAM_FILE = ROOT / "tools" / "retro-catalog-upstream.json"
OUTPUT_FILE = ROOT / "retro-catalog.json"

RAW_BASE = "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main"

# Extensions reconnues par l'application (RetroGamesFragment.classifyExtensionSystem).
SYSTEMS = {
    ".gb": ("gb", "Game Boy"),
    ".gbc": ("gb", "Game Boy Color"),
    ".dmg": ("gb", "Game Boy"),
    ".sgb": ("gb", "Game Boy"),
    ".nes": ("nes", "NES"),
    ".sfc": ("snes", "Super Nintendo"),
    ".smc": ("snes", "Super Nintendo"),
    ".gba": ("gba", "Game Boy Advance"),
    ".md": ("sega", "Mega Drive"),
    ".gen": ("sega", "Mega Drive"),
    ".sms": ("sega", "Master System"),
    ".gg": ("sega", "Game Gear"),
}

# `.md` est à la fois l'extension des ROMs Mega Drive et celle des fichiers Markdown :
# on écarte donc la documentation par son nom, et les fiches par leur extension.
DOC_NAMES = {"readme", "license", "licence", "changelog", "contributing", "notice", "todo"}
DOC_SUFFIXES = {".json", ".txt", ".xml", ".yml", ".yaml"}

COVER_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp")
# L'application refuse de télécharger un ROM de plus de 64 Mo (MAX_ROM_DOWNLOAD_BYTES).
MAX_ROM_BYTES = 64 * 1024 * 1024


def raw_url(relpath: str) -> str:
    """Adresse brute d'un fichier du dépôt, avec les espaces et accents encodés."""
    return RAW_BASE + "/" + urllib.parse.quote(relpath)


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug or "jeu"


def pretty_title(file_name: str) -> str:
    """`mon_super_jeu.gbc` -> `Mon Super Jeu`."""
    stem = file_name.rsplit(".", 1)[0]
    return stem.replace("_", " ").replace("-", " ").strip().title()


def read_sidecar(rom_path: Path) -> dict:
    """La fiche facultative qui décrit le jeu : `mon_jeu.gbc.json` à côté du ROM."""
    sidecar = rom_path.with_name(rom_path.name + ".json")
    if not sidecar.is_file():
        return {}
    try:
        with sidecar.open(encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError) as error:
        print(f"  ! fiche illisible {sidecar.relative_to(ROOT)} : {error}", file=sys.stderr)
        return {}


def find_cover(rom_path: Path, meta: dict) -> str:
    """Pochette du jeu : celle de la fiche, sinon une image de même nom dans `covers/`."""
    cover = str(meta.get("cover") or meta.get("coverUrl") or "").strip()
    candidates = []
    if cover:
        candidates.append(GAMES_DIR / cover)
        candidates.append(COVERS_DIR / cover)
    stem = rom_path.name.rsplit(".", 1)[0]
    for suffix in COVER_SUFFIXES:
        candidates.append(COVERS_DIR / f"{stem}{suffix}")
    for candidate in candidates:
        if candidate.is_file():
            return raw_url(str(candidate.relative_to(ROOT)))
    return ""


def build_entry(rom_path: Path) -> dict:
    meta = read_sidecar(rom_path)
    relpath = str(rom_path.relative_to(ROOT))
    system_id, platform_label = SYSTEMS.get(rom_path.suffix.lower(), ("other", "Autre"))
    size = rom_path.stat().st_size
    return {
        "id": str(meta.get("id") or slugify(rom_path.name.rsplit(".", 1)[0])),
        "title": str(meta.get("title") or pretty_title(rom_path.name)),
        "systemId": str(meta.get("systemId") or system_id),
        "platformLabel": str(meta.get("platformLabel") or platform_label),
        "genre": str(meta.get("genre") or "Homebrew"),
        "description": str(meta.get("description") or ""),
        "fileName": rom_path.name,
        "downloadUrl": raw_url(relpath),
        "sourceUrl": str(meta.get("sourceUrl") or ""),
        "licenceNote": str(meta.get("licenceNote") or "À vérifier"),
        "coverUrl": find_cover(rom_path, meta),
        "estimatedBytes": size,
    }


def collect_repo_games() -> list:
    if not GAMES_DIR.is_dir():
        return []
    entries = []
    for rom_path in sorted(GAMES_DIR.rglob("*")):
        if not rom_path.is_file():
            continue
        if COVERS_DIR in rom_path.parents:
            continue
        if rom_path.suffix.lower() not in SYSTEMS:
            continue
        if rom_path.name.endswith(".json"):
            continue
        if rom_path.name.rsplit(".", 1)[0].lower() in DOC_NAMES:
            continue
        if rom_path.suffix.lower() in DOC_SUFFIXES:
            continue
        if rom_path.stat().st_size > MAX_ROM_BYTES:
            print(
                f"  ! {rom_path.name} dépasse 64 Mo, l'application refusera le téléchargement",
                file=sys.stderr,
            )
        entries.append(build_entry(rom_path))
    return entries


def collect_upstream_games() -> list:
    if not UPSTREAM_FILE.is_file():
        return []
    try:
        with UPSTREAM_FILE.open(encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as error:
        print(f"  ! liste amont illisible : {error}", file=sys.stderr)
        return []
    entries = []
    for item in data:
        url = str(item.get("downloadUrl") or "").strip()
        file_name = str(item.get("fileName") or "").strip()
        if not url.startswith("https://") or not file_name:
            print(f"  ! entrée amont ignorée (adresse ou nom de fichier manquant) : {item}")
            continue
        entries.append(
            {
                "id": str(item.get("id") or slugify(file_name.rsplit(".", 1)[0])),
                "title": str(item.get("title") or pretty_title(file_name)),
                "systemId": str(item.get("systemId") or "gb"),
                "platformLabel": str(item.get("platformLabel") or "Retro"),
                "genre": str(item.get("genre") or "Homebrew"),
                "description": str(item.get("description") or ""),
                "fileName": file_name,
                "downloadUrl": url,
                "sourceUrl": str(item.get("sourceUrl") or ""),
                "licenceNote": str(item.get("licenceNote") or "Open-source"),
                "coverUrl": str(item.get("coverUrl") or ""),
                "estimatedBytes": int(item.get("estimatedBytes") or 0),
            }
        )
    return entries


def main() -> int:
    upstream = collect_upstream_games()
    local = collect_repo_games()

    # Un même jeu ne doit pas apparaître deux fois : la version du dépôt gagne.
    by_file = {}
    for entry in upstream + local:
        by_file[entry["fileName"]] = entry
    entries = sorted(
        by_file.values(),
        key=lambda e: (e["systemId"], e["title"].lower()),
    )

    OUTPUT_FILE.write_text(
        json.dumps(entries, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    total_mb = sum(e["estimatedBytes"] for e in entries) / (1024 * 1024)
    print(
        f"retro-catalog.json : {len(entries)} jeux "
        f"({len(upstream)} hébergés chez leur auteur, {len(local)} dans games/), "
        f"{total_mb:.1f} Mo au total"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
