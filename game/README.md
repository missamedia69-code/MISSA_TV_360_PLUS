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

L'univers Jeux utilise **un seul émulateur : la NES** (FCEUmm). C'est le plus léger
d'EmulatorJS ; il tourne sur les téléviseurs modestes et se joue avec les deux boutons de la
télécommande, une manette ou l'écran tactile.

| Dossier | Contenu |
|---|---|
| `moteur/` | EmulatorJS 4.2.3 (GPL-3.0) et `lanceur.html`, la page qui démarre un jeu |
| `emulateurs/` | `nes-fceumm.zip` : l'émulateur NES FCEUmm (GPL-2.0) |
| `roms/nes/` | les jeux |
| `covers/` | les pochettes affichées dans l'application |
| `licences/` | le texte de chaque licence |
| `sources/` | le code source exact des éléments sous GPL (moteur, émulateur, jeux) |
| `outils/catalogue.py` | met à jour et vérifie `catalogue.json` |

## Jeux

Uniquement des jeux homebrew dont la licence autorise la redistribution, y compris
commerciale (MISSA TV est payante). **Aucune ROM commerciale** (Nintendo, Konami, Capcom…),
même ancienne.

| Jeu | Auteur | Licence |
|---|---|---|
| RoboRun NES | HeavenHM | GPL-3.0 |
| Double Action Blaster Guys | NovaSquirrel | Zlib |
| Thwaite | Damian Yerrick | GPL-3.0+ |
| Concentration Room | Damian Yerrick | GPL-3.0+ |

Les ROM sont les fichiers publiés par leurs auteurs, sans modification. Les pochettes de
Double Action Blaster Guys, Thwaite et Concentration Room ont été dessinées pour MISSA TV.

## Ajouter un jeu

1. **Vérifier la licence** : elle doit autoriser la redistribution **sans clause
   « non commerciale »** (GPL, MIT, Zlib, BSD, CC0, CC BY, CC BY-SA), ou vous devez avoir
   un accord écrit de l'auteur.
2. Déposer la ROM (`.nes`) dans `roms/nes/`, sa licence dans `licences/` et, pour un jeu
   GPL, l'archive de ses sources dans `sources/`.
3. Ajouter une entrée dans la liste `jeux` de `catalogue.json` (copier une entrée existante).
   Console : `nes` (seule console prise en charge). Une pochette carrée (256 × 256,
   WebP) peut être ajoutée dans `covers/` avec le champ `cover`.
4. Lancer `python3 game/outils/catalogue.py` : il remplit la taille, l'empreinte et les
   liens, et signale les erreurs.
5. Fusionner sur `main` : le jeu apparaît dans l'application **au prochain démarrage**
   (l'application relit le catalogue à chaque lancement).
