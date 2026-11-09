# Payment Ledger Lab

A Java payment backend that posts a double-entry ledger, recovers from ambiguous provider responses, and compares its records with settlement reports.

**Status: work in progress. `POST /v1/payments` accepts payments, but they stay `PENDING`: nothing sends them to the provider or posts their journals yet.**

This is a reference implementation using synthetic data and a mock provider. It does not process real money and is not production banking infrastructure.

- [Accounting scope and invariants](docs/accounting.md)
- [Glossary](GLOSSARY.md)
- [Decisions](docs/adr/)
- [Dependencies](docs/dependencies.md)

## Proven so far

- Retrying `POST /v1/payments` with the same `Idempotency-Key` returns the original response and creates no second payment or provider operation, including under 100 concurrent requests. Reusing the key for a different request returns `409`. ([tests](src/test/java/io/github/aryodh/ledger/PaymentAcceptanceIT.java))

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

## License

Licensed under the [Apache License 2.0](LICENSE).
