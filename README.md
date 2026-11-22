# CTM · Crédit Téléphone (Application Android)

Application Android (Kotlin + Jetpack Compose + Material 3) pour la plateforme **CTM · Crédit Téléphone** (vente à crédit de téléphones en USD).

Elle réunit dans une seule application adaptative et hors-ligne les parcours **Agent terrain**, **Back-office**, et **Portail Client**, connectés au backend Supabase (Auth, PostgREST et Edge Function `api`).

## Architecture multi-modules

| Module | Rôle |
|---|---|
| `:app` | Application Android Jetpack Compose (Material 3, Navigation Compose type-safe, thème clair/sombre, polices locales, stockage SQLite & chiffré) |
| `:core-model` | Modèles métier purs, calculateur d'échéancier `BigDecimal` v1, vérificateur de conformité serveur, validateurs téléphone Congo (`08/09`) & IMEI Luhn |
| `:core-network` | Moteur de synchronisation hors-ligne (`SyncEngine`), file idempotente FIFO (`SyncMessages`, `SyncStore`) et gestion d'erreurs |
| `:core-security` | Contrats de sécurité (`TokenStore`, `DeviceFingerprint`, `PhotoVault`, `BiometricGate`, `DatabaseKey`) |
| `:app-agent` | ViewModels agent (`LoginViewModel`, `HomeViewModel`, `CustomerViewModel`, `ApplicationViewModel`, `PaymentViewModel`, `DeliveryViewModel`) |
| `:app-client` | ViewModels portail client (`ClientLoginViewModel`, `ContractViewModel`, `PayInstructionsViewModel`) |
| `:agent-http` | Client réseau OkHttp + Gson vers Supabase Auth, PostgREST et Edge Function `/functions/v1/api` |

## Fonctionnalités portées

- **Connexion & mot de passe oublié** : authentification Supabase email/mot de passe, rafraîchissement automatique de jeton (`TokenStore` via `EncryptedSharedPreferences`), réinitialisation de mot de passe par e-mail.
- **Mode hors-ligne agent terrain** :
  - Cache local du catalogue téléphones, de la politique de crédit et vérification cryptographique/mathématique du calculateur v1.
  - Enregistrement client (KYC), simulation & création de dossier de crédit, encaissement cash sur numéro de contrat (`CTM-YYYY-NNNNN`).
  - File de synchronisation SQLite persistante avec inspection des éléments en échec et correction de payload JSON avant renvoi.
- **Livraison en ligne (6 étapes)** : sélection d'un dossier approuvé, démarrage, vérification IMEI (Luhn + serveur), preuve de remise (Photo Picker sans permission + signature tactile `Canvas` avec empreinte SHA-256), demande et vérification d'enrôlement MDM, clôture et activation du contrat.
- **Back-office complet** (selon le rôle : `system_admin`, `supervisor`, `risk_analyst`, `finance`, `collections`, `stock_manager`) :
  - Dossiers & dossiers à décider (approuver / refuser avec motif et séparation créateur/décideur).
  - Politique de crédit (consultation et mise à jour sécurisée).
  - Paiements : saisie manuelle Mobile Money, confirmation de cash agent, gestion des numéros marchands.
  - Appareils & MDM : verrouillage / déverrouillage / levée de blocage sécurité, changement de SIM en attente, politique de restriction et fournisseurs par marque, affectation du stock aux agences.
  - Recouvrement & remises : tâches assignables/clôturables, recherche contrat et remise de pénalités ou clôture exceptionnelle, demandes d'approbation à double validation.
- **Portail Client** : consultation des contrats, détail des échéances, montants suggérés et instructions Mobile Money (accessible dès que la session client est active).

## Build & Tests

```bash
# Compiler l'APK debug
gradle :app:assembleDebug

# Exécuter les tests unitaires des modules métier, réseau, sécurité, HTTP et ViewModels
gradle :core-model:test :core-network:test :core-security:test :app-agent:test :agent-http:test :app-client:test
```
