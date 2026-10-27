# MISSA TV 360 PLUS

**Plateforme multimédia & rétro-gaming unifiée pour Android Mobile, Tablette et Android TV.**  
*Unified Cinema, Live TV, Direct Radio & Retro Gaming Platform for Android Phone, Tablet & Android TV.*

> **Note importante / Disclaimer** : Par défaut, MISSA TV 360 PLUS n'embarque aucune source vidéo commerciale ni aucune ROM commerciale. Les flux vidéo/audio reposent sur les extensions compatibles installées par l'utilisateur, tandis que les stations de radio directes et le catalogue de jeux rétro open-source (homebrew) fonctionnent immédiatement.

---

## Les 10 Univers MISSA TV 360 PLUS

MISSA TV 360 PLUS remplace la navigation par extension individuelle par une agrégation intelligente et dédupliquée autour de **10 univers thématiques** (auxquels s'ajoute l'Accueil unifié) :

1. **Films & Manga** (`navigation_universe_films`) — Longs métrages, mangas, animes et OAV agrégés et classés par catégories éditoriales stables (`MissaCategory`).
2. **Séries** (`navigation_universe_series`) — Séries TV, dramas et feuilletons multi-sources avec mémoire de source préférée.
3. **Courts métrages** (`navigation_universe_shorts`) — Formats courts, mini-documentaires et créations indépendantes.
4. **Jeunesse / Enfants** (`navigation_universe_kids`) — Sélection familiale filtrée avec **verrouillage parental par code PIN** (`TRUST-01`).
5. **TV en direct** (`navigation_universe_live_tv`) — Chaînes live, zapping instantané `CH+` / `CH-` à la télécommande, dernière chaîne mémorisée et favoris en tête (`FLOW-02`).
6. **Sports** (`navigation_universe_sports`) — Directs sportifs, compétitions et rediffusions.
7. **Musiques** (`navigation_universe_music`) — Titres, albums, clips vidéo, concerts, chaînes musicales et flux musicaux en continu (Afro, Cameroun, Urbain, Pop, Gospel, Jazz…).
8. **Podcasts** (`navigation_universe_podcasts`) — Émissions audio, chroniques et livres audio.
9. **Radio directe** (`navigation_universe_radio`) — Stations africaines (Cameroun, Côte d'Ivoire, Sénégal, RDC…) et internationales en lecture directe via **AndroidX Media3 `MediaSessionService`** (notification système, écran verrouillé, mini-lecteur persistant, minuterie de veille et flux personnalisés `M3U8`/`MP3`/`AAC`).
10. **Jeux Rétro** (`navigation_universe_retro_games`) — Catalogue homebrew libre + import de ROMs légales avec émulation **Libretro** à la demande (Game Boy / GBC, NES, Super Nintendo, GBA, Mega Drive / Master System), croix directionnelle 8 directions multi-touch, états de sauvegarde automatiques et menu Pause navigable à la télécommande.

---

## Architecture & Points Clés UI/UX

- **Compatibilité 100 % Extensions CloudStream** : Les plugins existants (`MainAPI`, `PluginManager`, `APIHolder`) fonctionnent en arrière-plan sans aucune modification requise.
- **Système de Design Sémantique (`THEME-01..08`)** :
  - Tokens d'attributs sémantiques `missa*` supportés sur les **8 modes de thème** (`AppTheme`, `AmoledMode`, `LightMode`, `AmoledModeLight`, `DraculaMode`, `SilentBlueMode`, `LavenderMode`, `MonetMode`).
  - Échelle typographique `TextAppearance.Missa.*` (`@font/google_sans`, `>= 12sp` mobile, variantes `values-sw600dp/` et `values-television/`).
  - Composants unifiés `Widget.Missa.{Pill,Button.Primary,Button.Secondary,Button.Icon}` et anneau de focus TV unique `3dp` jaune MISSA.
- **Performance & Mode Économie de Données (`HOME-02`, `HOME-13`, `FLOW-03`)** :
  - `RecyclerView` modulaire (`ListAdapter` + `DiffUtil` avec payloads sans perte de focus), carrousel `ViewPager2`, squelette `Shimmer` progressif et cache catalogue sur disque (`< 1s` au démarrage à froid).
  - Mode **Data Saver** réduisant la concurrence réseau et gelant l'auto-rotation du carrousel sur connexions mobiles limitées.
- **Internationalisation & Accessibilité (`I18N-01..02`, `A11Y-01`)** :
  - Filtrage par clés stables `MissaCategoryKey` indépendant de la langue active, parité complète `values/strings.xml` (EN) et `values-b+fr/strings.xml` (FR) avec `<plurals>`.
  - Support TalkBack (`accessibilityHeading`, descriptions dynamiques, cibles tactiles `>= 48dp`) et mise à l'échelle des polices jusqu'à `130 %`.

---

## Compilation & Tests / Build & CI

Prérequis : **JDK 17+** et **Android SDK (API 37)**.

```bash
# Exécuter les tests unitaires JUnit4 (classifieur, déduplication, filtres, parité i18n)
./gradlew :app:testPrereleaseDebugUnitTest

# Générer l'APK de debug Prerelease
./gradlew :app:assemblePrereleaseDebug
```

Le workflow GitHub Actions (`.github/workflows/missa-tv-ci.yml`) compile automatiquement le projet, exécute la suite de tests unitaires et publie l'artefact APK `missa-tv-360-prerelease-debug`.

---

## Crédits & Licence Open-Source

MISSA TV 360 PLUS est distribué sous licence **GNU General Public License v3.0 (GPL-3.0)** et s'appuie sur l'écosystème open-source suivant :

- Base moteur & système d'extensions : [reCloudStream / CloudStream](https://github.com/recloudstream/cloudstream) (GPL-3.0)
- Pont d'émulation Libretro Kotlin Multiplatform : [kotlinds / libretro-kmp](https://github.com/kotlinds/libretro-kmp) & cœurs officiels Libretro (SameBoy, FCEUmm, Snes9x, mGBA, Genesis Plus GX)
- Lecture audio & session média : [AndroidX Media3](https://developer.android.com/media/media3) (Apache-2.0)
- Jeux homebrew du catalogue libre : *µCity* (AntonioND, GPL-3.0), *Big2Small* (mdsteele, GPL-3.0), *RoboRun NES* (heaven-hm, GPL-3.0), *Libbet & the Magic Floor* & *240p Test Suite Mini* (Damian Yerrick / pinobatch, Zlib/GPL)
