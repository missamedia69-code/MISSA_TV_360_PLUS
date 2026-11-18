# Extensions de MISSA TV

L'application lit **`extensions/config.json`** (branche `main`) à chaque démarrage et s'y
conforme sur tous les appareils, sans nouvelle version de l'APK :

- les **dépôts** de la liste sont ajoutés, les autres sont retirés ;
- les **extensions** de la liste sont installées (puis mises à jour comme d'habitude), les
  autres extensions de ces dépôts sont désinstallées ;
- chaque extension diffuse dans les **univers** indiqués.

Tant que ce fichier n'existe pas (ou s'il ne contient aucune extension valide), rien n'est
modifié sur les appareils.

## Créer le fichier depuis l'application

1. Sur votre appareil, installez les extensions et réglez leurs univers.
2. **Paramètres → Extensions** (code administrateur), menu **⋮** →
   **Exporter la configuration (MISSA TV)**.
3. Le fichier `missa_extensions_config_<date>.json` est enregistré dans le dossier de
   sauvegarde (**Téléchargements** par défaut, réglable dans *Mises à jour et Sauvegarde*).
4. Déposez-le ici sous le nom **`extensions/config.json`**, sur `main`.

Pour essayer de nouvelles extensions sur votre appareil sans qu'elles soient retirées au
démarrage suivant : menu **⋮** → **Ne plus synchroniser les extensions sur cet appareil**.

## Format

```json
{
  "version": 1,
  "extensions": [
    {
      "depot": "https://exemple.org/repo.json",
      "nomDepot": "Mon dépôt",
      "extension": "NomInterne",
      "nom": "Nom affiché",
      "univers": ["films", "series"]
    }
  ]
}
```

| Champ | Rôle |
|---|---|
| `depot` | adresse HTTPS du dépôt (`repo.json`) |
| `extension` | nom interne de l'extension dans ce dépôt |
| `univers` | `films`, `series`, `shorts` (courts métrages), `sports`, `podcasts` ; `[]` = automatique ; `["none"]` = n'apparaît dans aucun univers |
| `nomDepot`, `nom` | facultatifs, pour s'y retrouver |

Musiques, Jeunesse et Documentaires (YouTube), TV en direct (`tv/chaines.m3u`) et Radio
n'affichent pas le contenu des extensions.

**Ne publiez que des extensions dont vous avez le droit de diffuser les contenus.**

## Publier vos propres extensions (.cs3)

Vos extensions compilées peuvent être hébergées ici même, dans **le dépôt d'extensions MISSA**,
et installées automatiquement sur tous les appareils.

1. Déposez le fichier dans **`extensions/depot/`** (par exemple `extensions/depot/MaSource.cs3`).
2. Décrivez-le dans **`extensions/depot/extensions.json`** :

   ```json
   [
     {
       "fichier": "MaSource.cs3",
       "nom": "Ma Source",
       "univers": ["films", "series"],
       "types": ["Movie", "TvSeries"],
       "langue": "fr",
       "description": "Films et séries de Ma Source"
     }
   ]
   ```

3. Lancez **`python3 extensions/outils/depot.py`** (ou demandez-le à Claude) : il vérifie le
   fichier (archive valide, `manifest.json`, code compilé, version), puis génère
   `extensions/depot/repo.json` et `plugins.json`, et ajoute l'extension à `config.json` avec
   ses univers, **sans toucher aux entrées des autres dépôts**.
4. Fusionnez sur `main` : au démarrage suivant, chaque appareil ajoute le dépôt MISSA, installe
   l'extension et applique ses univers.

**Mise à jour d'une extension** : remplacez le `.cs3` par une version recompilée avec un
numéro `version` plus grand (dans son manifest), relancez l'outil : les appareils la mettent à
jour tout seuls.

**Important** : `config.json` s'impose aux appareils. S'il ne contient que vos extensions
MISSA, les extensions des autres dépôts sont retirées des appareils synchronisés. Exportez
d'abord votre configuration complète depuis l'application (menu ⋮ → « Exporter la
configuration ») et placez-la dans `config.json` **avant** de lancer l'outil.

