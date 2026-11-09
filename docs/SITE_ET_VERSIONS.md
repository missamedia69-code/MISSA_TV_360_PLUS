# Site vitrine et publication des versions

Le site de présentation et de téléchargement est dans `site/`. Il est hébergé
gratuitement par GitHub Pages :

> https://missamedia69-code.github.io/MISSA_TV_360_PLUS/

Son bouton « Télécharger l'APK » pointe toujours vers l'APK de la **dernière version**
publiée dans GitHub Releases.

Deux workflows s'en chargent :

| Workflow | Quand | Ce qu'il fait |
|---|---|---|
| `.github/workflows/site.yml` | modification de `site/` sur `main` | publie le site sur GitHub Pages |
| `.github/workflows/release.yml` | tag `v…` poussé, ou lancement manuel | construit l'APK signé et crée la version |

---

## 1. Activer GitHub Pages (une seule fois)

1. Sur GitHub, ouvrez le dépôt, puis **Settings → Pages**.
2. Sous **Build and deployment → Source**, choisissez **GitHub Actions**.
3. Fusionnez la branche sur `main` : le site est publié quelques minutes plus tard.
   Vous pouvez aussi le relancer depuis l'onglet **Actions → Site vitrine → Run workflow**.

## 2. Créer la clé de signature (une seule fois)

Android n'accepte une mise à jour que si elle est signée avec **la même clé** que la
version installée. Il faut donc une clé à vous, créée une fois et **conservée
précieusement**. Si vous la perdez, vos utilisateurs devront désinstaller l'application
(et perdre leurs favoris) pour passer à la version suivante.

Sur votre ordinateur, avec Java installé :

```bash
keytool -genkeypair -v -keystore missa-release.keystore -alias missa -keyalg RSA -keysize 4096 -validity 36500
```

Choisissez un mot de passe solide. Gardez `missa-release.keystore` et ses mots de passe
dans un endroit sûr, en dehors du dépôt : **ne les commitez jamais**.

Convertissez ensuite la clé en texte pour GitHub :

```bash
base64 -w 0 missa-release.keystore > missa-release.keystore.b64
```

(Sur macOS : `base64 -i missa-release.keystore -o missa-release.keystore.b64`.)

## 3. Ajouter les secrets (une seule fois)

**Settings → Secrets and variables → Actions → New repository secret**, quatre fois :

| Nom | Valeur |
|---|---|
| `MISSA_KEYSTORE_BASE64` | le contenu de `missa-release.keystore.b64` |
| `MISSA_KEYSTORE_PASSWORD` | le mot de passe de la clé (keystore) |
| `MISSA_KEY_ALIAS` | `missa` (l'alias choisi plus haut) |
| `MISSA_KEY_PASSWORD` | le mot de passe de l'alias (souvent le même) |

Sans ces secrets, le workflow de publication s'arrête avec un message clair : il ne
publie jamais un APK signé avec une clé jetable.

## 4. Publier une version

1. Mettez à jour `versionName` et augmentez `versionCode` dans `gradle/libs.versions.toml`.
2. Fusionnez sur `main`.
3. Créez le tag correspondant et poussez-le :

   ```bash
   git tag v4.8.0
   ```

   ```bash
   git push origin v4.8.0
   ```

   Ou bien : onglet **Actions → Publier une version → Run workflow**, en indiquant le numéro.

Le workflow construit l'APK `stable`, vérifie qu'il n'est pas signé avec la clé de debug,
calcule son empreinte SHA-256 et crée la version GitHub avec deux fichiers :
`MISSA-TV-360-PLUS.apk` et `MISSA-TV-360-PLUS.apk.sha256`. Le site affiche aussitôt le
nouveau numéro, la taille et la date.

## Modifier le site

Le site est fait de trois fichiers simples, sans outil de construction :
`site/index.html` (textes), `site/style.css` (apparence) et `site/app.js` (lecture de la
dernière version). Ouvrez `site/index.html` dans un navigateur pour le prévisualiser.
