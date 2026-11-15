# Univers TV en direct de MISSA TV

L'univers **TV en direct** de l'application affiche les chaînes du fichier
[`chaines.m3u`](chaines.m3u) de ce dossier.

## Comment l'application s'en sert

1. À **chaque démarrage**, elle demande `tv/chaines.m3u` à GitHub (branche `main`).
   Si le fichier n'a pas changé depuis la dernière fois, rien n'est téléchargé.
2. La liste est gardée sur l'appareil : l'univers fonctionne aussi hors connexion.
3. Au tout premier lancement, l'application affiche la copie de la liste rangée dans l'APK,
   le temps de télécharger la dernière version.
4. Dans l'univers, le bouton **« Mettre à jour la liste »** force une vérification.

**Changer les chaînes = modifier `chaines.m3u` sur `main`.** Aucune nouvelle version de
l'application n'est nécessaire : les utilisateurs voient la nouvelle liste au prochain
démarrage (GitHub peut mettre jusqu'à 5 minutes à servir la nouvelle version).

## Format du fichier

Un M3U classique, comme ceux des lecteurs IPTV :

```
#EXTM3U
#EXTINF:-1 tvg-id="CRTV.cm" tvg-logo="https://exemple.org/crtv.png" tvg-country="CM" group-title="Généraliste",CRTV
https://exemple.org/crtv/playlist.m3u8
```

| Élément | Rôle dans l'application |
|---|---|
| nom (après la virgule) | nom de la chaîne sous sa tuile |
| `tvg-logo` | logo de la tuile (sinon, les initiales de la chaîne) |
| `group-title` | onglet **Catégories** (plusieurs catégories : séparez-les par `;`) |
| `tvg-country` | onglet **Pays** (code ISO à 2 lettres : `CM`, `CI`, `SN`...) |
| `http-referrer`, `http-user-agent` ou `#EXTVLCOPT:` | en-têtes envoyés au serveur de la chaîne |

Limites : 20 000 chaînes et 16 Mo au plus. Une liste qui ne contient aucune chaîne valide
est ignorée (l'application garde la précédente).

## Liste de départ

La liste de départ a été produite par `outils/generer_depart.py` à partir des listes
publiques du projet [iptv-org](https://github.com/iptv-org/iptv) : chaînes d'Afrique
francophone et quelques chaînes d'information internationales. Elle a ensuite été
complétée par d'autres sources fournies par l'utilisateur. En particulier, la
playlist « Popular Movies IPTV » a apporté 581 flux absents de la liste publiée ;
7 autres étaient déjà présents (2 dans la liste de départ, puis 5 ajoutés avec la
liste Afrique). Les catégories cinéma ont été traduites en français. Ce script de génération ne doit pas être relancé
sur la liste publiée sans précaution : il écraserait les ajouts manuels.

L'ajout d'une URL ne prouve ni la disponibilité du flux, ni ses droits de diffusion ;
les liens doivent être contrôlés et retirés si nécessaire. Vous pouvez modifier ou
remplacer entièrement la liste par votre propre fichier.

**N'ajoutez que des chaînes que vous avez le droit de diffuser** (flux publics officiels,
chaînes partenaires) : pas de bouquets payants piratés.
