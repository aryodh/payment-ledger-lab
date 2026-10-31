# Dependencies

Chosen on 2026-11-01. Dependabot proposes updates to the Maven, GitHub Actions, and Compose versions.

| Component | Version | Note |
| --- | --- | --- |
| Java | 25 | Build and CI use Temurin 25 |
| Spring Boot | 4.1.2 | Latest stable on Maven Central. It manages the versions below |
| PostgreSQL JDBC driver | 42.7.14 | Managed by Spring Boot |
| Flyway library | 12.4.0 | Managed by Spring Boot, test scope only |
| Testcontainers | 2.0.5 | Managed by Spring Boot |
| Maven | 3.10.0 | Pinned by the wrapper |
| PostgreSQL image | 18.6-alpine | Pinned by digest in `compose.yaml`. Tests read the image from that file |
| Flyway CLI image | 12.4.0-alpine | Pinned by digest in `compose.yaml`. Keep it on the same version as the library |
| GitHub Actions | `checkout` v7.0.1, `setup-java` v6.1.0 | Pinned by commit SHA |

The application runs as `ledger_app` and has no Flyway dependency at runtime. Migrations run in a separate step as `ledger_migrator`, so the application process never holds a credential that can change the schema.
