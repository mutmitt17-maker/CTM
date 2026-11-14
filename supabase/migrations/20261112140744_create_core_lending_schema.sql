/*
# Core lending schema (customers, inventory, loans, repayments, payments)

Plain English: creates the six core business tables for the pay-as-you-go phone credit platform, with the status lists, money rules and integrity checks described in the project brief.

1. New enum types
- customer_status: active, suspended, blacklisted
- inventory_status: in_stock, assigned, sold, locked, stolen
- installment_frequency: daily, weekly, monthly
- loan_status: pending, approved, rejected, active, completed, defaulted
- contract_status: active, closed, breached
- repayment_status: pending, partial, paid, overdue
- payment_method: mpesa, airtel_money, orange_money, cash
- payment_status: success, failed, pending

2. New tables
- customer: client identity and contact details. phone_number is unique.
- inventory_item: each phone, identified by its unique 15-digit IMEI.
- loan_application: a request to finance one phone for one customer. Amounts must add up (financed = total - down payment).
- loan_contract: a validated contract. Versioned per application so any restructuring creates a new version instead of overwriting history.
- repayment_schedule: one row per installment of a contract.
- payment: money received from a customer. external_transaction_id is unique so a repeated webhook cannot create a duplicate payment.

3. Security
- Row level security is enabled on every table.
- All default access for the browser roles (anon, authenticated) is revoked. No policies are created for them, so the browser cannot read or write these tables.
- Only the server role (service_role, used by backend functions) receives privileges.

4. Important notes
- 4.1 Money columns use NUMERIC(14,2) to avoid rounding errors. Interest rate uses NUMERIC(7,4).
- 4.2 A phone can sit on only one approved or active loan at a time (partial unique index on loan_application).
- 4.3 Foreign keys link every child row to its parent. Indexes cover the common lookups.
- 4.4 updated_at is refreshed automatically on update for mutable tables.
*/

DO $$ BEGIN
  CREATE TYPE customer_status AS ENUM ('active', 'suspended', 'blacklisted');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE inventory_status AS ENUM ('in_stock', 'assigned', 'sold', 'locked', 'stolen');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE installment_frequency AS ENUM ('daily', 'weekly', 'monthly');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE loan_status AS ENUM ('pending', 'approved', 'rejected', 'active', 'completed', 'defaulted');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE contract_status AS ENUM ('active', 'closed', 'breached');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE repayment_status AS ENUM ('pending', 'partial', 'paid', 'overdue');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE payment_method AS ENUM ('mpesa', 'airtel_money', 'orange_money', 'cash');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE payment_status AS ENUM ('success', 'failed', 'pending');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS customer (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_number varchar(20) NOT NULL UNIQUE,
  first_name varchar(100) NOT NULL,
  last_name varchar(100) NOT NULL,
  national_id_number varchar(50) NOT NULL,
  address varchar(255),
  city varchar(100),
  status customer_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS customer_national_id_number_idx ON customer (national_id_number);

CREATE TABLE IF NOT EXISTS inventory_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  imei varchar(15) NOT NULL UNIQUE CHECK (imei ~ '^[0-9]{15}$'),
  model varchar(100) NOT NULL,
  brand varchar(100) NOT NULL,
  color varchar(50),
  purchase_price numeric(14,2) NOT NULL CHECK (purchase_price >= 0),
  retail_price numeric(14,2) NOT NULL CHECK (retail_price >= 0),
  status inventory_status NOT NULL DEFAULT 'in_stock',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS loan_application (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer (id),
  inventory_item_id uuid NOT NULL REFERENCES inventory_item (id),
  total_price numeric(14,2) NOT NULL CHECK (total_price > 0),
  down_payment numeric(14,2) NOT NULL DEFAULT 0 CHECK (down_payment >= 0),
  financed_amount numeric(14,2) NOT NULL CHECK (financed_amount >= 0),
  duration_days integer NOT NULL CHECK (duration_days > 0),
  installment_frequency installment_frequency NOT NULL,
  status loan_status NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT loan_application_down_payment_within_total CHECK (down_payment <= total_price),
  CONSTRAINT loan_application_financed_amount_matches CHECK (financed_amount = total_price - down_payment)
);

CREATE INDEX IF NOT EXISTS loan_application_customer_id_idx ON loan_application (customer_id);
CREATE INDEX IF NOT EXISTS loan_application_inventory_item_id_idx ON loan_application (inventory_item_id);

CREATE UNIQUE INDEX IF NOT EXISTS loan_application_one_live_loan_per_device_idx
  ON loan_application (inventory_item_id)
  WHERE status IN ('approved', 'active');

CREATE TABLE IF NOT EXISTS loan_contract (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  loan_application_id uuid NOT NULL REFERENCES loan_application (id),
  version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
  contract_number varchar(50) NOT NULL UNIQUE,
  start_date date NOT NULL,
  end_date date NOT NULL,
  interest_rate numeric(7,4) NOT NULL CHECK (interest_rate >= 0),
  total_payable numeric(14,2) NOT NULL CHECK (total_payable >= 0),
  status contract_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT loan_contract_end_after_start CHECK (end_date >= start_date),
  CONSTRAINT loan_contract_version_per_application UNIQUE (loan_application_id, version)
);

CREATE TABLE IF NOT EXISTS repayment_schedule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  installment_number integer NOT NULL CHECK (installment_number > 0),
  due_date date NOT NULL,
  amount_due numeric(14,2) NOT NULL CHECK (amount_due >= 0),
  amount_paid numeric(14,2) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0),
  status repayment_status NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT repayment_schedule_installment_unique UNIQUE (contract_id, installment_number)
);

CREATE INDEX IF NOT EXISTS repayment_schedule_due_date_idx ON repayment_schedule (due_date);

CREATE TABLE IF NOT EXISTS payment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  external_transaction_id varchar(100) NOT NULL UNIQUE,
  amount numeric(14,2) NOT NULL CHECK (amount > 0),
  payment_method payment_method NOT NULL,
  status payment_status NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS payment_contract_id_idx ON payment (contract_id);

DROP TRIGGER IF EXISTS customer_set_updated_at ON customer;
CREATE TRIGGER customer_set_updated_at BEFORE UPDATE ON customer
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS inventory_item_set_updated_at ON inventory_item;
CREATE TRIGGER inventory_item_set_updated_at BEFORE UPDATE ON inventory_item
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS loan_application_set_updated_at ON loan_application;
CREATE TRIGGER loan_application_set_updated_at BEFORE UPDATE ON loan_application
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS loan_contract_set_updated_at ON loan_contract;
CREATE TRIGGER loan_contract_set_updated_at BEFORE UPDATE ON loan_contract
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS repayment_schedule_set_updated_at ON repayment_schedule;
CREATE TRIGGER repayment_schedule_set_updated_at BEFORE UPDATE ON repayment_schedule
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_contract ENABLE ROW LEVEL SECURITY;
ALTER TABLE repayment_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON customer, inventory_item, loan_application, loan_contract, repayment_schedule, payment FROM anon, authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE ON customer, inventory_item, loan_application, loan_contract, repayment_schedule, payment TO service_role;

REVOKE EXECUTE ON FUNCTION public.set_updated_at() FROM PUBLIC, anon, authenticated;
