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

Le catalogue est **vide** : l'univers Jeux a été remis à zéro pour accueillir les futurs
jeux de MISSA TV. L'écran Jeux et le lecteur de l'application restent en place.

| Dossier | Contenu |
|---|---|
| `moteur/` | EmulatorJS 4.2.3 (GPL-3.0) et `lanceur.html`, la page qui démarre un jeu |
| `licences/` | le texte de chaque licence |
| `sources/` | le code source exact des éléments sous GPL |
| `outils/catalogue.py` | met à jour et vérifie `catalogue.json` |

Pour ajouter un jeu sur console, il faudra remettre l'émulateur correspondant dans
`emulateurs/` (consoles possibles : `nes`, `gb`, `gba` ; jamais Snes9x, Genesis Plus GX
ou PicoDrive, interdits en usage commercial).

**Aucune ROM commerciale** (Nintendo, Sega, Konami, SNK…), même ancienne.

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
