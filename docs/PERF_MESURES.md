# Mesurer avant / après (section 7 du rapport d'audit)

Toutes les actions A1 → A13 ont été appliquées en aveugle côté mesure : le rapport est
statique et ce dépôt n'a ni émulateur ni appareil. **Les chiffres doivent être relevés sur
un vrai téléphone ou une vraie Android TV**, sinon on ne saura pas si les gains sont réels.

## Règle d'or

Mesurer **deux fois le même appareil**, dans les mêmes conditions, avec **l'APK release**
(jamais le debug : une application debuggable tourne sans les optimisations d'ART, donc
plus lentement — c'est tout l'objet de A1).

## Installer les deux versions

Le paquet de la variante release est `com.missatv.app.prerelease`.

```bash
# 1. AVANT : le code tel qu'il était au commit de référence 97a4869
git worktree add ../MISSA_AVANT 97a4869
cd ../MISSA_AVANT && ./gradlew :app:assemblePrereleaseRelease
adb install -r app/build/outputs/apk/prerelease/release/app-prerelease-release.apk
# -> mesurer, noter les chiffres, puis désinstaller
adb uninstall com.missatv.app.prerelease

# 2. APRÈS : la branche courante
cd ../MISSA_TV_360_PLUS && ./gradlew :app:assemblePrereleaseRelease
adb install -r app/build/outputs/apk/prerelease/release/app-prerelease-release.apk
# -> mesurer les mêmes choses
```

Les APK sont aussi publiés par la CI (`missa-tv-360-prerelease-release`), conservés 7 jours.

## Les quatre mesures

### 1. Démarrage à froid

```bash
adb shell am force-stop com.missatv.app.prerelease
adb shell am start -W -n com.missatv.app.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity2
```

→ relever `TotalTime` (ms). Refaire **3 fois** et garder la médiane : la première mesure
est toujours faussée par le chargement des bibliothèques.

### 2. Fluidité au défilement

Ouvrir l'accueil, puis **défiler lentement sur toute la page** (et recommencer dans
Films, Musiques, Enfants) :

```bash
adb shell dumpsys gfxinfo com.missatv.app.prerelease reset   # avant de défiler
# ... défiler ...
adb shell dumpsys gfxinfo com.missatv.app.prerelease
```

→ relever **Janky frames** (le pourcentage) et le nombre d'images à plus de 8 ms.

### 3. Freezes (le vrai symptôme : l'image qui se fige)

```bash
adb logcat | grep -E "Skipped [0-9]+ frames|Davey|ANR|Slow "
```

→ compter les `Skipped … frames` pendant 2 minutes d'usage. **C'est la mesure la plus
parlante** : les actions A2, A3, A4 et A5 visent précisément à les faire disparaître.

### 4. Mémoire

```bash
adb shell dumpsys meminfo com.missatv.app.prerelease
```

→ relever `TOTAL PSS` après avoir parcouru les 9 univers (les catalogues Radio, Musique et
Enfants sont les plus gros).

### 5. Taille de l'APK

```bash
ls -l app/build/outputs/apk/prerelease/release/*.apk
```

→ A5 (catalogues sortis de `rebuild_preference`) et A8 (R8 activé) doivent la faire baisser.

## Tableau à remplir

| Mesure | Avant (97a4869) | Après (branche) | Ce qui doit bouger |
|---|---|---|---|
| Démarrage à froid — `TotalTime` (ms) | | | A6 (plus de refresh réseau forcé) |
| `Skipped frames` sur 2 min d'usage | | | A2, A3, A4, A5 |
| Janky frames (%) au défilement | | | A3, A4, A7 |
| `TOTAL PSS` après les 9 univers (Mo) | | | A5, A7 |
| Taille de l'APK release (Mo) | | | A5, A8 |

## Ce qui peut ne rien changer (et pourquoi)

- **A1, A9, A10, A11, A12** ne sont pas des optimisations de vitesse : sécurité,
  sauvegarde, permissions et économiseur de données. Ils ne doivent rien bouger dans ce
  tableau, à part la taille de l'APK pour A9/A11 (négligeable).
- **A8 (R8)** peut rendre l'apparemment *légèrement* plus lente au premier lancement
  (le code est réorganisé) mais réduit nettement la taille. Si une extension se met à
  planter, c'est A8 : dire quelle extension et `adb logcat` le message d'erreur.
