# Payment Ledger Lab

A Java payment backend that posts a double-entry ledger, recovers from ambiguous provider responses, and compares its records with settlement reports.

**Status: work in progress. The ledger's posting function exists, but there is no API or payment flow yet.**

This is a reference implementation using synthetic data and a mock provider. It does not process real money and is not production banking infrastructure.

- [Accounting scope and invariants](docs/accounting.md)
- [Glossary](GLOSSARY.md)
- [Decisions](docs/adr/)
- [Dependencies](docs/dependencies.md)

## Development

Needs JDK 25 and Docker.

```bash
./mvnw verify
```

To run the database locally:

```bash
cp .env.example .env
docker compose up migrate
```

If port 5432 is taken, set `LEDGER_DB_PORT` in `.env`.
