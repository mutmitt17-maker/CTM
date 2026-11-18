# Applications Android (Agent et Client)

Projet Gradle multi-modules. État actuel : **jalons 1-4**.

| Module | Rôle | État |
|---|---|---|
| `:core-model` | Calculateur de crédit identique au serveur, simulateur, validations (IMEI, téléphone). Kotlin pur, sans Android. | ✅ fait, testé |
| `:core-network` | Moteur de synchronisation hors ligne (file, reprise avec la même clé, conflits), classement des erreurs de l'API, messages en français. Kotlin pur. | ✅ fait, testé |
| `:core-security` | Interfaces : TokenStore (jetons), DeviceFingerprint (empreinte), BiometricGate (biométrie), DatabaseKey (clé SQLCipher), PhotoVault (photos KYC/livraison). Kotlin pur, implémentations Android dans `:app-agent`. | ✅ fait, testé |
| `:app-agent` | ViewModels : Login, Home (catalogue + sync), Customer (nouveau client), Application (dossier + simulation hors ligne), Delivery (flux livraison), Payment (encaissement). Kotlin pur. | ✅ fait, testé |
| `:app-client` | Application client (OTP, contrat, paiements) | 🔜 jalons 5-6 |

Les décisions de conception sont dans le prompt du projet. Les manques du serveur sont dans `BACKEND_GAPS.md`.

## Lancer les tests

Sur GitHub : le workflow **Android** se lance à chaque push qui touche `android/`.

En local (Java 17 et Gradle 8.14) :

```bash
cd android
gradle :core-model:test :core-network:test :core-security:test :app-agent:test
```

## Architecture des ViewModels (`:app-agent`)

```
LoginViewModel      → AgentApi.login() → TokenStore.save()
HomeViewModel       → AgentApi.me() + offlineCatalogue() + SyncEngine.runOnce()
CustomerViewModel   → SyncEngine.enqueue(customer.create)
ApplicationViewModel→ LoanSimulator.simulate() hors ligne + SyncEngine.enqueue(application.submit)
DeliveryViewModel   → SyncEngine.enqueue(start_delivery / verify_imei / record_proof / complete)
PaymentViewModel    → SyncEngine.enqueue(payment.cash)
```

Chaque ViewModel :
- **Ne dépend pas d'Android** (pas d'Activity, pas de Context) → testable en Kotlin pur avec JUnit.
- Expose un `StateFlow<State>` observable depuis l'UI.
- Utilise des interfaces (`AgentApi`, `SyncEngine`, `TokenStore`, `PhotoVault`) injectées par le constructeur.

## Règle d'or du calculateur

`LoanCalculator` doit donner exactement les mêmes montants que les fonctions SQL `compute_offer()` et `installment_plan()`.
À chaque téléchargement du catalogue, l'application appelle `CalculatorVerifier.verify(spec)` avec les vecteurs reçus du serveur.
Un seul écart désactive la simulation hors ligne.

## Synchronisation hors ligne (`:core-network`)

- `SyncEngine.runOnce()` envoie la file par lots de 50 maximum, dans l'ordre de création.
- Chaque action est marquée `SENT` **avant** l'appel réseau. Après une coupure ou un arrêt brutal, elle repart avec **la même clé d'idempotence** : le serveur renvoie le résultat déjà enregistré, sans doublon.
- Un conflit ou un échec bloque les actions suivantes du **même client** (elles attendent), pas celles des autres clients.
- Pour corriger une action en échec : `SyncEngine.correct(...)` crée une **nouvelle** action avec une nouvelle clé. L'ancienne est gardée pour l'historique et ne bloque plus.
- L'application fournit deux choses : `SyncStore` (Room chiffré) et `SyncApi` (appel HTTP de `sync_batch`).
