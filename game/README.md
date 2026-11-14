# Univers Jeux de MISSA TV : Neo Geo

L'univers Jeux de MISSA TV propose des jeux **Neo Geo**. Rien de l'émulateur n'est dans
l'APK : tout est dans ce dossier et téléchargé à la demande.

## Comment l'application s'en sert

1. À **chaque démarrage**, elle lit `game/catalogue.json` sur la branche `main` : les
   bannières des jeux s'affichent tout de suite dans l'univers Jeux.
2. Quand l'utilisateur touche un jeu, elle télécharge ce qui manque, une seule fois :
   - le **moteur Neo Geo** de son processeur (`neogeo/moteur/`, environ 0,7 Mo) ;
   - le **BIOS Neo Geo** (`neogeo/bios/`) ;
   - le **jeu** (`roms/neogeo/`, fichier `.neo`).
3. Chaque fichier est vérifié par son empreinte SHA-256. Le jeu se lance ensuite **hors
   ligne**, en plein écran, avec la manette, la télécommande ou la manette tactile.

## Le moteur

| Élément | Version | Licence | Rôle |
|---|---|---|---|
| [Geolith](https://gitlab.com/jgemu/geolith) (port libretro) | 0.5.0 | BSD-3-Clause | émulation Neo Geo AES/MVS, 100 % de la ludothèque |
| [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) | 0.14.0 | GPL-3.0 | affichage OpenGL, son, manettes |

Geolith est l'un des rares émulateurs Neo Geo dont la licence **autorise l'usage
commercial**. FinalBurn Neo et MAME l'interdisent (« you may not sell… »), ils ne peuvent
donc pas être utilisés dans MISSA TV.

Le moteur est natif (pas de WebView) : c'est ce qui lui permet de tourner sur les petits
téléviseurs. Un paquet par architecture : `arm64-v8a` et `armeabi-v7a` (téléviseurs,
box, téléphones), `x86_64` et `x86`. Chaque paquet contient `libgeolith.so` (cœur officiel
du serveur de compilation libretro) et `liblibretrodroid.so` (bibliothèque publiée sur
JitPack), sans modification. La partie Java de LibretroDroid est intégrée à l'application
(`app/src/main/java/com/swordfish/libretrodroid`), avec une seule modification : la
bibliothèque native est chargée depuis le paquet téléchargé.

Les textes de licence sont dans `licences/` et le code source exact dans `sources/`.

## Ce que vous devez fournir

### 1. Le BIOS (obligatoire)

Une Neo Geo ne démarre pas sans son BIOS, qui appartient à SNK. **Il doit faire partie
de votre accord de licence** avec l'ayant droit, au même titre que les jeux.

- Mode arcade (`"mode": "mvs"`, par défaut) : `neogeo/bios/neogeo.zip` (jeu de BIOS MAME
  récent) ;
- mode console (`"mode": "aes"`) : `neogeo/bios/aes.zip`.

Déclarez-le dans la liste `bios` du catalogue :

```json
"bios": [
  { "path": "game/neogeo/bios/neogeo.zip", "licence": "Contrat SNK n° ... du ..." }
]
```

Tant que le BIOS n'est pas publié, l'application affiche les jeux mais annonce qu'ils
arrivent bientôt.

### 2. Les jeux (format `.neo`)

Geolith lit uniquement le format **`.neo`** (TerraOnion). Si votre fournisseur livre des
fichiers MAME (`.zip`), convertissez-les avec l'outil libre
[lithogen](https://github.com/carmiker/lithogen) ou NeoBuilder. Pour Metal Slug,
le fichier converti `game/roms/neogeo/mslug.neo` a été vérifié avec
`lithogen --strict` : c'est le seul fichier du jeu déclaré dans le catalogue
et téléchargé par l'application. L'archive MAME d'origine n'est pas nécessaire
au fonctionnement de l'application et n'est pas conservée dans le dossier `game/`.

La bannière originale de Metal Slug est dans `bannieres/mslug.webp` (portrait 3:4)
et s'affiche dans la grille des jeux. Un logo horizontal original se trouve dans
`logos/mslug.webp` ; l'écran Jeux n'a pas de champ logo séparé et ne l'affiche pas.

Neo Bomberman est fourni directement au format `.neo` : `roms/neogeo/neobombe.neo`.
Son affiche originale est dans `bannieres/neobombe.webp`. L'archive reçue sous un
nom `.png` n'était pas une image mais un ZIP contenant ce `.neo` ; seule la ROM
extraite est publiée dans le dépôt.

Metal Slug 2 est converti depuis l'archive MAME reçue avec `lithogen --strict` :
seuls `roms/neogeo/mslug2.neo` et son affiche `bannieres/mslug2.webp` sont
publiés. L'autre archive reçue sous un nom ressemblant à `mslugx` contient en
réalité Metal Slug 1 (ROMs `201-*`), déjà présent ; elle n'est pas ajoutée.

Quatre autres archives reçues sous des noms `.png` sont des ZIP MAME : Metal
Slug X (ROMs `250-*`), Metal Slug 3 (`256-*`), Metal Slug 4 (`263-*`) et Metal
Slug 5 (`268-*`). Leur intégrité ZIP a été vérifiée et elles ont été converties
avec `lithogen --strict` (NGH 250, 256, 263 et 268 respectivement). Seules les
ROMs `.neo` validées sont hébergées dans la Release
`jeux-metal-slug-2026-11` ; les ZIP d'origine et leurs BIOS inclus ne sont pas
nécessaires au jeu et ne figurent pas dans le catalogue. Leurs affiches
originales sont dans `bannieres/`. Le catalogue réseau et sa copie embarquée
référencent les mêmes URL, tailles et empreintes SHA-256. Une validation de
conversion et les contrôles Android ne remplacent pas un essai sur appareil.

## Ajouter un jeu

1. Déposer le jeu dans `roms/neogeo/` (par exemple `roms/neogeo/kof98.neo`).
2. Déposer sa bannière dans `bannieres/` : image **portrait 3:4**, par exemple
   600 × 800 pixels, en WebP ou JPEG (moins de 200 Ko).
3. Ajouter une entrée dans la liste `jeux` de `catalogue.json` :

   ```json
   {
     "id": "kof98",
     "title": "The King of Fighters '98",
     "genre": "Combat",
     "author": "SNK",
     "players": "1-2",
     "description": "Le grand classique du combat en équipes de trois.",
     "path": "game/roms/neogeo/kof98.neo",
     "banner": "game/bannieres/kof98.webp",
     "licence": "Contrat ... du ... (diffusion commerciale autorisée)"
   }
   ```

4. Lancer `python3 game/outils/catalogue.py` : il remplit la taille, l'empreinte et les
   liens, et refuse un jeu qui n'est pas un vrai `.neo`, sans licence, ou sans le BIOS du
   mode choisi.
5. Fusionner sur `main` : le jeu apparaît dans l'application **au prochain démarrage**.

### Jeux de plus de 100 Mo

GitHub refuse les fichiers de plus de 100 Mo dans le dépôt. Pour un gros jeu (Garou,
King of Fighters 2003...), publiez le fichier `.neo` en pièce jointe d'une **Release**
GitHub (jusqu'à 2 Go) ou sur un autre stockage HTTPS, et décrivez-le sans `path` :

```json
{
  "id": "garou", "title": "Garou: Mark of the Wolves", "genre": "Combat", "author": "SNK",
  "description": "...", "licence": "Contrat ...", "banner": "game/bannieres/garou.webp",
  "url": "https://github.com/missamedia69-code/MISSA_TV_360_PLUS/releases/download/jeux-1/garou.neo",
  "bytes": 123456789,
  "sha256": "empreinte obtenue avec : sha256sum garou.neo"
}
```

Taille maximale d'un jeu : 160 Mo.

**Aucun jeu sans licence écrite** autorisant sa diffusion dans une application payante.

## Commandes

| | Manette | Télécommande | Écran tactile |
|---|---|---|---|
| Direction | croix / stick | flèches | croix à gauche |
| A B C D | A B X Y (selon la manette) | OK ou 1, 2, 3, 4 | boutons à droite |
| Pièce | Select | 5 | PIÈCE |
| Start | Start | 0, Menu ou Lecture | START |
| Menu pause | Home / Mode | Retour | Retour |

Les jeux sont réglés en **partie gratuite** : pas besoin d'insérer de pièce. Les
sauvegardes (scores, progression) sont gardées sur l'appareil.
