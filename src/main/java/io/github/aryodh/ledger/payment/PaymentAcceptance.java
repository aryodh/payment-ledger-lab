package io.github.aryodh.ledger.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private static final String LOCK_NOT_AVAILABLE = "55P03";

	private static final String RETRY_AFTER_SECONDS = "1";

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final JsonMapper json;

	private final long feeBasisPoints;

	PaymentAcceptance(JdbcClient jdbc, TransactionTemplate transaction, JsonMapper json,
			@Value("${ledger.platform-fee-bps}") long feeBasisPoints) {
		if (feeBasisPoints < 0 || feeBasisPoints > 10000) {
			throw new IllegalStateException("ledger.platform-fee-bps must be between 0 and 10000");
		}
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.json = json;
		this.feeBasisPoints = feeBasisPoints;
	}

	public Response accept(String idempotencyKey, String merchantReference, long amountMinor, String currency,
			String requestId) {
		String requestHash = sha256(this.json.writeValueAsString(List.of(SCOPE, merchantReference, amountMinor, currency)));
		UUID paymentId = UUID.randomUUID();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("payment_id", paymentId);
		body.put("status", "PENDING");
		body.put("status_url", "/v1/payments/" + paymentId);
		var accepted = new Response(HttpStatus.ACCEPTED.value(), this.json.writeValueAsString(body));
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
			if (LOCK_NOT_AVAILABLE.equals(sqlState(ex))) {
				var headers = new HttpHeaders();
				headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
				throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
						"A request with this Idempotency-Key is still being processed", headers);
			}
			throw ex;
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

	private static String sqlState(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}
		return null;
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

}
