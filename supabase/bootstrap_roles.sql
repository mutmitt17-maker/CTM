-- Comptes de test pour essayer TOUS les écrans du site (à lancer après bootstrap_users.sql).
--
-- ÉTAPE 1 : dans le tableau de bord Supabase, Authentication > Users > Add user, créez 4 utilisateurs
--   (e-mail + mot de passe, cochez « Auto confirm ») : un superviseur d'agence, un analyste risque,
--   un agent de recouvrement et un agent financier.
-- ÉTAPE 2 : remplacez les 4 UUID ci-dessous par les identifiants affichés, puis lancez ce fichier une seule fois.
--
-- Qui peut faire quoi sur le site :
--   system_admin       : politique de crédit, numéros marchands, politique des appareils, stock
--   finance            : espèces à confirmer, paiement manuel, validations
--   agency_supervisor  : dossiers à décider (son agence), SIM à valider, restructuration, remise de pénalités
--   risk_analyst       : dossiers à décider, déverrouiller un appareil / lever un blocage, SIM, validations
--   collections        : verrouiller un appareil, recouvrement (assigner / clôturer), remise de pénalités
--
-- ATTENTION : mfa_method 'totp' n'est qu'un repère pour que le compte soit actif pendant les essais.
-- Une vraie double authentification doit être mise en place avant toute utilisation réelle.

insert into app_user (id, role, status, mfa_method, agency_id)
select '00000000-0000-0000-0000-000000000003', 'agency_supervisor', 'active', 'totp', id
from agency where name = 'Agence Test';

insert into app_user (id, role, status, mfa_method)
values
  ('00000000-0000-0000-0000-000000000004', 'risk_analyst', 'active', 'totp'),
  ('00000000-0000-0000-0000-000000000005', 'collections',  'active', 'totp'),
  ('00000000-0000-0000-0000-000000000006', 'finance',      'active', 'totp');
