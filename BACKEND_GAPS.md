# CTM · Écarts et points à confirmer côté serveur (Android Agent)

Ce document recense les dépendances serveur vérifiées et les points qui demandent confirmation ou évolution côté Supabase (Edge Functions & migrations SQL).

## Étape 1 — KYC, biométrie, contacts d'urgence, consentements et Storage

### Comportement confirmé côté serveur
1. **Ordres de synchronisation hors ligne (`process_sync_batch` / `process_sync_action`)** :
   - Les 7 actions gérées sont `customer.create`, `customer.update`, `kyc.submit`, `emergency_contact.add`, `consent.record`, `application.submit`, `payment.cash`.
   - `application.submit` peut être envoyé avant la fin de la vérification biométrique : `evaluate_application` maintient le dossier en `pending` (`awaiting_biometric_verification`) jusqu'à l'exécution de `worker-biometrics` + `record_biometric_verification`, ou le passe en `pending_review` (`biometrics_not_verified`).
2. **Stockage des photos KYC (bucket privé `kyc`)** :
   - Bucket privé INSERT-only (pas d'écrasement ni de relecture agent), taille max 5 Mo (JPEG/PNG), chemin `kyc/<customer_id>/<unique>.jpg`.
   - La RLS exige que le client (`customer`) existe déjà sur le serveur avant l'upload Storage : l'app Android exécute donc `customer.create` -> upload Storage `kyc/<customer_id>/...` -> `kyc.submit` (retenu dans `SyncEngine` tant que ses photos sont encore en attente dans `AndroidPhotoVault`).
3. **Biométrie côté serveur uniquement** :
   - `worker-biometrics` lit les photos dans `kyc` et calcule les scores via `_shared/biometrics.ts`.
   - L'app Android n'envoie jamais `face_match_score`, `liveness_passed`, `ocr_data` ni `ocr_confidence`.

### Points à confirmer / compléter côté serveur
1. **Valeurs exactes des énumérations / contraintes CHECK SQL** (les fichiers `supabase/migrations/*.sql` étant hors de ce dépôt Android) :
   - `kyc.submit.id_document_type` : l'app envoie `national_id`, `voter_card`, `passport`, `driver_license`. À confirmer face au CHECK / enum PostgreSQL réel.
   - `emergency_contact.add.relationship` : l'app envoie `parent`, `spouse`, `sibling`, `friend`, `colleague`, `other`. À confirmer face au schéma SQL réel.
   - `consent.record.consent_type` et `text_version` : l'app envoie `kyc_biometrics`, `credit_terms`, `device_lock_mdm` avec `text_version = "v1.0"`. À confirmer face aux règles SQL et textes légaux validés.
2. **Fournisseur biométrique réel (`BIOMETRICS_MODE=live`)** :
   - Actuellement en mode `dry-run` (ou `simulate_pass` en dev). Brancher le fournisseur réel (AWS Rekognition, SmileID ou équivalent) dans `_shared/biometrics.ts` avant la mise en production.
3. **Codes d'erreur structurés (`reason`)** :
   - Retourner des codes machine stables (en plus des messages textuels) dans `process_sync_action` et les RPCs pour faciliter l'affichage localisé et la correction hors ligne sans dépendre de regex sur le texte d'erreur.

## Étapes ultérieures (à suivre lors des jalons 3b / 3c)
- **Étape 2 (Livraison 3b)** : bucket `handover/<delivery_id>/<unique>.jpg` INSERT-only (5 Mo), `MDM_MODE=live` (Knox Guard / ABM / Android Enterprise) dans `_shared/mdm.ts`, et charge utile QR d'enrôlement MDM si requise par le fournisseur.
- **Étape 3+ (Sécurité & Client)** : `client-auth` OTP pour l'app Client et notifications push FCM pour l'agent (`worker-biometrics` / révision superviseur).
