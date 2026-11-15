# Applications Android (Agent et Client)

Projet Gradle multi-modules. État actuel : **jalon 1**.

| Module | Rôle | État |
|---|---|---|
| `:core-model` | Calculateur de crédit identique au serveur, simulateur, validations (IMEI, téléphone). Kotlin pur, sans Android. | fait, testé |
| `:core-security`, `:core-network` | Chiffrement, jetons, client de l'API | à faire (jalon 2) |
| `:app-agent`, `:app-client` | Les deux applications | à faire (jalons 3 à 6) |

Les décisions de conception sont dans le prompt du projet. Les manques du serveur sont dans `BACKEND_GAPS.md`.

## Lancer les tests

Sur GitHub : le workflow **Android** se lance à chaque push qui touche `android/`.

En local (Java 17 et Gradle 8.14) :

```bash
cd android
gradle :core-model:test
```

## Règle d'or du calculateur

`LoanCalculator` doit donner exactement les mêmes montants que les fonctions SQL `compute_offer()` et `installment_plan()`.
À chaque téléchargement du catalogue, l'application appelle `CalculatorVerifier.verify(spec)` avec les vecteurs reçus du serveur.
Un seul écart désactive la simulation hors ligne.

Les valeurs attendues de `src/test/.../Vectors.kt` viennent d'une implémentation indépendante des règles écrites.
Pour les confronter au vrai serveur, lancer `select public.calculator_spec();` dans le SQL Editor et comparer les `expected`.
