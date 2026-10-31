# One merchant, no tenants

The ledger serves one merchant with one API key. Tenant isolation would add a column and a foreign key qualifier to every table and a class of tests to every feature, without showing anything about double-entry accounting or failure recovery, which is what this project exists to demonstrate.

Adding tenants later means migrating every table and query. That cost is accepted, because this is a reference implementation with no existing data to move.
