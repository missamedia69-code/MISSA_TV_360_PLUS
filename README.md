# MISSA TV 360 PLUS

**Plateforme multimédia unifiée pour Android Mobile, Tablette et Android TV.**  
*Unified Cinema, Live TV & Direct Radio Platform for Android Phone, Tablet & Android TV.*

🌐 **Site et téléchargement : [missatv.pages.dev](https://missatv.pages.dev)**

> **Note importante / Disclaimer** : Par défaut, MISSA TV 360 PLUS n'embarque aucune source vidéo commerciale. Les flux vidéo/audio reposent sur les extensions compatibles installées par l'utilisateur, tandis que les stations de radio directes fonctionnent immédiatement.

---

## Les 11 Univers MISSA TV 360 PLUS

MISSA TV 360 PLUS remplace la navigation par extension individuelle par une agrégation intelligente et dédupliquée autour de **11 univers thématiques** (auxquels s'ajoute l'Accueil unifié) :

1. **Films & Mangas** (`navigation_universe_films`) — Longs métrages, mangas, animes et OAV agrégés et classés par catégories éditoriales stables (`MissaCategory`).
2. **Séries** (`navigation_universe_series`) — Séries TV, dramas et feuilletons multi-sources avec mémoire de source préférée.
3. **Documentaires** (`navigation_universe_documentaries`) — Grands documentaires, docu-séries, enquêtes, nature, histoire, sciences et chaînes découverte.
4. **Courts métrages** (`navigation_universe_shorts`) — Formats courts, mini-documentaires et créations indépendantes.
5. **Jeunesse / Enfants** (`navigation_universe_kids`) — Sélection familiale filtrée avec **verrouillage parental par code PIN** (`TRUST-01`).
6. **TV en direct** (`navigation_universe_live_tv`) — Les chaînes du fichier [`tv/chaines.m3u`](tv/README.md) du dépôt, relu par l'application à chaque démarrage (rien n'est téléchargé s'il n'a pas changé). Petites tuiles logo, onglets Toutes / Favoris / Catégories / Pays, recherche, lecture dans le lecteur vidéo.
7. **Sports** (`navigation_universe_sports`) — Directs sportifs, compétitions et rediffusions.
8. **Musiques** (`navigation_universe_music`) — Titres, albums, clips vidéo, concerts, chaînes musicales et flux musicaux en continu (Afro, Cameroun, Urbain, Pop, Gospel, Jazz…).
9. **Podcasts** (`navigation_universe_podcasts`) — Émissions audio, chroniques et livres audio.
10. **Radio directe** (`navigation_universe_radio`) — Les 51 000 stations de l'annuaire libre Radio Browser, rangées par **pays** (225 pays, par continent, Cameroun et Afrique francophone en tête) et par **thème** (actualités, gospel, afro, jazz...), en petites tuiles logo chargées page par page, plus la sélection MISSA, les favoris et la recherche. Lecture via **AndroidX Media3 `MediaSessionService`** (notification système, écran verrouillé, mini-lecteur persistant, minuterie de veille et flux personnalisés `M3U8`/`MP3`/`AAC`).
11. **Jeux** (`navigation_universe_games`) — Jeux d'arcade **Neo Geo**, avec un moteur natif léger : [Geolith](https://gitlab.com/jgemu/geolith) (BSD, usage commercial autorisé) et [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (GPL-3.0). Rien de l'émulateur n'est dans l'APK : le moteur (~0,7 Mo), le BIOS et les jeux (`.neo`) sont téléchargés depuis le dossier [`game/`](game/README.md) au premier lancement, vérifiés, puis joués hors ligne à la manette, à la télécommande ou avec la manette tactile.

---

## Architecture & Points Clés UI/UX

- **Compatibilité 100 % Extensions CloudStream** : Les plugins existants (`MainAPI`, `PluginManager`, `APIHolder`) fonctionnent en arrière-plan sans aucune modification requise.
- **Système de Design Sémantique (`THEME-01..08`)** :
  - Tokens d'attributs sémantiques `missa*` supportés sur les **8 modes de thème** (`AppTheme`, `AmoledMode`, `LightMode`, `AmoledModeLight`, `DraculaMode`, `SilentBlueMode`, `LavenderMode`, `MonetMode`).
  - Échelle typographique `TextAppearance.Missa.*` (`@font/google_sans`, `>= 12sp` mobile, variantes `values-sw600dp/` et `values-television/`).
  - Composants unifiés `Widget.Missa.{Pill,Button.Primary,Button.Secondary,Button.Icon}` et anneau de focus TV unique `3dp` vert de navigation (`missaAccentNavigation`); le jaune reste réservé à l’accent de sélection.
- **Performance & Mode Économie de Données (`HOME-02`, `HOME-13`, `FLOW-03`)** :
  - `RecyclerView` modulaire (`ListAdapter` + `DiffUtil` avec payloads sans perte de focus), carrousel `ViewPager2`, squelette `Shimmer` progressif et cache catalogue sur disque (`< 1s` au démarrage à froid).
  - Mode **Data Saver** réduisant la concurrence réseau et gelant l'auto-rotation du carrousel sur connexions mobiles limitées.
  - Mode **appareil léger** automatique sous 1,3 Go de RAM (TV et box 512 Mo / 1 Go) : sans animation, images en 16 bits, catalogues mis à jour l'un après l'autre, moins de requêtes simultanées, lecteur vidéo limité au 720p avec une mémoire tampon réduite (`MissaDeviceProfile`).
- **Internationalisation & Accessibilité (`I18N-01..02`, `A11Y-01`)** :
  - Filtrage par clés stables `MissaCategoryKey` indépendant de la langue active, parité complète `values/strings.xml` (EN) et `values-b+fr/strings.xml` (FR) avec `<plurals>`.
  - Support TalkBack (`accessibilityHeading`, descriptions dynamiques, cibles tactiles `>= 48dp`) et mise à l'échelle des polices jusqu'à `130 %`.

---

## Compilation & Lancement dans Android Studio (Zéro téléchargement externe)

Le projet est préconfiguré pour s'ouvrir et se lancer directement dans **Android Studio** sans installer d'outil supplémentaire :

1. **Ouvrez le dossier dans Android Studio** (**File → Open…**) — fonctionne aussi bien via `git clone` qu'en téléchargeant l'archive **Download ZIP** depuis GitHub.
2. **Aucun JDK externe à télécharger** : le projet utilise directement le **JDK embarqué dans Android Studio** (JetBrains Runtime 17 ou 21+) sans exiger de JDK 17 ou JDK 25 séparé.
3. **Configuration prête à l'emploi** : la variante **`prereleaseDebug`**, la configuration d'exécution **`MISSA TV 360 PLUS`** (bouton **Run ▶**) et le provisionnement automatique du SDK Android (`android.builder.sdkDownload=true`) sont déjà activés.
4. Cliquez simplement sur **Run ▶** en haut d'Android Studio pour compiler et lancer l'application sur téléphone, tablette ou Android TV.

```bash
# En ligne de commande (tests unitaires + génération de l'APK)
./gradlew :app:testPrereleaseDebugUnitTest :app:assemblePrereleaseDebug
```

Le workflow GitHub Actions (`.github/workflows/missa-tv-ci.yml`) compile automatiquement le projet, exécute la suite de tests unitaires et publie l'artefact APK `missa-tv-360-prerelease-debug`.

---

## Crédits & Licence Open-Source

MISSA TV 360 PLUS est distribué sous licence **GNU General Public License v3.0 (GPL-3.0)** et s'appuie sur l'écosystème open-source suivant :

- Base moteur & système d'extensions : [reCloudStream / CloudStream](https://github.com/recloudstream/cloudstream) (GPL-3.0)
- Lecture audio & session média : [AndroidX Media3](https://developer.android.com/media/media3) (Apache-2.0)
