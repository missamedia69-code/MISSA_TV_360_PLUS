# Univers Jeux de MISSA TV

Ce dossier contient tout ce que l'univers Jeux télécharge **à la demande** : l'application
reste légère. Rien n'est inclus dans l'APK.

## Comment l'application s'en sert

1. Elle lit `game/catalogue.json` sur la branche `main`.
2. Quand l'utilisateur choisit un jeu, elle télécharge, si ce n'est pas déjà fait :
   - le **moteur** d'émulation (`moteur/`, une seule fois, environ 200 Ko) ;
   - l'**émulateur** de la console du jeu (`emulateurs/`, une fois par console, environ 2 Mo) ;
   - le **jeu** (`roms/`).
3. Chaque fichier est vérifié par son empreinte SHA-256, puis rangé sur l'appareil.
   Le jeu se lance ensuite **hors ligne**, dans un écran plein écran de l'application.

## Contenu

| Dossier | Contenu |
|---|---|
| `moteur/` | EmulatorJS 4.2.3 (GPL-3.0) et `lanceur.html`, la page qui démarre un jeu |
| `emulateurs/` | un paquet par console : NES (FCEUmm, GPL-2.0), Game Boy / Color (Gambatte, GPL-2.0), Game Boy Advance (mGBA, MPL-2.0) |
| `roms/` | les jeux, par console |
| `licences/` | le texte de chaque licence |
| `sources/` | le code source exact des éléments sous GPL (moteur, émulateurs, jeux) |
| `outils/catalogue.py` | met à jour et vérifie `catalogue.json` |

**Pas de Super Nintendo ni de Mega Drive pour l'instant :** les émulateurs proposés par
EmulatorJS pour ces consoles (Snes9x, Genesis Plus GX, PicoDrive) interdisent l'usage
commercial, et MISSA TV est une application payante.

## Jeux

Uniquement des jeux homebrew dont la licence autorise la redistribution, y compris
commerciale. **Aucune ROM commerciale** (Nintendo, Sega, Konami…), même ancienne.

| Jeu | Console | Auteur | Licence |
|---|---|---|---|
| 2048 | Game Boy | Sanqui | Zlib |
| Adjustris | Game Boy | tbsp | CC0 |
| Big2Small | Game Boy | Matthew Steele | GPL-3.0+ |
| Geometrix | Game Boy | Antonio Niño Díaz | GPL-3.0+ |
| Libbet & the Magic Floor | Game Boy | Damian Yerrick | Zlib |
| µCity | Game Boy Color | Antonio Niño Díaz | GPL-3.0+ |
| Concentration Room | NES | Damian Yerrick | GPL-3.0+ |
| Double Action Blaster Guys | NES | NovaSquirrel | Zlib |
| RoboRun NES | NES | HeavenHM | GPL-3.0 |
| Thwaite | NES | Damian Yerrick | GPL-3.0+ |

Les ROM sont les fichiers publiés par leurs auteurs, sans modification.

## Ajouter un jeu

1. **Vérifier la licence** : elle doit autoriser la redistribution **sans clause
   « non commerciale »** (GPL, MIT, Zlib, BSD, CC0, CC BY, CC BY-SA), ou vous devez avoir
   un accord écrit de l'auteur.
2. Déposer la ROM dans `roms/<console>/`, sa licence dans `licences/` et, pour un jeu
   GPL, l'archive de ses sources dans `sources/`.
3. Ajouter une entrée dans la liste `jeux` de `catalogue.json` (copier une entrée existante).
   Consoles possibles : `nes`, `gb`, `gba`.
4. Lancer `python3 game/outils/catalogue.py` : il remplit la taille, l'empreinte et les
   liens, et signale les erreurs.
5. Fusionner sur `main` : le jeu apparaît dans l'application au prochain rafraîchissement.
