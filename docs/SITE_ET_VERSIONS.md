# Site vitrine et publication des versions

Le site de présentation et de téléchargement est dans `site/`. Il est hébergé
gratuitement, sur Cloudflare Pages et sur GitHub Pages :

> **https://missatv.pages.dev** (Cloudflare Pages, adresse principale)
>
> https://missamedia69-code.github.io/MISSA_TV_360_PLUS/ (GitHub Pages, copie de secours :
> certains opérateurs bloquent les adresses `github.io`)

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

## 1 bis. Publier aussi sur Cloudflare (recommandé)

Certains opérateurs bloquent les adresses `github.io`. Le workflow
`.github/workflows/site-cloudflare.yml` publie le même site sur Cloudflare Pages, à l'adresse
`https://missatv.pages.dev` (Cloudflare peut ajouter un suffixe si le nom est déjà pris).

1. Sur **https://dash.cloudflare.com/profile/api-tokens** : **Create Token**, modèle
   **« Edit Cloudflare Workers »**, **Use template**. Dans **Permissions**, cliquez
   **+ Add more** et ajoutez deux lignes : **Account → Cloudflare Pages → Edit** et
   **Account → D1 → Edit** (D1 servira au serveur des paiements). Puis **Continue to
   summary** et **Create Token**.
2. Dans GitHub, **Settings → Secrets and variables → Actions → New repository secret** :
   - `CLOUDFLARE_API_TOKEN` : la clé créée ;
   - `CLOUDFLARE_ACCOUNT_ID` : l'identifiant du compte (visible dans l'adresse du tableau de
     bord Cloudflare, `dash.cloudflare.com/<identifiant>/…`).
3. Onglet **Actions → Site vitrine (Cloudflare Pages) → Run workflow**. Ensuite, chaque
   modification de `site/` sur `main` est publiée automatiquement.

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

## Avant la mise en ligne : informations à compléter

Les pages légales contiennent des champs surlignés en jaune `[à compléter : …]`.
Remplacez-les par vos informations, en français et en anglais :

| Page française | Page anglaise | À compléter |
|---|---|---|
| `site/mentions-legales.html` | `site/en/legal-notice.html` | nom ou raison sociale, ville et pays, directeur de la publication |
| `site/contact.html` | `site/en/contact.html` | e-mail de contact, numéro WhatsApp (ou supprimer ce bloc) |

## Modifier le site

Le site est fait de fichiers simples, sans outil de construction :

- `site/index.html` (français) et `site/en/index.html` (anglais) : la page principale ;
- `site/mentions-legales.html`, `site/confidentialite.html`, `site/contact.html` et leurs
  équivalents dans `site/en/` : les pages légales et le contact ;
- `site/style.css` (apparence, commune à toutes les pages) et `site/app.js` (lecture de la
  dernière version, dans la langue de la page).

Ouvrez `site/index.html` dans un navigateur pour le prévisualiser. Quand vous modifiez un
texte, pensez à faire la même modification dans l'autre langue.

La politique de confidentialité décrit ce que fait l'application **aujourd'hui** (aucune
donnée envoyée à l'éditeur, liste des services contactés). Si l'application change, par
exemple avec un compte, un paiement ou des statistiques, cette page doit être mise à jour
avant la publication de la version concernée.
