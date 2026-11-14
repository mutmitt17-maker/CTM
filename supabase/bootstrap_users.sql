-- STEP 1: in the Supabase dashboard, Authentication > Users > Add user, create these users
--   (e-mail + password, tick "Auto confirm"): an administrator and a field agent.
-- STEP 2: replace the two UUIDs below by the ids shown in the dashboard, then run this file once.
--
-- WARNING: mfa_method 'totp' is only a placeholder so the account can be marked active for the first tests.
-- Real authenticator enrolment (TOTP / passkey) must be set up before any production use.

insert into agency (name, region) values ('Agence Test', 'Lubumbashi') on conflict (name) do nothing;

insert into app_user (id, role, status, mfa_method)
values ('00000000-0000-0000-0000-000000000001', 'system_admin', 'active', 'totp');

insert into app_user (id, role, status, mfa_method, agency_id)
select '00000000-0000-0000-0000-000000000002', 'field_agent', 'active', 'totp', id
from agency where name = 'Agence Test';
