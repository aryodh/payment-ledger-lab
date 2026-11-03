package io.github.aryodh.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PaymentAcceptanceIT extends LedgerDatabaseTestBase {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transaction;

	@Autowired
	private JsonMapper json;

	@Test
	void acceptedPaymentStoresPaymentCaptureOperationAuditRecordAndResponse() throws Exception {
		String reference = newReference();

		HttpResponse<String> response = post(newKey(), body(reference, 10000));

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode accepted = this.json.readTree(response.body());
		UUID payment = UUID.fromString(accepted.get("payment_id").asString());
		assertThat(accepted.get("status").asString()).isEqualTo("PENDING");
		assertThat(accepted.get("status_url").asString()).isEqualTo("/v1/payments/" + payment);
		assertThat(row("""
				SELECT merchant_reference || ' ' || amount_minor || ' ' || fee_minor || ' ' || currency || ' ' || status
				FROM payments WHERE id = ?""", payment)).isEqualTo(reference + " 10000 300 SGD PENDING");
		assertThat(row("""
				SELECT operation_type || ' ' || provider_idempotency_key || ' ' || state
				FROM provider_operations WHERE payment_id = ?""", payment))
			.isEqualTo("CAPTURE capture:" + payment + " QUEUED");
		assertThat(row("""
				SELECT actor || ' ' || action || ' ' || after_status || ' ' || (request_id ~ '^[0-9a-f-]{36}$')
				FROM audit_log WHERE resource = 'payment:' || ?""", payment))
			.isEqualTo("unauthenticated PAYMENT_ACCEPTED PENDING true");
		assertThat(row("SELECT response_status || ' ' || response_body FROM idempotency_records WHERE resource_id = ?",
				payment))
			.isEqualTo("202 " + response.body());
	}

	@ParameterizedTest
	@CsvSource({ "1, 0", "333, 9", "100000000, 3000000" })
	void feeIsSnapshottedFromConfigurationRoundingDown(long amount, long fee) throws Exception {
		String reference = newReference();

		assertThat(post(newKey(), body(reference, amount)).statusCode()).isEqualTo(202);

		assertThat(this.jdbc.sql("SELECT fee_minor FROM payments WHERE merchant_reference = ?")
			.param(reference)
			.query(Long.class)
			.single()).isEqualTo(fee);
	}

	@Test
	void sameKeyAndRequestReplaysTheOriginalResponse() throws Exception {
		String key = newKey();
		String reference = newReference();

		HttpResponse<String> first = post(key, body(reference, 10000));
		HttpResponse<String> replay = post(key, body(reference, 10000));

		assertThat(replay.statusCode()).isEqualTo(202);
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(acceptedPayments(reference)).isOne();
	}

	@Test
	void hundredConcurrentIdenticalRequestsAcceptOnePayment() throws Exception {
		String key = newKey();
		String reference = newReference();
		var start = new CountDownLatch(1);

		List<HttpResponse<String>> responses;
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<HttpResponse<String>>> requests = IntStream.range(0, 100).mapToObj(i -> executor.submit(() -> {
				start.await();
				return post(key, body(reference, 10000));
			})).toList();
			start.countDown();
			responses = requests.stream().map(PaymentAcceptanceIT::result).toList();
		}

		assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(202));
		assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
		assertThat(acceptedPayments(reference)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM provider_operations o JOIN payments p ON p.id = o.payment_id
				WHERE p.merchant_reference = ?""").param(reference).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit_log a JOIN payments p ON a.resource = 'payment:' || p.id
				WHERE p.merchant_reference = ?""").param(reference).query(Long.class).single()).isOne();
	}

	@ParameterizedTest
	@CsvSource({ "false, 10001", "true, 10000" })
	void sameKeyWithADifferentRequestConflicts(boolean otherReference, long amount) throws Exception {
		String key = newKey();
		String reference = newReference();
		String secondReference = otherReference ? newReference() : reference;
		post(key, body(reference, 10000));

		HttpResponse<String> conflict = post(key, body(secondReference, amount));

		assertProblem(conflict, 409, "IDEMPOTENCY_KEY_REUSED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM payments WHERE merchant_reference IN (?, ?)")
			.params(reference, secondReference)
			.query(Long.class)
			.single()).isOne();
	}

	@Test
	void differentKeyWithTheSameMerchantReferenceConflictsWithoutReservingTheKey() throws Exception {
		String reference = newReference();
		String secondKey = newKey();
		post(newKey(), body(reference, 10000));

		HttpResponse<String> conflict = post(secondKey, body(reference, 10000));

		assertProblem(conflict, 409, "DUPLICATE_MERCHANT_REFERENCE");
		assertThat(acceptedPayments(reference)).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM idempotency_records WHERE key = ?")
			.param(secondKey)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void keyHeldByAnUncommittedRequestReturnsRequestInProgress() throws Exception {
		String key = newKey();
		String reference = newReference();
		var claimed = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> holder = executor.submit(() -> this.transaction.executeWithoutResult(status -> {
				this.jdbc.sql("""
						INSERT INTO idempotency_records
							(scope, key, canonical_request_hash, response_status, response_body, resource_id)
						VALUES ('POST /v1/payments', ?, 'held', 202, '{}', ?)
						""").params(key, UUID.randomUUID()).update();
				claimed.countDown();
				try {
					release.await();
				}
				catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
				status.setRollbackOnly();
			}));
			while (!claimed.await(100, TimeUnit.MILLISECONDS)) {
				if (holder.isDone()) {
					holder.get();
				}
			}

			HttpResponse<String> inProgress;
			try {
				inProgress = post(key, body(reference, 10000));
			}
			finally {
				release.countDown();
			}
			holder.get();

			assertProblem(inProgress, 409, "REQUEST_IN_PROGRESS");
			assertThat(inProgress.headers().firstValue("Retry-After")).hasValue("1");
		}

		assertThat(post(key, body(reference, 10000)).statusCode()).isEqualTo(202);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			not json                                                                    | 400 | MALFORMED_REQUEST
			{"merchant_reference": "r", "currency": "SGD"}                              | 400 | MALFORMED_REQUEST
			{"merchant_reference": "r", "amount_minor": 1.5, "currency": "SGD"}         | 400 | MALFORMED_REQUEST
			{"merchant_reference": "r", "amount_minor": "100", "currency": "SGD"}       | 400 | MALFORMED_REQUEST
			{"merchant_reference": "r", "amount_minor": 99999999999999999999, "currency": "SGD"} | 400 | MALFORMED_REQUEST
			{"merchant_reference": "r", "amount_minor": 0, "currency": "SGD"}           | 422 | INVALID_AMOUNT
			{"merchant_reference": "r", "amount_minor": -5, "currency": "SGD"}          | 422 | INVALID_AMOUNT
			{"merchant_reference": "r", "amount_minor": 100000001, "currency": "SGD"}   | 422 | INVALID_AMOUNT
			{"merchant_reference": "r", "amount_minor": 100, "currency": "USD"}         | 422 | UNSUPPORTED_CURRENCY
			{"merchant_reference": "", "amount_minor": 100, "currency": "SGD"}          | 422 | INVALID_MERCHANT_REFERENCE
			""")
	void invalidRequestIsRejectedWithoutReservingTheKey(String body, int status, String code) throws Exception {
		String key = newKey();

		assertProblem(post(key, body), status, code);

		assertThat(post(key, body(newReference(), 10000)).statusCode()).isEqualTo(202);
	}

	@Test
	void merchantReferenceLongerThan200CharactersIsRejected() throws Exception {
		assertProblem(post(newKey(), body("x".repeat(201), 100)), 422, "INVALID_MERCHANT_REFERENCE");
	}

	@ParameterizedTest
	@ValueSource(ints = { -1, 0, 256 })
	void missingEmptyOrOverlongIdempotencyKeyIsRejected(int keyLength) throws Exception {
		String key = keyLength < 0 ? null : "k".repeat(keyLength);

		assertProblem(post(key, body(newReference(), 100)), 400, "INVALID_IDEMPOTENCY_KEY");
	}

	@ParameterizedTest
	@ValueSource(strings = { "UPDATE payments SET status = 'SUCCEEDED'", "DELETE FROM payments", "TRUNCATE payments CASCADE",
			"UPDATE provider_operations SET state = 'CONFIRMED'", "DELETE FROM provider_operations",
			"UPDATE idempotency_records SET response_status = 200", "DELETE FROM idempotency_records",
			"TRUNCATE idempotency_records", "UPDATE audit_log SET actor = 'someone'", "DELETE FROM audit_log",
			"TRUNCATE audit_log" })
	void applicationRoleCannotChangeAcceptedRecords(String sql) throws Exception {
		post(newKey(), body(newReference(), 100));

		assertThatThrownBy(() -> this.jdbc.sql(sql).update()).rootCause().hasMessageContaining("permission denied");
	}

	private HttpResponse<String> post(String key, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/v1/payments"))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode problem = this.json.readTree(response.body());
		assertThat(problem.get("status").asInt()).isEqualTo(status);
		assertThat(problem.get("code").asString()).isEqualTo(code);
		assertThat(problem.get("instance").asString()).isEqualTo("/v1/payments");
		assertThat(UUID.fromString(problem.get("request_id").asString())).isNotNull();
	}

	private String row(String sql, UUID payment) {
		return this.jdbc.sql(sql).param(payment).query(String.class).single();
	}

	private long acceptedPayments(String reference) {
		return this.jdbc.sql("SELECT count(*) FROM payments WHERE merchant_reference = ?")
			.param(reference)
			.query(Long.class)
			.single();
	}

	private static HttpResponse<String> result(Future<HttpResponse<String>> request) {
		try {
			return request.get();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String body(String reference, long amount) {
		return """
				{"merchant_reference": "%s", "amount_minor": %d, "currency": "SGD"}""".formatted(reference, amount);
	}

	private static String newKey() {
		return "key-" + UUID.randomUUID();
	}

	private static String newReference() {
		return "order-" + UUID.randomUUID();
	}

}
