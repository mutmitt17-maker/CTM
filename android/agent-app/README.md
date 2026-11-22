# Application agent (Android)

Application Android de l’agent de terrain : connexion, catalogue hors ligne, simulation de crédit,
création de client, dossier de crédit et encaissement en espèces. Les actions sont gardées sur le
téléphone puis envoyées au serveur (bouton « Synchroniser maintenant »).

## Obtenir l’APK de test

1. GitHub → onglet « Actions » → workflow « Android App » → dernier run vert.
2. En bas de la page du run, section « Artifacts » : télécharge `ctm-agent-debug-apk`.
3. Décompresse le zip, envoie `app-debug.apk` sur le téléphone et ouvre-le
   (autorise l’installation depuis cette source si Android le demande).

## Ce build est séparé de celui des tests

Le dossier `android/` construit et teste les modules Kotlin purs (rapide, sans SDK Android).
Ce dossier `android/agent-app/` a son propre build : il réutilise ces mêmes modules et ne sert
qu’à fabriquer l’APK. Commande : `gradle :app:assembleDebug` (Android SDK et JDK 17 requis).

## État (jalon 3a)

Fait : connexion, accueil, catalogue en cache, simulation, nouveau client, dossier, encaissement,
file d’actions durable (SQLite), jetons chiffrés (Keystore).

À venir : 3b — livraison (IMEI, photo, signature, enrôlement MDM) et biométrie ;
3c — chiffrement de la base (SQLCipher), envoi automatique en arrière-plan, envoi des photos.
