# Chiffres du catalogue MISSA TV 360 PLUS

Dernière mise à jour : **2026-11-05** (audit H10)

Ce document répond à une seule question : **de combien de contenus l'application
dispose-t-elle réellement ?** Toute communication (fiche store, publicité,
capture) doit s'appuyer sur les chiffres mesurés ici, jamais sur une estimation.

Règle : un chiffre non mesuré n'est pas publié. « Des milliers de radios » ne
peut être écrit que si la ligne correspondante ci-dessous le prouve à la date
de la communication.

---

## 1. Ce qui est mesuré, et comment

Le catalogue n'est pas un fichier : il est **récolté à l'exécution** auprès des
extensions actives, puis dédoublonné. L'application imprime elle-même son
recensement dans le logcat, une fois la récolte terminée :

```
adb logcat -s MissaCatalog:V | grep "Catalog harvest"
```

La ligne utile :

```
Catalog harvest ok: rawItems=2286 entries=1979 perUniverse={films=302, series=1399, ...}
```

- `rawItems` : fiches remontées par les extensions, avant dédoublonnage.
- `entries` : fiches conservées après `normalizeAndDeduplicate` ( titre + année
  + type ). C'est le chiffre qui compte.
- `perUniverse` : répartition par univers, une fiche pouvant appartenir à
  plusieurs univers.
- La ligne `Catalog harvest produced nothing` signale l'échec de toutes les
  extensions : ce n'est pas un catalogue vide, c'est une panne.

Les radios ne viennent pas de la récolte : `DirectRadioPlayerManager.allPresetStations()`
additionne les **préréglages écrits dans le code**, les **bouquets Laut.fm**
générés (`lautInstant`) et les **catalogues distants** (Radio Browser avec
`hidebroken=true`, SomaFM, Icecast/Xiph), puis dédoublonne le tout
(`mergeStationCatalogues`). Le total n'existe donc qu'à l'exécution.

---

## 2. Chiffres connus sans exécution (comptés dans les sources)

Mesurés le 2026-11-05 en comptant les entrées des fichiers de préréglages :

| Univers | Préréglages écrits dans le code | Fichier |
|---|---|---|
| Radio | **24** stations + bouquets générés + catalogues distants | `MissaRadioProviders.kt` (`curatedInstantStations`) |
| Musique | **16** titres | `MissaMusicProviders.kt` |
| Enfants | **7** éléments | `MissaKidsProviders.kt` |
| TV direct | **0** chaîne livrée — l'utilisateur fournit sa playlist M3U | `MissaUniverseDialogs.kt` |
| Films / Séries / Documentaires | **0** — tout vient des extensions actives | récolte à l'exécution |

Ces colonnes ne sont pas le catalogue : ce sont les contenus **embarqués**. Le
reste dépend de l'appareil, du réseau et des extensions activées.

---

## 3. Dernier recensement à l'exécution disponible

Mesure du **2026-11-04**, relevée sur le logcat d'un émulateur Android TV
(8 extensions actives). **À refaire** avant toute communication : c'est une
photo à un instant donné, avec les extensions et le réseau de ce jour-là.

```
rawItems    = 2286
entries     = 1979      <-- fiches uniques après dédoublonnage
perUniverse = { films = 302, séries = 1399, documentaires = 278, enfants = 190 }
activeApis  = 8
enrichissement : ok = 27, échecs = 0, ignorés = 3
```

Radios : **non mesuré à cette date** (la ligne de recensement ne couvre que la
récolte des extensions, pas `allPresetStations`). C'est le premier chiffre à
compléter.

---

## 4. Ce que l'interface affiche (correctif H10)

Avant l'audit, les cartes d'univers affichaient un compte lu sur une récolte
encore partielle : la carte Films annonçait « 16 titres » alors que
l'univers en contenait 302. Un chiffre faux est pire qu'un chiffre absent.

Depuis l'audit H10 :

- le compte n'est affiché **que lorsque la récolte est terminée**
  (`!snapshot.isLoading`) ; pendant le chargement la carte n'affiche rien ;
- le compte est celui de l'univers **après dédoublonnage**, pas celui de la
  page en cours de chargement ;
- les préréglages musique et radio, qui ne dépendent pas de la récolte,
  restent affichés en permanence.

Le chiffre est imprimé au milieu de la carte d'univers, là où le vide se
trouvait (H9) : il sert de preuve de contenu.

---

## 5. À refaire avant chaque communication

1. `adb logcat -s MissaCatalog:V | grep "Catalog harvest"` après un démarrage
   à froid, réseau disponible, toutes les extensions activées.
2. Relever `entries` et `perUniverse`.
3. Ajouter le nombre de stations radio retourné par
   `DirectRadioPlayerManager.allPresetStations().size` — à exposer dans un log
   si le chiffre doit servir en communication. **C'est le manque actuel.**
4. Mettre à jour la date et le tableau de la section 3.
