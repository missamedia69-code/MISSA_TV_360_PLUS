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
