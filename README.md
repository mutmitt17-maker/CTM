# Crédit Téléphone : plateforme de vente à crédit de téléphones (USD)

Contenu du dépôt :

| Dossier | Rôle |
|---|---|
| `supabase/migrations/` | Base de données (étapes 1 à 6). À appliquer dans l'ordre des noms de fichiers. |
| `supabase/functions/` | Edge Functions : `api` (back-office et app agent), `webhook-mobile-money`, `worker-notifications`, `worker-device-commands`. |
| `supabase/ci/` | Outils de test automatique (jamais à lancer sur Supabase). |
| `supabase/seed_test_data.sql`, `supabase/bootstrap_users.sql` | Données et comptes de test, à lancer à la main. |
| `web/` | Back-office minimal pour Cloudflare Pages (aucune compilation). |
| `.github/workflows/` | Contrôles automatiques et déploiements. |

---

## 1. Mettre le projet sur GitHub

1. Sur github.com : **New repository**, nom `credit-telephone`, **Private**, sans README ni .gitignore.
2. Sur votre ordinateur, dans le dossier du projet (le contenu du zip) :
   ```bash
   git init
   git add .
   git commit -m "Etapes 1 a 6"
   git branch -M main
   git remote add origin https://github.com/VOTRE-COMPTE/credit-telephone.git
   git push -u origin main
   ```
3. Onglet **Actions** du dépôt : le workflow **CI** démarre tout seul. Il fait deux choses :
   - il applique les 10 migrations sur un PostgreSQL vierge, puis rejoue un scénario complet (inscription du client, dossier, livraison, paiements, clôture) ;
   - il vérifie le code TypeScript des Edge Functions.

   **C'est le premier vrai test.** Les migrations n'ont jamais été exécutées avant cela. S'il y a une erreur, copiez le message rouge de l'onglet Actions et envoyez-le moi : je corrige.

## 2. Créer le projet Supabase

1. supabase.com, **New project** (région la plus proche de vos utilisateurs). Notez le mot de passe de la base.
2. **Project Settings > API** : notez l'URL du projet, la clé `anon` et la clé `service_role` (celle-ci ne doit jamais apparaître dans le code ni dans `web/`).
3. Appliquer les migrations, au choix :
   - Dans le dépôt GitHub : **Settings > Secrets and variables > Actions**, ajoutez `SUPABASE_ACCESS_TOKEN` (supabase.com/dashboard/account/tokens), `SUPABASE_PROJECT_REF` (la partie avant `.supabase.co`) et `SUPABASE_DB_PASSWORD`. Puis **Actions > Apply database migrations (manual) > Run workflow**.
   - Ou en local avec la CLI : `supabase link --project-ref REF` puis `supabase db push`.
4. Créer les comptes de test : dans **Authentication > Users > Add user**, créez un administrateur et un agent (e-mail + mot de passe, cocher « Auto confirm »). Ouvrez `supabase/bootstrap_users.sql`, remplacez les deux UUID par ceux affichés, puis exécutez-le dans **SQL Editor**.
5. Exécutez `supabase/seed_test_data.sql` dans le SQL Editor (une agence et 3 téléphones de test).

## 3. Déployer les Edge Functions

1. Secrets, dans **Project Settings > Edge Functions > Secrets** (ou `supabase secrets set NOM=valeur`) :

   | Secret | Valeur |
   |---|---|
   | `ALLOWED_ORIGIN` | L'adresse de votre site Cloudflare Pages (ex. `https://credit-telephone.pages.dev`) |
   | `WORKER_SECRET` | Une longue chaîne aléatoire que vous inventez |
   | `MOBILE_MONEY_WEBHOOK_SECRET` | Idem, pour la phase 2 |
   | `NOTIFY_MODE` | `dry-run` (par défaut : rien n'est envoyé) puis `live` |
   | `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID` | Seulement en mode `live` |
   | `SMS_API_URL`, `SMS_API_KEY` | Seulement en mode `live` |
   | `MDM_MODE` | `dry-run` (par défaut : **aucun téléphone réel n'est verrouillé**) |

2. Déployer : **Actions > Deploy Edge Functions > Run workflow** (il utilise les mêmes secrets GitHub qu'au point 2.3).

## 4. Déployer le back-office sur Cloudflare Pages

1. Ouvrez `web/config.js`, remplacez `YOUR-PROJECT-REF` et `YOUR-ANON-KEY` par vos valeurs (la clé `anon` est publique par conception). Commit et push.
2. dash.cloudflare.com > **Workers & Pages > Create > Pages > Connect to Git** : choisissez le dépôt.
3. Réglages de build : **Framework preset : None**, **Build command : (vide)**, **Build output directory : `web`**. Déployez.
4. Reprenez l'adresse obtenue (`xxx.pages.dev`) et mettez-la dans le secret `ALLOWED_ORIGIN` de Supabase (point 3.1).
5. Dans Supabase, **Authentication > URL Configuration** : ajoutez cette adresse comme Site URL.

## 5. Planifier les files d'attente

Les deux workers lisent les files (messages et commandes téléphones). Ils doivent être appelés chaque minute pour tenir l'objectif de déblocage sous 60 secondes. Dans le SQL Editor (en remplaçant REF et le secret) :

```sql
create extension if not exists pg_net;
select cron.schedule('worker-notifications', '* * * * *', $$
  select net.http_post(
    url := 'https://REF.supabase.co/functions/v1/worker-notifications',
    headers := '{"x-worker-secret": "VOTRE_WORKER_SECRET"}'::jsonb) $$);
select cron.schedule('worker-device-commands', '* * * * *', $$
  select net.http_post(
    url := 'https://REF.supabase.co/functions/v1/worker-device-commands',
    headers := '{"x-worker-secret": "VOTRE_WORKER_SECRET"}'::jsonb) $$);
```

Pour tester sans planification, appelez le worker à la main :
`curl -X POST https://REF.supabase.co/functions/v1/worker-notifications -H "x-worker-secret: VOTRE_WORKER_SECRET"`.

---

## Que tester, dans quel ordre

**Test 1, dès que le point 4 est fait**
- Connexion avec l'administrateur et avec l'agent.
- Accueil : le rôle s'affiche correctement.
- Politique de crédit : visible ; l'administrateur la modifie avec un motif (essayez un taux absurde : il doit être refusé).
- Simulateur (avec l'agent) : choisir un téléphone, un acompte (minimum 20 % du prix), voir l'échéancier calculé.
- Vérifier qu'un agent ne peut pas modifier la politique (message 403).

**Test 2, paiements**
- Il faut d'abord un contrat actif : le parcours complet (client, KYC, dossier, livraison) a besoin de l'application agent, qui n'existe pas encore. En attendant, le scénario `supabase/ci/smoke_test.sql` fait ce parcours en SQL ; vous pouvez en adapter une copie pour créer des données dans le projet de test.
- Ensuite : saisie manuelle d'un paiement Mobile Money, vérification du doublon, de la ventilation et du déblocage.

**Test 3, après écriture de l'application agent**
- Synchronisation hors ligne, livraison avec scan IMEI.

## Ce qui n'est pas prêt

- L'application Android agent et l'application cliente.
- Les connexions réelles à Knox Guard, Apple Business Manager et au MDM Android Enterprise (en `dry-run`, les ordres sont seulement journalisés).
- Les modèles de messages WhatsApp : à créer dans Meta (mêmes noms que dans `supabase/functions/_shared/messages.ts`, langue `fr`) et à faire approuver.
- Le rendu PDF des documents de clôture : le message est envoyé avec les numéros de documents, mais les PDF ne sont pas encore générés.
- Le format des webhooks de l'agrégateur Mobile Money : le fichier fourni est un modèle sûr à adapter une fois l'agrégateur choisi.
- L'authentification forte (TOTP / passkey) : les comptes de test utilisent une valeur provisoire.
