# Manques du serveur et contrats supposés

Chaque point est codé côté Android derrière une interface avec une implémentation factice.
Les routes et formats ci-dessous sont des **propositions** à valider avant d'écrire le serveur.

## 1. Stockage des photos
- Supabase Storage, bucket privé `kyc` (pièces, selfies) et `handover` (preuves de remise), règles d'accès par agence.
- Chemin proposé : `kyc/{customer_id}/{uuid}.jpg`. L'application envoie le fichier puis met le chemin dans l'action (`id_document_photo_path`, `selfie_path`, `p_photo_path`).
- Les fichiers sont supprimés du téléphone dès l'envoi confirmé.

## 2. Connexion du client par OTP
- `POST /functions/v1/client-auth` avec `{action: "send_otp", phone}` puis `{action: "verify_otp", phone, code}`.
- Réponse de `verify_otp` : session Supabase (jeton d'accès et de rafraîchissement).
- Les tables et le blocage à 5 essais existent déjà en base ; la fonction manque.

## 3. Enrôlement MDM
- Aujourd'hui `request_mdm_enrollment` ne renvoie rien. Il faut une action qui renvoie le contenu du QR code Android Enterprise : `{enrollment_qr: "<json ou jeton>", expires_at}` pour une livraison donnée.

## 4. Contrat et certificat en PDF
- `GET`/action `contract_document` : `{url, expires_at}` d'une URL signée de courte durée. Idem `ownership_certificate` après solde.

## 5. Authentification de l'appareil client
- Enregistrement : `{action: "register_device", device_public_key, fingerprint}` -> identifiant d'appareil.
- Rapports d'état, réponse aux attestations, réception des commandes (`apply_policy`, `lock`, `unlock`, `release`, `request_attestation`) par notification push (FCM) avec reprise par interrogation.

## 6. Connexions réelles Knox Guard, Apple Business Manager, MDM Android
- Aujourd'hui en simulation (`dry-run`) : les ordres sont seulement journalisés.

## 7. Vérification biométrique côté serveur
- Le moteur de décision ignore la vivacité et la correspondance envoyées par le téléphone. Il attend `record_biometric_verification()`, aujourd'hui réservé au serveur et non exposé, et qui dépend du point 1.
- Conséquence : tout `application.submit` répond `awaiting_biometric_verification` et aucun dossier ne devient `approved` en production. Les tests de bout en bout doivent simuler cette étape.

## 8. Codes d'erreur stables
- Hors `sync_batch`, `error` est un message anglais libre. Messages rencontrés dans le code :
  `delivery not available for this agent`, `scan and verify the IMEI first`, `proof already recorded`,
  `handover photo and signature are both required`, `device already enrolled`, `IMEI not verified`,
  `handover photo and signature are missing`, `MDM enrolment is not confirmed by the server yet (status: …)`,
  `application is not in the approved state`, `only an approved application can be delivered`,
  `the person who approved the file cannot hand over the device`, `agent not allowed`.
- Proposition : ajouter un champ `reason` stable (`delivery_not_available`, `imei_not_verified`, `proof_already_recorded`, `proof_missing`, `mdm_already_enrolled`, `mdm_not_enrolled`, `application_not_approved`, `approver_cannot_deliver`, `agent_not_allowed`).
