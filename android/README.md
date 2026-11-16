# Applications Android (Agent et Client)

Projet Gradle multi-modules. État actuel : **jalon 1**.

| Module | Rôle | État |
|---|---|---|
| `:core-model` | Calculateur de crédit identique au serveur, simulateur, validations (IMEI, téléphone). Kotlin pur, sans Android. | fait, testé |
| `:core-network` | Moteur de synchronisation hors ligne (file, reprise avec la même clé, conflits), classement des erreurs de l'API, messages en français. Kotlin pur ; le client HTTP et le JSON viendront avec le module Android. | fait, testé |
| `:core-security` | Chiffrement, jetons, empreinte d'appareil | à faire (jalon 2, partie Android) |
| `:app-agent`, `:app-client` | Les deux applications | à faire (jalons 3 à 6) |

Les décisions de conception sont dans le prompt du projet. Les manques du serveur sont dans `BACKEND_GAPS.md`.

## Lancer les tests

Sur GitHub : le workflow **Android** se lance à chaque push qui touche `android/`.

En local (Java 17 et Gradle 8.14) :

```bash
cd android
gradle :core-model:test :core-network:test
```

## Règle d'or du calculateur

`LoanCalculator` doit donner exactement les mêmes montants que les fonctions SQL `compute_offer()` et `installment_plan()`.
À chaque téléchargement du catalogue, l'application appelle `CalculatorVerifier.verify(spec)` avec les vecteurs reçus du serveur.
Un seul écart désactive la simulation hors ligne.

Les valeurs attendues de `src/test/.../Vectors.kt` viennent d'une implémentation indépendante des règles écrites.
Pour les confronter au vrai serveur, lancer `select public.calculator_spec();` dans le SQL Editor et comparer les `expected`.

## Synchronisation hors ligne (`:core-network`)

- `SyncEngine.runOnce()` envoie la file par lots de 50 maximum, dans l'ordre de création.
- Chaque action est marquée `SENT` **avant** l'appel réseau. Après une coupure ou un arrêt brutal, elle repart avec **la même clé d'idempotence** : le serveur renvoie le résultat déjà enregistré, sans doublon.
- Un conflit ou un échec bloque les actions suivantes du **même client** (elles attendent), pas celles des autres clients.
- Pour corriger une action en échec : `SyncEngine.correct(...)` crée une **nouvelle** action avec une nouvelle clé. L'ancienne est gardée pour l'historique et ne bloque plus.
- L'application fournit deux choses : `SyncStore` (Room chiffré) et `SyncApi` (appel HTTP de `sync_batch`).
