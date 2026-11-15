# Bomber Plus — prototype homebrew Neo Geo

**Prototype, pas une nouvelle version finalisée de Neo Bomberman.** Une
cartouche et un paquet `.neo` ont été compilés par GitHub Actions avec ngdevkit
(run `43676693343`) et proposés comme **artefacts expérimentaux**, pas comme
jeu prêt à être distribué. Aucun essai de démarrage dans un émulateur ni sur
console n'a encore été réalisé ; la ROM n'est pas dans le catalogue. Ce projet écrit
un programme 68000 inédit destiné au matériel Neo Geo MVS/AES ; il ne modifie
pas `game/rom/neobombe.neo` et n'embarque ni la ROM commerciale ni son BIOS.
Il utilise la couche FIX (lettres et symboles) pour un premier jeu fonctionnel,
**sans sprites ni musique d'origine**. L'ambition graphique et l'équilibrage
restent à développer. Ne pas l'ajouter au catalogue Jeux avant un test sur
émulateur et, pour revendiquer la compatibilité matérielle, sur une vraie console.

## Ce qui est implémenté

- Labyrinthe 13 × 11, murs fixes, briques destructibles, 3 à 8 ennemis selon
  le niveau, déplacements au joystick, mode solo ou coopératif deux joueurs ;
- bombes avec temporisation de 90 images, explosions en croix bloquées par les
  murs/briques, réactions en chaîne et flammes de 24 images ;
- bonus aléatoires portée/capacité, 3 vies, invulnérabilité temporaire après
  dégâts, score et progression de manche ; IA ennemie simple ;
- code logique C99 indépendant de la plateforme et adaptateur matériel ngdevkit
  qui dessine la grille sur la couche FIX de la Neo Geo.

Sur Neo Geo : **A** lance une partie solo depuis l'écran titre ; **B** lance
une partie à deux. Joystick pour bouger, **A** pour poser une bombe ; **B**
redémarre après un game over. Le mode MVS de MISSA TV est la première cible.

## Aperçu jouable en WebView

```sh
python3 game/homebrew/neobomberman-plus/web/server.py
# Ouvrir http://localhost:8000 dans le navigateur (port exposé par l'aperçu Arena)
```

Ce serveur de développement compile `bomber.c` en bibliothèque partagée avec
GCC et l'exécute derrière une interface Canvas. Les boutons tactiles, le clavier
et le mode deux joueurs sont disponibles. **Il ne charge ni n'émule la ROM Neo
Geo** et ne constitue donc pas une preuve de compatibilité avec la console.
Le serveur garde une seule partie en mémoire : ouvrir plusieurs onglets partage
la partie ; il n'est pas destiné à un déploiement public multi-utilisateur.

## Vérifications locales (sans console)

```sh
make -C game/homebrew/neobomberman-plus test
```

Les tests vérifient les murs, déplacements, bonus, limite de bombes, explosion,
propagation arrêtée par une brique, réaction en chaîne, dégâts et changement de
niveau. Le packer `.neo` a ses propres tests de structure et refuse un NGH
incohérent avec le code 68000. Ces tests **ne prouvent pas** qu'une ROM démarre.

## Compiler une cartouche expérimentale

Installer [ngdevkit](https://github.com/dciabrin/ngdevkit) (>= 0.5) et ses
outils (compilateur `m68k-neogeo-elf-gcc`, Z80, `romtool.py`), puis `git`,
`autoconf`, `automake`, `rsync`, `zip`, `imagemagick`, `sox` :

```sh
make -C game/homebrew/neobomberman-plus native
```

`tools/build_native.sh` importe une révision figée des
[exemples ngdevkit](https://github.com/dciabrin/ngdevkit-examples) dans un
répertoire temporaire, utilise son assembleur BIOS, sa police FIX, ses ROMs
Z80/C et compile les sources présentes dans ce dossier. Il crée localement
`build/bomberplus.zip` (puces de la cartouche) et `build/bomberplus.neo`
(format TerraOnion pour Geolith). Il **ne** publie ni BIOS ni assets du jeu
commercial. L'identifiant provisoire NGH est `1987`, distinct de Neo
Bomberman (`0x93`) ; ne pas le réutiliser pour un autre jeu.

La sérialisation NEO suit `NEO\x01`, un en-tête de 4096 octets puis P/S/M/V/C ;
la région C entrelace les octets C1/C2. Elle a été vérifiée par lecture des
sources `geo_neo.c` de Geolith et `neowrite.c` de lithogen, mais **reste à valider
sur une cartouche générée et lancée**. Une ROM formatée correctement n'est pas
nécessairement jouable.

## Prochaines étapes

1. Construire la cartouche avec ngdevkit en CI et inspecter le code P/les puces.
2. Tester le démarrage, les commandes et l'audio dans GnGeo/MAME puis Geolith.
3. Remplacer la couche FIX provisoire par des sprites et animations originaux,
   créer les effets sonores, équilibrer la coop et ajouter de vrais niveaux.
4. Tester sur Neo Geo MVS réelle avant toute affirmation de compatibilité.
5. **Seulement ensuite**, publier un `.neo` distinct et son affiche et l'ajouter
   au catalogue sans remplacer le Neo Bomberman déjà disponible.
