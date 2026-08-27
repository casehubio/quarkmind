-- Patch: add tenancy_id to message_ledger_entry before V2004 creates an index on it.
-- Missing from qhorus consolidated V1 — upstream fix pending.
ALTER TABLE message_ledger_entry ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(255);
