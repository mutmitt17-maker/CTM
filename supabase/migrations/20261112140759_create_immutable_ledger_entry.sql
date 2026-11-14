/*
# Immutable financial ledger (ledger_entry)

Plain English: adds the accounting journal where every money movement is recorded. Rows can be added but never changed or deleted. Corrections are made by adding a reversing entry. The running balance for each account is calculated by the database, not supplied by the caller.

1. New enum types
- ledger_entry_type: DEBIT, CREDIT
- ledger_account_category: PRINCIPAL_RECEIVABLE, INTEREST_INCOME, FEE_INCOME, PENALTY_INCOME, CUSTOMER_CASH, MOBILE_MONEY_CLEARING

2. New table
- ledger_entry: id, optional contract and payment links, entry type, account category, positive amount, balance after the entry, description, creation time.

3. Balance rule
- For each (contract, account category) pair, balance_after = previous balance + amount for DEBIT, or - amount for CREDIT.
- balance_after is overwritten by the database on insert, so callers cannot set it.
- A transaction-level lock serialises concurrent entries for the same account so balances cannot diverge.

4. Immutability
- Triggers reject every UPDATE, DELETE and TRUNCATE on ledger_entry with an error.

5. Security
- Row level security is enabled. No policies are created for anon or authenticated, and their privileges are revoked.
- service_role may only SELECT and INSERT. It has no UPDATE or DELETE grant.
*/

DO $$ BEGIN
  CREATE TYPE ledger_entry_type AS ENUM ('DEBIT', 'CREDIT');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE ledger_account_category AS ENUM (
    'PRINCIPAL_RECEIVABLE',
    'INTEREST_INCOME',
    'FEE_INCOME',
    'PENALTY_INCOME',
    'CUSTOMER_CASH',
    'MOBILE_MONEY_CLEARING'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS ledger_entry (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid REFERENCES loan_contract (id),
  payment_id uuid REFERENCES payment (id),
  entry_type ledger_entry_type NOT NULL,
  account_category ledger_account_category NOT NULL,
  amount numeric(14,2) NOT NULL CHECK (amount > 0),
  balance_after numeric(14,2) NOT NULL DEFAULT 0,
  description text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ledger_entry_contract_account_idx ON ledger_entry (contract_id, account_category);
CREATE INDEX IF NOT EXISTS ledger_entry_payment_id_idx ON ledger_entry (payment_id);

CREATE OR REPLACE FUNCTION public.ledger_entry_compute_balance()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  previous_balance numeric(14,2);
  signed_amount numeric(14,2);
BEGIN
  PERFORM pg_advisory_xact_lock(
    hashtextextended(coalesce(NEW.contract_id::text, 'none') || ':' || NEW.account_category::text, 0)
  );

  SELECT coalesce(sum(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END), 0)
    INTO previous_balance
    FROM public.ledger_entry AS le
    WHERE le.contract_id IS NOT DISTINCT FROM NEW.contract_id
      AND le.account_category = NEW.account_category;

  signed_amount := CASE WHEN NEW.entry_type = 'DEBIT' THEN NEW.amount ELSE -NEW.amount END;
  NEW.balance_after := previous_balance + signed_amount;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.ledger_entry_block_change()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entry is append-only: % is not allowed; post a reversing entry instead', TG_OP;
END;
$$;

DROP TRIGGER IF EXISTS ledger_entry_compute_balance_trg ON ledger_entry;
CREATE TRIGGER ledger_entry_compute_balance_trg BEFORE INSERT ON ledger_entry
  FOR EACH ROW EXECUTE FUNCTION public.ledger_entry_compute_balance();

DROP TRIGGER IF EXISTS ledger_entry_block_update_delete_trg ON ledger_entry;
CREATE TRIGGER ledger_entry_block_update_delete_trg BEFORE UPDATE OR DELETE ON ledger_entry
  FOR EACH ROW EXECUTE FUNCTION public.ledger_entry_block_change();

DROP TRIGGER IF EXISTS ledger_entry_block_truncate_trg ON ledger_entry;
CREATE TRIGGER ledger_entry_block_truncate_trg BEFORE TRUNCATE ON ledger_entry
  FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_entry_block_change();

ALTER TABLE ledger_entry ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON ledger_entry FROM anon, authenticated;
GRANT SELECT, INSERT ON ledger_entry TO service_role;

REVOKE EXECUTE ON FUNCTION public.ledger_entry_compute_balance() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.ledger_entry_block_change() FROM PUBLIC, anon, authenticated;
