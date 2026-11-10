# Captures d'écran du site

Le site prévoit 7 emplacements pour de **vraies** captures de l'application. Tant qu'une
capture manque, son cadre (TV ou téléphone) affiche un visuel de repli discret : le logo ou
l'icône de l'univers avec « Capture à venir ». Il n'y a jamais d'image cassée.

## Fichiers à fournir

Noms exacts, en minuscules, au format **WebP**, déposés dans ce dossier
(`site/assets/captures/`) :

| Fichier | Appareil | Écran à capturer | Taille conseillée |
|---|---|---|---|
| `tv-accueil.webp` | Android TV | Accueil de l'application (grand visuel de l'accroche) | 1920 × 1080 |
| `tv-films.webp` | Android TV | Univers Films, rangées d'affiches | 1920 × 1080 |
| `tv-jeux.webp` | Android TV | Univers Jeux, pochettes des jeux | 1920 × 1080 |
| `tv-enfants.webp` | Android TV | Univers Enfants, ou l'écran du code parental | 1920 × 1080 |
| `tel-accueil.webp` | Téléphone | Accueil de l'application, en portrait | 1080 × 2340 |
| `tel-films.webp` | Téléphone | Univers Films, en portrait | 1080 × 2340 |
| `tel-jeux.webp` | Téléphone | Univers Jeux, en portrait | 1080 × 2340 |

- TV : format 16:9. Une capture 1280 × 720 convient aussi.
- Téléphone : format portrait d'environ 9:19,5. Le haut de l'image est toujours visible, le
  bas peut être légèrement rogné si le format diffère.
- Poids visé : moins de 250 Ko par image (WebP qualité 80 environ).

## Règles de contenu, à respecter strictement

- **De vraies captures de l'application**, sans retouche ni montage. Pas de maquette, pas
  d'image générée.
- **Aucune marque, aucun film, aucune série ni chaîne réels** à l'écran : utilisez les affiches
  fictives prévues pour les démonstrations. Pas de logo de station de radio, de chaîne ou
  d'émission réelle : c'est pour cela que les écrans Radio, Podcasts et TV en direct ne font
  pas partie de la liste.
- Masquez les informations personnelles (nom de compte, adresse e-mail, etc.).

## Comment les déposer

1. Faites la capture.
   - Sur Android TV, avec un ordinateur relié par ADB :
     `adb exec-out screencap -p > tv-films.png`
   - Sur téléphone : boutons Marche + Volume bas, puis récupérez le fichier.
2. Convertissez-la en WebP, par exemple :
   `cwebp -q 80 tv-films.png -o tv-films.webp`
   (ou avec tout éditeur d'image qui exporte en WebP).
3. Copiez le fichier dans `site/assets/captures/` avec le nom exact du tableau.
4. Déclarez-le dans `site/assets/captures/captures.js`, par exemple :

   ```js
   window.MISSA_CAPTURES = ["tv-accueil.webp", "tv-films.webp", "tel-accueil.webp"];
   ```

   Le site ne charge que les captures listées dans ce fichier : un oubli n'affiche donc
   jamais d'image cassée, seulement le visuel de repli.
5. Ouvrez `site/index.html` dans un navigateur pour vérifier, puis fusionnez sur `main` : le
   site est republié automatiquement. Les pages française et anglaise utilisent les mêmes
   fichiers.

## Extraits déjà utilisés

`site/assets/extrait-navigation.webp` et `site/assets/extrait-radio.webp` sont des extraits
recadrés de vraies captures de l'application, choisis pour ne montrer aucune marque.
