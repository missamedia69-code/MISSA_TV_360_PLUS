# Essai de 24 h, abonnement et paiements KPay

## Comment ça marche

1. **Premier lancement** : l'application envoie au serveur une empreinte de l'appareil
   (jamais l'identifiant brut) et le modèle. Le serveur note la date : c'est le début de
   l'essai gratuit de **24 h**. Réinstaller l'application ne relance pas l'essai.
2. **Fin de l'essai** : blocage immédiat. L'écran affiche le code de l'appareil
   (ex. `MISSA-7K2Q9P`) et :
   - sur **TV**, un QR code à scanner avec le téléphone ;
   - sur **téléphone / tablette**, un bouton « Payer et activer ».
   Les deux ouvrent `https://missatv.pages.dev/activer/?code=…`.
3. **Paiement** : de **1 à 9 mois** à 1 000 FCFA le mois (30 jours par mois payé), ou
   **1 an** (365 jours) pour 10 000 FCFA ; au-delà de 9 mois, c'est la formule 1 an. Le
   client paie sur la page sécurisée de KPay (Mobile Money des pays couverts par KPay, ou
   carte). Un nouveau paiement s'ajoute à la date de fin de l'abonnement en cours.
4. **Activation automatique** : KPay prévient le serveur (notification signée), l'écran de
   blocage vérifie toutes les 5 s et se ferme tout seul.
5. **2 appareils par abonnement** : sur la page « Activer » du 2ᵉ appareil, rubrique
   « J'ai déjà un abonnement », on entre le code de l'appareil qui a payé. Un appareil
   inactif depuis 30 jours libère sa place.
6. **Expiration** : 1 jour de grâce (rappel à l'ouverture), puis blocage.
7. **Hors connexion** : l'application fonctionne 3 jours sans vérification, puis demande
   une connexion.

Tant que le blocage n'est pas activé (étape 4 ci-dessous), **l'application reste libre**,
même si le serveur est en ligne.

## Mise en service (une seule fois)

Le serveur (dossier `cloudflare/`) est publié avec le site par le workflow
« Site vitrine (Cloudflare Pages) ». La base de données D1 `missatv` est créée
automatiquement.

1. **KPay** : dans votre tableau de bord, application MISSA TV :
   - URL de notification (callback des dépôts ou URL générique) :
     `https://missatv.pages.dev/api/kpay-webhook`
   - notez la clé publique (`kpay_test_…` puis `kpay_live_…` après la vérification
     d'identité), la clé secrète et le secret de signature des webhooks.
2. **GitHub → Settings → Secrets and variables → Actions → New repository secret** :
   | Nom | Valeur |
   |---|---|
   | `KPAY_API_KEY` | clé publique KPay |
   | `KPAY_SECRET_KEY` | clé secrète KPay |
   | `KPAY_WEBHOOK_SECRET` | secret de signature des webhooks KPay |
   | `MISSA_ADMIN_TOKEN` | (facultatif) un long mot de passe à vous, pour offrir des jours |
3. **Tester** avec les clés `kpay_test_…` : onglet **Actions → Site vitrine (Cloudflare
   Pages) → Run workflow**, puis ouvrez `https://missatv.pages.dev/activer/?code=…` avec le
   code affiché par l'application.
4. **Activer le blocage** : même écran GitHub, onglet **Variables** → **New repository
   variable** `MISSA_LICENCE_ENFORCED` = `1`, puis relancez le workflow. Pour tout libérer
   (panne, promotion), mettez `0` et relancez.
5. Passez aux clés `kpay_live_…` quand KPay a validé votre compte (mettez à jour les deux
   secrets et relancez le workflow).

## Support : offrir des jours à un client

```bash
curl -X POST https://missatv.pages.dev/api/admin/grant -H "Authorization: Bearer VOTRE_MISSA_ADMIN_TOKEN" -H "content-type: application/json" -d '{"code":"MISSA-7K2Q9P","days":30}'
```

## Avant d'activer le blocage

- Mettre à jour la politique de confidentialité (FR et EN) : l'application envoie
  désormais au serveur une empreinte de l'appareil et son modèle, et le serveur garde les
  paiements (référence KPay, montant, date).
- Ajouter les conditions d'abonnement (prix, durée, 2 appareils, pas de remboursement ou
  votre règle) dans les conditions d'utilisation.

## Limites connues

- Un utilisateur qui bloque l'adresse du serveur **dès l'installation** n'est jamais
  bloqué (choix volontaire : une panne du serveur ne doit pas bloquer tout le monde).
- Reculer l'horloge du téléphone de plus d'une heure force une nouvelle vérification.
