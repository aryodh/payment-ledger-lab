CREATE TABLE ledger.payments (
	id uuid PRIMARY KEY,
	merchant_reference text NOT NULL UNIQUE CHECK (length(merchant_reference) BETWEEN 1 AND 200),
	amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
	fee_minor bigint NOT NULL CHECK (fee_minor BETWEEN 0 AND amount_minor),
	currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
	status text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
	created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger.provider_operations (
	id uuid PRIMARY KEY,
	operation_type text NOT NULL CHECK (operation_type = 'CAPTURE'),
	payment_id uuid NOT NULL REFERENCES ledger.payments (id),
	provider_idempotency_key text NOT NULL UNIQUE,
	state text NOT NULL CHECK (state IN ('QUEUED', 'IN_FLIGHT', 'CONFIRMED', 'DECLINED', 'UNKNOWN', 'NEEDS_REVIEW')),
	created_at timestamptz NOT NULL DEFAULT now()
);

-- Kept for the life of the database, so a retried request can never create a second payment.
CREATE TABLE ledger.idempotency_records (
	scope text NOT NULL,
	key text NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
	canonical_request_hash text NOT NULL,
	response_status integer NOT NULL,
	-- json, not jsonb, so a replay returns the original bytes.
	response_body json NOT NULL,
	resource_id uuid NOT NULL,
	created_at timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (scope, key)
);

CREATE TABLE ledger.audit_log (
	id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	actor text NOT NULL,
	action text NOT NULL,
	resource text NOT NULL,
	reason text,
	before_status text,
	after_status text,
	request_id text NOT NULL,
	created_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON ledger.payments, ledger.provider_operations, ledger.idempotency_records, ledger.audit_log
	TO ledger_app;
