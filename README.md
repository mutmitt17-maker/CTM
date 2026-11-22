# CTM · Crédit Téléphone — Application Android Agent (Niveau 2)

Application Android officielle de l'**Agent de terrain CTM · Crédit Téléphone** (Kotlin + Jetpack Compose + Material 3).

Elle correspond au **Niveau 2** de la plateforme CTM (distinct du site Back-Office web et de la future application Client), connecté au backend Supabase (Auth, PostgREST et Edge Function `api`).

## Architecture multi-modules

| Module | Rôle |
|---|---|
| `:app` | Application Android Jetpack Compose (Material 3, Navigation Compose type-safe, thème clair/sombre, polices locales, stockage SQLite & chiffré) |
| `:core-model` | Modèles métier purs, calculateur d'échéancier `BigDecimal` v1, vérificateur de conformité serveur, validateurs téléphone Congo (`08/09`) & IMEI Luhn |
| `:core-network` | Moteur de synchronisation hors-ligne (`SyncEngine`), file idempotente FIFO (`SyncMessages`, `SyncStore`) et gestion d'erreurs |
| `:core-security` | Contrats de sécurité (`TokenStore`, `DeviceFingerprint`, `PhotoVault`, `BiometricGate`, `DatabaseKey`) |
| `:app-agent` | ViewModels agent (`LoginViewModel`, `HomeViewModel`, `CustomerViewModel`, `ApplicationViewModel`, `PaymentViewModel`, `DeliveryViewModel`) |
| `:agent-http` | Client réseau OkHttp + Gson vers Supabase Auth, PostgREST et Edge Function `/functions/v1/api` |

## Fonctionnalités de l'application Agent

- **Connexion agent & mot de passe oublié** : authentification Supabase email/mot de passe, rafraîchissement automatique de jeton (`TokenStore` via `EncryptedSharedPreferences`), réinitialisation de mot de passe par e-mail.
- **Accueil & mode hors-ligne** :
  - Cache local du catalogue téléphones et de la politique de crédit.
  - Vérification de conformité du calculateur local v1 par rapport aux vecteurs du serveur.
- **Nouveau client (hors-ligne)** : enregistrement KYC et validation du numéro Congo (`08`/`09`).
- **Simulateur & création de dossier (hors-ligne)** : sélection du téléphone, calcul de l'acompte, durée autorisée, fréquence (journalier/hebdomadaire) et échéancier complet `BigDecimal`, puis mise en file de création de dossier.
- **Encaissement cash (hors-ligne)** : saisie de paiement en espèces sur numéro de contrat (`CTM-YYYY-NNNNN`).
- **File de synchronisation SQLite** : suivi des actions en attente, en conflit ou en échec, avec inspection et correction du payload JSON avant renvoi au serveur.
- **Livraison téléphone (en ligne, 6 étapes)** : sélection d'un dossier approuvé, démarrage, vérification IMEI (Luhn + serveur), preuve de remise (Photo Picker sans permission + signature tactile `Canvas` avec empreinte SHA-256), demande et contrôle d'enrôlement MDM, clôture et activation du contrat.

## Build & Tests

```bash
# Compiler l'APK debug
gradle :app:assembleDebug

# Exécuter les tests unitaires des modules agent
gradle :core-model:test :core-network:test :core-security:test :app-agent:test :agent-http:test
```
