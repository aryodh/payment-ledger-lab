-- Flyway creates the ledger schema, owned by ledger_migrator. Later migrations grant
-- table privileges one by one, so the application role has none by default.
GRANT USAGE ON SCHEMA ledger TO ledger_app;
