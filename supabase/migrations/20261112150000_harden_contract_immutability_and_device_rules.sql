/*
# Hardening: contract immutability, device exclusivity, payment protection

Plain English: closes the gaps found when checking the schema against the brief. Contract terms and schedules can no longer be rewritten or deleted, a phone can only be on one live contract, and raw payments can no longer be altered or removed.

1. Contract immutability (brief rule 3)
- loan_contract: contractual terms (application, version, number, dates, rate, total payable) are frozen. Only status may change, and only from 'active' to 'closed' or 'breached'. DELETE and TRUNCATE are rejected.
- repayment_schedule: due_date, amount_due, installment_number and contract are frozen. Only amount_paid and status may change. DELETE and TRUNCATE are rejected.
- A restructuring is a NEW loan_contract row (version + 1) with its own schedule. The previous version must be closed first.
- One active contract per application (partial unique index).

2. Device exclusivity (brief rule 1)
- A contract can only be created for an application that is 'approved' or 'active'.
- A contract cannot be created if the same phone is already on an active contract of another application.
- An application can only be approved or activated if the phone is 'in_stock' or already 'assigned'.
- inventory_item.status follows the loan: approved/active -> assigned, completed -> sold, rejected -> back to in_stock (if no other live loan). 'defaulted' leaves the status alone so a later lock/stolen decision stays explicit.

3. Payment protection
- payment: amount, contract, method, external_transaction_id and created_at are frozen. Status can only move from 'pending' to 'success' or 'failed'. DELETE and TRUNCATE are rejected.

4. Security
- DELETE privilege removed from service_role on loan_contract, repayment_schedule and payment.
- New functions are not executable by PUBLIC, anon or authenticated.
*/

-- 1a. One active contract per application
CREATE UNIQUE INDEX IF NOT EXISTS loan_contract_one_active_per_application_idx
  ON loan_contract (loan_application_id)
  WHERE status = 'active';

-- 1b. loan_contract guards
CREATE OR REPLACE FUNCTION public.loan_contract_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.loan_application_id IS DISTINCT FROM OLD.loan_application_id
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.contract_number IS DISTINCT FROM OLD.contract_number
     OR NEW.start_date IS DISTINCT FROM OLD.start_date
     OR NEW.end_date IS DISTINCT FROM OLD.end_date
     OR NEW.interest_rate IS DISTINCT FROM OLD.interest_rate
     OR NEW.total_payable IS DISTINCT FROM OLD.total_payable
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'loan_contract terms are immutable: create a new contract version instead';
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'active' THEN
    RAISE EXCEPTION 'loan_contract status % is final and cannot change to %', OLD.status, NEW.status;
  END IF;

  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.block_row_or_truncate()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  RAISE EXCEPTION '% on % is not allowed: history is preserved', TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE OR REPLACE FUNCTION public.loan_contract_guard_insert()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  app_status public.loan_status;
  app_item uuid;
BEGIN
  SELECT la.status, la.inventory_item_id
    INTO app_status, app_item
    FROM public.loan_application AS la
    WHERE la.id = NEW.loan_application_id
    FOR SHARE;

  IF app_status IS NULL OR app_status NOT IN ('approved', 'active') THEN
    RAISE EXCEPTION 'a contract requires an approved or active loan application (found: %)', coalesce(app_status::text, 'none');
  END IF;

  IF NEW.status = 'active' AND EXISTS (
    SELECT 1
      FROM public.loan_contract AS lc
      JOIN public.loan_application AS la2 ON la2.id = lc.loan_application_id
      WHERE lc.status = 'active'
        AND la2.inventory_item_id = app_item
        AND lc.loan_application_id <> NEW.loan_application_id
  ) THEN
    RAISE EXCEPTION 'this device is already attached to another active contract';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS loan_contract_guard_insert_trg ON loan_contract;
CREATE TRIGGER loan_contract_guard_insert_trg BEFORE INSERT ON loan_contract
  FOR EACH ROW EXECUTE FUNCTION public.loan_contract_guard_insert();

DROP TRIGGER IF EXISTS loan_contract_guard_update_trg ON loan_contract;
CREATE TRIGGER loan_contract_guard_update_trg BEFORE UPDATE ON loan_contract
  FOR EACH ROW EXECUTE FUNCTION public.loan_contract_guard_update();

DROP TRIGGER IF EXISTS loan_contract_block_delete_trg ON loan_contract;
CREATE TRIGGER loan_contract_block_delete_trg BEFORE DELETE ON loan_contract
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS loan_contract_block_truncate_trg ON loan_contract;
CREATE TRIGGER loan_contract_block_truncate_trg BEFORE TRUNCATE ON loan_contract
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

-- 1c. repayment_schedule guards
CREATE OR REPLACE FUNCTION public.repayment_schedule_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.installment_number IS DISTINCT FROM OLD.installment_number
     OR NEW.due_date IS DISTINCT FROM OLD.due_date
     OR NEW.amount_due IS DISTINCT FROM OLD.amount_due
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'repayment_schedule terms are immutable: create a new contract version with a new schedule instead';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS repayment_schedule_guard_update_trg ON repayment_schedule;
CREATE TRIGGER repayment_schedule_guard_update_trg BEFORE UPDATE ON repayment_schedule
  FOR EACH ROW EXECUTE FUNCTION public.repayment_schedule_guard_update();

DROP TRIGGER IF EXISTS repayment_schedule_block_delete_trg ON repayment_schedule;
CREATE TRIGGER repayment_schedule_block_delete_trg BEFORE DELETE ON repayment_schedule
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS repayment_schedule_block_truncate_trg ON repayment_schedule;
CREATE TRIGGER repayment_schedule_block_truncate_trg BEFORE TRUNCATE ON repayment_schedule
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

-- 3. payment protection
CREATE OR REPLACE FUNCTION public.payment_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.external_transaction_id IS DISTINCT FROM OLD.external_transaction_id
     OR NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'payment details are immutable: record a reversal instead';
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'pending' THEN
    RAISE EXCEPTION 'payment status % is final and cannot change to %', OLD.status, NEW.status;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS payment_guard_update_trg ON payment;
CREATE TRIGGER payment_guard_update_trg BEFORE UPDATE ON payment
  FOR EACH ROW EXECUTE FUNCTION public.payment_guard_update();

DROP TRIGGER IF EXISTS payment_block_delete_trg ON payment;
CREATE TRIGGER payment_block_delete_trg BEFORE DELETE ON payment
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS payment_block_truncate_trg ON payment;
CREATE TRIGGER payment_block_truncate_trg BEFORE TRUNCATE ON payment
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

-- 2. Device status rules and sync on loan_application
CREATE OR REPLACE FUNCTION public.loan_application_check_device()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  item_status public.inventory_status;
BEGIN
  IF NEW.status IN ('approved', 'active')
     AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status
          OR OLD.inventory_item_id IS DISTINCT FROM NEW.inventory_item_id) THEN
    SELECT ii.status INTO item_status
      FROM public.inventory_item AS ii
      WHERE ii.id = NEW.inventory_item_id
      FOR UPDATE;

    IF item_status IS NULL OR item_status NOT IN ('in_stock', 'assigned') THEN
      RAISE EXCEPTION 'device cannot be approved or activated: its status is %', coalesce(item_status::text, 'unknown');
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.loan_application_sync_device()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.status IN ('approved', 'active') THEN
    UPDATE public.inventory_item
      SET status = 'assigned'
      WHERE id = NEW.inventory_item_id AND status = 'in_stock';
  ELSIF NEW.status = 'completed' THEN
    UPDATE public.inventory_item
      SET status = 'sold'
      WHERE id = NEW.inventory_item_id AND status IN ('in_stock', 'assigned');
  ELSIF NEW.status = 'rejected' THEN
    UPDATE public.inventory_item AS ii
      SET status = 'in_stock'
      WHERE ii.id = NEW.inventory_item_id
        AND ii.status = 'assigned'
        AND NOT EXISTS (
          SELECT 1 FROM public.loan_application AS la
          WHERE la.inventory_item_id = NEW.inventory_item_id
            AND la.id <> NEW.id
            AND la.status IN ('approved', 'active')
        );
  END IF;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS loan_application_check_device_trg ON loan_application;
CREATE TRIGGER loan_application_check_device_trg BEFORE INSERT OR UPDATE ON loan_application
  FOR EACH ROW EXECUTE FUNCTION public.loan_application_check_device();

DROP TRIGGER IF EXISTS loan_application_sync_device_trg ON loan_application;
CREATE TRIGGER loan_application_sync_device_trg AFTER INSERT OR UPDATE OF status ON loan_application
  FOR EACH ROW EXECUTE FUNCTION public.loan_application_sync_device();

-- 4. Security
REVOKE DELETE, TRUNCATE ON loan_contract, repayment_schedule, payment FROM service_role;

REVOKE EXECUTE ON FUNCTION public.loan_contract_guard_update() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.loan_contract_guard_insert() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.block_row_or_truncate() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.repayment_schedule_guard_update() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.payment_guard_update() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.loan_application_check_device() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.loan_application_sync_device() FROM PUBLIC, anon, authenticated;
