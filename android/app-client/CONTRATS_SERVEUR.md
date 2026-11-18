# app-client : contrat avec le serveur

Le module `:app-client` est en Kotlin pur (comme `:app-agent`) : des ViewModels testés avec un faux `ClientApi`.
L'implémentation réelle (HTTP) viendra avec le module Android. Voici ce qu'elle devra appeler.

## Ce qui existe déjà sur le serveur

Le client connecté (rôle `client`, lié à un `customer_id`) ne voit que ses propres lignes, grâce à la sécurité par lignes.
Lectures avec le jeton du client (PostgREST) :

| Méthode de `ClientApi` | Appel |
|---|---|
| `myContracts()` | `GET /rest/v1/loan_contract?select=id,contract_number,status,start_date,end_date,total_payable` |
| `schedule(id)` | `GET /rest/v1/repayment_schedule?contract_id=eq.<id>&select=installment_number,due_date,amount_due,amount_paid,status&order=installment_number` |
| `payments(id)` | `GET /rest/v1/payment?contract_id=eq.<id>&select=id,amount,payment_method,status,created_at&order=created_at.desc` |
| `merchantAccounts()` | `GET /rest/v1/merchant_account?select=provider,merchant_number,display_name,active` |
| `contractStatus(id)` | `POST /functions/v1/api` avec `{"action":"contract_status","params":{"p_contract":"<id>"}}` |

Réponse de `contract_status` : `contract_status`, `device_state`, `status_color`, `lock_reason`, `overdue_days`,
`penalty_outstanding_usd`, `total_outstanding_usd`.

Les montants (`numeric`) doivent être lus comme `BigDecimal` à partir du texte JSON, jamais comme `Double`.
Valeurs possibles : `loan_contract.status` = active / closed / breached ; `repayment_schedule.status` = pending / partial / paid / overdue ;
`payment.payment_method` = mpesa / airtel_money / orange_money / cash ; `payment.status` = success / failed / pending.

## Ce qui MANQUE sur le serveur

1. **Connexion par OTP** (`BACKEND_GAPS.md`, point 2) : la fonction `client-auth` n'existe pas.
   - `{action:"send_otp", phone}` puis `{action:"verify_otp", phone, code}` ; la réponse de `verify_otp` est la session Supabase.
   - À préciser avant d'écrire la fonction : qui crée le compte `app_user` (rôle `client`, `customer_id`) lié au numéro de téléphone du client ?
   - La longueur du code n'est pas fixée ; l'application accepte 4 à 8 chiffres.
2. **Lancer un paiement depuis l'application** : aucune action ne le permet. Aujourd'hui le client paie depuis son téléphone vers le
   numéro marchand, avec le **numéro de contrat comme référence** ; le paiement est enregistré par le webhook de l'opérateur
   (`webhook-mobile-money`, phase 2, à adapter à l'agrégateur choisi) ou par le back-office (`record_manual_mobile_money_payment`).
   L'application affiche donc des instructions (numéro marchand, référence, montant suggéré) et propose d'actualiser.
3. **Numéros marchands vides** : la table `merchant_account` est vide tant que l'administrateur ne l'a pas remplie
   (action `set_merchant_account`). Le back-office web n'a pas encore d'écran pour le faire : sans cela, l'application client affichera
   « paiement Mobile Money indisponible ».

## Montant suggéré

`échéances échues et non soldées + pénalités`, sinon le reste de la prochaine échéance. C'est une suggestion que le client peut modifier ;
le serveur décide de l'affectation du paiement.
