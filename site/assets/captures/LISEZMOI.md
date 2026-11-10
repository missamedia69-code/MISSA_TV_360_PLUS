# Captures de l'application

Captures utilisées par la page d'accueil (`site/index.html` et `site/en/index.html`,
générées par `outils-site/generer_accueil.py`) :

| Fichier | Contenu |
|---|---|

| `tv-radio.webp` | univers Radio (bandeau et rangée de stations floutés) |
| `tv-musique.webp` | lecteur musical (rangée de vignettes floutée) |
| `tv-podcasts.webp` | univers Podcasts (bandeau et rangée floutés) |

Règles :

- Jamais de marque, de logo, d'affiche de film ou série, ni de visage de tiers lisible :
  floutez-les (les captures Films avec de vraies affiches ne sont pas utilisées).
- Format WebP, environ 1300 px de large, moins de 80 Ko.
- Pour ajouter ou remplacer une capture, modifiez la liste `shots` dans
  `outils-site/generer_accueil.py`, puis lancez depuis la racine du dépôt :
  `python3 outils-site/generer_accueil.py .`
