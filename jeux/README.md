# Jeux de l'univers Jeux de MISSA TV

Ce dossier contient les jeux que l'appli propose au téléchargement. Ce sont
uniquement des **jeux homebrew** (créés par des amateurs pour des consoles
rétro) dont la licence autorise la redistribution. **Aucune ROM commerciale.**

L'appli lit le catalogue `retro-catalog.json` (à la racine du dépôt) sur la
branche `main`, puis télécharge la ROM choisie depuis ce dossier. Elle vérifie
l'empreinte SHA-256 du fichier avant de le lancer dans l'émulateur intégré.

## Contenu

| Jeu | Console | Auteur | Licence | Source d'origine |
|---|---|---|---|---|
| 2048 | Game Boy | Sanqui (graphismes : beware) | Zlib | https://github.com/Sanqui/2048-gb |
| Adjustris | Game Boy | tbsp | CC0 | https://github.com/tbsp/adjustris |
| Big2Small | Game Boy | Matthew Steele | GPL-3.0+ | https://github.com/mdsteele/big2small |
| Geometrix | Game Boy | Antonio Niño Díaz | GPL-3.0+ | https://github.com/AntonioND/geometrix |
| Libbet & the Magic Floor | Game Boy | Damian Yerrick | Zlib | https://github.com/pinobatch/libbet |
| µCity | Game Boy Color | Antonio Niño Díaz | GPL-3.0+ | https://github.com/AntonioND/ucity |
| Concentration Room | NES | Damian Yerrick | GPL-3.0+ | https://github.com/pinobatch/croom-nes |
| Double Action Blaster Guys | NES | NovaSquirrel | Zlib | https://github.com/NovaSquirrel/DABG |
| RoboRun NES | NES | HeavenHM | GPL-3.0 | https://github.com/heaven-hm/roborun-nes |
| Thwaite | NES | Damian Yerrick | GPL-3.0+ | https://github.com/pinobatch/thwaite-nes |
| 240p Test Suite | Game Boy Advance | Damian Yerrick, Artemio Urbina | GPL-2.0+ | https://github.com/pinobatch/240p-test-mini |
| Dragon's Castle (prototype) | Mega Drive | Javier Degirolmo (Sik) | Zlib | https://github.com/sikthehedgehog/dragon |

Les ROM sont les fichiers publiés par leurs auteurs, sans modification.

## Organisation

- `roms/<console>/` : les ROM.
- `licences/` : le texte de chaque licence.
- `sources/` : pour les jeux sous GPL, l'archive du code source correspondant
  exactement à la version publiée. La GPL l'exige quand on redistribue le jeu :
  ne supprimez pas ces archives tant que la ROM est proposée.
- `outils/catalogue.py` : met à jour et vérifie `retro-catalog.json`.

## Ajouter un jeu

1. Vérifiez la licence. Elle doit autoriser la redistribution, **sans clause
   « non commerciale »** (CC BY-NC…) : on reste ainsi libre quel que soit
   l'avenir de l'appli. Exemples acceptés : GPL, MIT, Zlib, BSD, CC0, CC BY,
   CC BY-SA.
2. Déposez la ROM dans `roms/<console>/` et la licence dans `licences/`.
   Pour un jeu GPL, ajoutez aussi l'archive des sources dans `sources/`.
3. Ajoutez une entrée dans `retro-catalog.json` (copiez une entrée existante).
   Consoles possibles pour `systemId` : `gb`, `nes`, `snes`, `gba`, `sega`.
4. Lancez `python3 jeux/outils/catalogue.py` : le script remplit la taille,
   l'empreinte et les liens de téléchargement, et signale les erreurs.
5. Fusionnez sur `main` : le jeu apparaît dans l'appli au prochain
   rafraîchissement du catalogue (12 h maximum, ou en tirant la liste vers le bas).
