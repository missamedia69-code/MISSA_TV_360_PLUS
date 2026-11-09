# Les jeux de MISSA TV 360+

Chaque fichier déposé dans ce dossier devient une **vignette prête à installer** dans
l'univers *Jeux rétro* de l'application. Rien d'autre à faire : le catalogue se
reconstruit tout seul.

## Ajouter un jeu

1. **Déposez le fichier du jeu** dans ce dossier.

   | Console | Extensions reconnues |
   | --- | --- |
   | Game Boy / Game Boy Color | `.gb` `.gbc` `.dmg` `.sgb` |
   | NES | `.nes` |
   | Super Nintendo | `.sfc` `.smc` |
   | Game Boy Advance | `.gba` |
   | Sega (Mega Drive, Master System, Game Gear) | `.md` `.gen` `.sms` `.gg` |

2. **Ajoutez la pochette** (facultatif) : une image carrée dans `covers/`, nommée comme
   le fichier du jeu sans son extension — `ucity.gbc` → `covers/ucity.png`. 512 × 512
   est une bonne taille. Sans pochette, la vignette garde l'icône manette.

3. **Décrivez le jeu** (facultatif) dans une fiche posée à côté du fichier, nommée
   `<fichier du jeu>.json` — par exemple `ucity.gbc.json` :

   ```json
   {
     "title": "µCity",
     "systemId": "gb",
     "platformLabel": "Game Boy Color",
     "genre": "Gestion & Construction",
     "description": "Jeu open-source de construction et de gestion urbaine.",
     "sourceUrl": "https://github.com/AntonioND/ucity",
     "licenceNote": "GPL-3.0"
   }
   ```

   Tout est facultatif : sans fiche, le titre est déduit du nom du fichier et la console
   de son extension.

4. **C'est tout.** GitHub Actions régénère `retro-catalog.json` dès que ce dossier
   change. Pour le faire à la main :

   ```bash
   python3 tools/build_retro_catalog.py
   ```

L'application relit le catalogue toutes les 12 heures, ou tout de suite si l'on tire
l'écran vers le bas.

## Un jeu déjà téléchargeable chez son auteur ?

Ne le recopiez pas : ajoutez-le à `tools/retro-catalog-upstream.json` avec l'adresse de
téléchargement de l'auteur. Le dépôt reste léger et le jeu suit ses propres mises à jour.
C'est ainsi que sont référencés les cinq jeux libres déjà présents.

## Ce que vous avez le droit de déposer ici

**Uniquement des jeux que vous avez le droit de distribuer :**

- vos propres créations ;
- des jeux libres ou homebrew dont la licence autorise la redistribution
  (MIT, GPL, Zlib, Creative Commons…) — pensez à remplir `licenceNote` ;
- des jeux dont l'auteur vous a donné l'autorisation par écrit.

**Les ROMs de jeux commerciaux sont interdites ici.** Nintendo, Sega, Konami, Capcom et
les autres protègent leurs jeux : héberger Mario, Zelda, Castlevania, Pokémon ou Sonic
constitue une contrefaçon. GitHub supprime ces fichiers sur simple signalement, le compte
peut être fermé, et l'application deviendrait un outil de piratage. Si vous possédez
l'original, importez la ROM **dans l'application** (bouton « Importer ») : elle reste sur
votre appareil et n'est diffusée à personne.
