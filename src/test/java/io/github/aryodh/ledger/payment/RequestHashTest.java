package io.github.aryodh.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHashTest {

	// Stored hashes are kept for the life of the database. If this value changes, every retry of an existing key
	// returns 409 IDEMPOTENCY_KEY_REUSED, so the canonical form must only change under a new version prefix.
	@Test
	void hashOfAKnownRequestNeverChanges() {
		assertThat(PaymentAcceptance.requestHash("order-1", 10000, "SGD"))
			.isEqualTo("9df580d308a4070f5b4b0e1cfd06d516b8b7e52eeaabaea7910c739e4c734d59");
	}

	@Test
	void fieldBoundariesCannotBeShifted() {
		assertThat(PaymentAcceptance.requestHash("a|1", 1, "SGD"))
			.isNotEqualTo(PaymentAcceptance.requestHash("a", 11, "SGD"));
		assertThat(PaymentAcceptance.requestHash("order-1", 10000, "SGD"))
			.isNotEqualTo(PaymentAcceptance.requestHash("order-1", 10001, "SGD"));
	}

}
