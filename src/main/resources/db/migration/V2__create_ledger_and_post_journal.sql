CREATE TABLE ledger.ledger_accounts (
	id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	code text NOT NULL,
	currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
	account_type text NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE')),
	normal_side text NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
	UNIQUE (code, currency)
);

INSERT INTO ledger.ledger_accounts (code, currency, account_type, normal_side) VALUES
	('PROVIDER_CLEARING', 'SGD', 'ASSET', 'DEBIT'),
	('MERCHANT_PAYABLE', 'SGD', 'LIABILITY', 'CREDIT'),
	('PLATFORM_FEE_REVENUE', 'SGD', 'REVENUE', 'CREDIT');

CREATE TABLE ledger.ledger_posting_counter (
	single_row boolean PRIMARY KEY DEFAULT true CHECK (single_row),
	value bigint NOT NULL CHECK (value >= 0)
);

INSERT INTO ledger.ledger_posting_counter (value) VALUES (0);

CREATE TABLE ledger.journals (
	id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	posting_seq bigint NOT NULL UNIQUE CHECK (posting_seq > 0),
	business_event_id text NOT NULL UNIQUE CHECK (length(business_event_id) BETWEEN 1 AND 200),
	journal_type text NOT NULL CHECK (journal_type IN ('CAPTURE', 'REFUND')),
	currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
	business_reference text NOT NULL CHECK (length(business_reference) BETWEEN 1 AND 200),
	posted_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger.ledger_entries (
	journal_id uuid NOT NULL REFERENCES ledger.journals (id),
	ordinal integer NOT NULL CHECK (ordinal > 0),
	account_id uuid NOT NULL REFERENCES ledger.ledger_accounts (id),
	side text NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
	amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
	PRIMARY KEY (journal_id, ordinal)
);

-- account_id is null when the journal's currency has no such account.
CREATE FUNCTION ledger.resolve_entries(p_entries jsonb, p_currency text)
RETURNS TABLE (ordinal integer, account_id uuid, side text, amount_minor bigint)
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
	SELECT e.ordinal::integer, a.id, e.side, e.amount_minor
	FROM ROWS FROM (jsonb_to_recordset(p_entries) AS (account text, side text, amount_minor bigint))
		WITH ORDINALITY AS e (account, side, amount_minor, ordinal)
	LEFT JOIN ledger.ledger_accounts a ON a.code = e.account AND a.currency = p_currency
$$;

CREATE FUNCTION ledger.post_journal(
	p_business_event_id text,
	p_journal_type text,
	p_currency text,
	p_business_reference text,
	p_entries jsonb
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
	v_entry_count bigint;
	v_debits numeric;
	v_credits numeric;
	v_existing ledger.journals;
	v_posting_seq bigint;
	v_journal_id uuid;
BEGIN
	SELECT count(*),
		coalesce(sum(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
		coalesce(sum(amount_minor) FILTER (WHERE side = 'CREDIT'), 0)
	INTO v_entry_count, v_debits, v_credits
	FROM ledger.resolve_entries(p_entries, p_currency);

	IF v_entry_count < 2 THEN
		RAISE EXCEPTION 'journal needs at least two entries';
	END IF;
	IF EXISTS (SELECT FROM ledger.resolve_entries(p_entries, p_currency) WHERE account_id IS NULL) THEN
		RAISE EXCEPTION 'entry references an account not in currency %', p_currency;
	END IF;
	IF EXISTS (SELECT FROM ledger.resolve_entries(p_entries, p_currency)
			WHERE side IS NULL OR side NOT IN ('DEBIT', 'CREDIT')) THEN
		RAISE EXCEPTION 'entry side must be DEBIT or CREDIT';
	END IF;
	IF EXISTS (SELECT FROM ledger.resolve_entries(p_entries, p_currency)
			WHERE amount_minor IS NULL OR amount_minor NOT BETWEEN 1 AND 100000000) THEN
		RAISE EXCEPTION 'entry amount must be between 1 and 100000000 minor units';
	END IF;
	IF v_debits <> v_credits THEN
		RAISE EXCEPTION 'journal is unbalanced: debits %, credits %', v_debits, v_credits;
	END IF;

	-- Lock before the duplicate check, so a concurrent duplicate sees the committed journal
	-- and returns without using a number. Every transaction takes this lock last.
	PERFORM FROM ledger.ledger_posting_counter FOR UPDATE;

	SELECT * INTO v_existing FROM ledger.journals WHERE business_event_id = p_business_event_id;
	IF FOUND THEN
		IF (v_existing.journal_type, v_existing.currency, v_existing.business_reference)
				IS DISTINCT FROM (p_journal_type, p_currency, p_business_reference)
			OR (SELECT jsonb_agg(jsonb_build_array(account_id, side, amount_minor) ORDER BY account_id, side, amount_minor)
				FROM ledger.ledger_entries WHERE journal_id = v_existing.id)
			<> (SELECT jsonb_agg(jsonb_build_array(account_id, side, amount_minor) ORDER BY account_id, side, amount_minor)
				FROM ledger.resolve_entries(p_entries, p_currency)) THEN
			RAISE EXCEPTION 'business event % already posted with different facts', p_business_event_id;
		END IF;
		RETURN v_existing.id;
	END IF;

	UPDATE ledger.ledger_posting_counter SET value = value + 1 RETURNING value INTO v_posting_seq;

	INSERT INTO ledger.journals (posting_seq, business_event_id, journal_type, currency, business_reference)
	VALUES (v_posting_seq, p_business_event_id, p_journal_type, p_currency, p_business_reference)
	RETURNING id INTO v_journal_id;

	INSERT INTO ledger.ledger_entries (journal_id, ordinal, account_id, side, amount_minor)
	SELECT v_journal_id, ordinal, account_id, side, amount_minor
	FROM ledger.resolve_entries(p_entries, p_currency);

	RETURN v_journal_id;
END;
$$;

REVOKE ALL ON FUNCTION ledger.resolve_entries(jsonb, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger.post_journal(text, text, text, text, jsonb) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION ledger.post_journal(text, text, text, text, jsonb) TO ledger_app;
GRANT SELECT ON ledger.ledger_accounts, ledger.journals, ledger.ledger_entries TO ledger_app;
