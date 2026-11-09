package io.github.aryodh.ledger.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

import io.github.aryodh.ledger.api.ApiException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a payment at most once per idempotency key. The key, payment, capture operation, audit record and stored
 * response commit in one transaction, so a replay always finds a complete acceptance.
 */
@Service
public class PaymentAcceptance {

	static final String SCOPE = "POST /v1/payments";

	// Spring leaves PostgreSQL's lock_not_available uncategorized, so it is matched by SQLState.
	private static final String LOCK_NOT_AVAILABLE = "55P03";

	private static final String RETRY_AFTER_SECONDS = "1";

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final JsonMapper json;

	private final long feeBasisPoints;

	PaymentAcceptance(JdbcClient jdbc, TransactionTemplate transaction, JsonMapper json,
			@Value("${ledger.platform-fee-bps}") long feeBasisPoints) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.json = json;
		this.feeBasisPoints = feeBasisPoints;
	}

	public Response accept(String idempotencyKey, String merchantReference, long amountMinor, String currency,
			String requestId) {
		String requestHash = requestHash(merchantReference, amountMinor, currency);
		UUID paymentId = UUID.randomUUID();
		var accepted = new Response(HttpStatus.ACCEPTED.value(),
				this.json.writeValueAsString(new Accepted(paymentId, "PENDING", "/v1/payments/" + paymentId)));
		try {
			return this.transaction.execute(status -> {
				// A request with the same key waits on the unique index for this one to commit or roll back.
				this.jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
				int claimed = this.jdbc.sql("""
						INSERT INTO idempotency_records
							(scope, key, canonical_request_hash, response_status, response_body, resource_id)
						VALUES (?, ?, ?, ?, ?::json, ?)
						ON CONFLICT (scope, key) DO NOTHING
						""").params(SCOPE, idempotencyKey, requestHash, accepted.status(), accepted.body(), paymentId).update();
				if (claimed == 0) {
					return replay(idempotencyKey, requestHash);
				}
				insertPayment(paymentId, merchantReference, amountMinor, currency, requestId);
				return accepted;
			});
		}
		catch (DataAccessException ex) {
			if (!(ex.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState()))) {
				throw ex;
			}
			var headers = new HttpHeaders();
			headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
			throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "A conflicting request is still being processed",
					headers);
		}
	}

	private Response replay(String idempotencyKey, String requestHash) {
		return this.jdbc.sql("""
				SELECT canonical_request_hash, response_status, response_body::text
				FROM idempotency_records WHERE scope = ? AND key = ?
				""").params(SCOPE, idempotencyKey).query((rs, row) -> {
			if (!rs.getString("canonical_request_hash").equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"This Idempotency-Key was used with a different request");
			}
			return new Response(rs.getInt("response_status"), rs.getString("response_body"));
		}).single();
	}

	private void insertPayment(UUID paymentId, String merchantReference, long amountMinor, String currency,
			String requestId) {
		try {
			this.jdbc.sql("""
					INSERT INTO payments (id, merchant_reference, amount_minor, fee_minor, currency, status)
					VALUES (?, ?, ?, ?, ?, 'PENDING')
					""").params(paymentId, merchantReference, amountMinor, amountMinor * this.feeBasisPoints / 10000, currency)
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_MERCHANT_REFERENCE",
					"A payment with this merchant_reference already exists");
		}
		this.jdbc.sql("""
				INSERT INTO provider_operations (id, operation_type, payment_id, provider_idempotency_key, state)
				VALUES (?, 'CAPTURE', ?, ?, 'QUEUED')
				""").params(UUID.randomUUID(), paymentId, "capture:" + paymentId).update();
		this.jdbc.sql("""
				INSERT INTO audit_log (actor, action, resource, after_status, request_id)
				VALUES ('unauthenticated', 'PAYMENT_ACCEPTED', ?, 'PENDING', ?)
				""").params("payment:" + paymentId, requestId).update();
	}

	/**
	 * Fingerprints the request fields that must match for a replay. Stored hashes are kept for the life of the
	 * database, so this format is fixed here rather than taken from a serializer whose output can change with
	 * configuration or upgrades. Changing it means a new version prefix, with old records still compared under v1.
	 * Each string is length-prefixed so no two requests share a canonical form.
	 */
	static String requestHash(String merchantReference, long amountMinor, String currency) {
		String canonical = "v1|" + SCOPE.length() + ":" + SCOPE + "|" + merchantReference.length() + ":"
				+ merchantReference + "|" + amountMinor + "|" + currency.length() + ":" + currency;
		return sha256(canonical);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The status and exact body to return, whether new or replayed.
	 */
	public record Response(int status, String body) {
	}

	record Accepted(UUID paymentId, String status, String statusUrl) {
	}

}
