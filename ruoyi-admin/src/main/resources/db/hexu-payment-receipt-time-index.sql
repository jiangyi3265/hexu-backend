-- Apply once to an existing database before deploying the order payment-time read model.
CREATE INDEX hx_ledger_receipt_lookup ON hx_ledger(reference_id, shop_id, category, id);
