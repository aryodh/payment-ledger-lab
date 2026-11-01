package io.github.aryodh.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class PostJournalIT extends LedgerDatabaseTestBase {

	private static final String CAPTURE = entries(entry("PROVIDER_CLEARING", "DEBIT", 10000),
			entry("MERCHANT_PAYABLE", "CREDIT", 9700), entry("PLATFORM_FEE_REVENUE", "CREDIT", 300));

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transaction;

	@Test
	void validJournalCommitsWithItsEntriesInOrder() {
		String event = newEvent();

		UUID journal = post(event, "CAPTURE", "payment-1", CAPTURE);

		assertThat(journalId(event)).isEqualTo(journal);
		assertThat(jdbc.sql("""
				SELECT a.code || ' ' || e.side || ' ' || e.amount_minor
				FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
				WHERE e.journal_id = ? ORDER BY e.ordinal
				""").param(journal).query(String.class).list())
			.containsExactly("PROVIDER_CLEARING DEBIT 10000", "MERCHANT_PAYABLE CREDIT 9700",
					"PLATFORM_FEE_REVENUE CREDIT 300");
	}

	@Test
	void fullRefundEndsWithTheDocumentedMerchantDeficit() {
		String reference = "payment-" + UUID.randomUUID();

		post(newEvent(), "CAPTURE", reference, CAPTURE);
		post(newEvent(), "REFUND", reference, refund(2000));
		post(newEvent(), "REFUND", reference, refund(8000));

		assertThat(balancesFor(reference)).containsOnly(Map.entry("PROVIDER_CLEARING", BigDecimal.ZERO),
				Map.entry("MERCHANT_PAYABLE", BigDecimal.valueOf(-300)),
				Map.entry("PLATFORM_FEE_REVENUE", BigDecimal.valueOf(300)));
	}

	@ParameterizedTest
	@MethodSource("invalidEntries")
	void invalidEntriesAreRejectedWithNoPartialJournal(String entries, String message) {
		String event = newEvent();

		assertThatThrownBy(() -> post(event, "CAPTURE", "payment-1", entries)).rootCause().hasMessageContaining(message);

		assertThat(journalCount(event)).isZero();
	}

	static Stream<Arguments> invalidEntries() {
		String debit = entry("PROVIDER_CLEARING", "DEBIT", 100);
		String credit = entry("MERCHANT_PAYABLE", "CREDIT", 100);
		return Stream.of(Arguments.of(entries(debit, entry("MERCHANT_PAYABLE", "CREDIT", 99)), "unbalanced"),
				Arguments.of(entries(debit, entry("MERCHANT_PAYABLE", "CREDIT", 101)), "unbalanced"),
				Arguments.of(entries(debit, entry("MERCHANT_PAYABLE", "DEBIT", 100)), "unbalanced"),
				Arguments.of(entries(debit), "at least two entries"), Arguments.of("[]", "at least two entries"),
				Arguments.of(null, "at least two entries"), Arguments.of("{}", "non-array"),
				Arguments.of(entries(debit, entry("NO_SUCH_ACCOUNT", "CREDIT", 100)), "not in currency"),
				Arguments.of(entries(debit, entry("MERCHANT_PAYABLE", "SIDEWAYS", 100)), "DEBIT or CREDIT"),
				Arguments.of(entries(debit, """
						{"account": "MERCHANT_PAYABLE", "amount_minor": 100}"""), "DEBIT or CREDIT"),
				Arguments.of(entries(entry("PROVIDER_CLEARING", "DEBIT", 0), entry("MERCHANT_PAYABLE", "CREDIT", 0)),
						"between 1 and 100000000"),
				Arguments.of(entries(entry("PROVIDER_CLEARING", "DEBIT", -5), entry("MERCHANT_PAYABLE", "CREDIT", -5)),
						"between 1 and 100000000"),
				Arguments.of(entries(entry("PROVIDER_CLEARING", "DEBIT", 100000001),
						entry("MERCHANT_PAYABLE", "CREDIT", 100000001)), "between 1 and 100000000"),
				Arguments.of(entries(credit, """
						{"account": "PROVIDER_CLEARING", "side": "DEBIT"}"""), "between 1 and 100000000"),
				Arguments.of(entries(credit, """
						{"account": "PROVIDER_CLEARING", "side": "DEBIT", "amount_minor": 100.5}"""), "bigint"),
				Arguments.of(entries(credit, """
						{"account": "PROVIDER_CLEARING", "side": "DEBIT", "amount_minor": 99999999999999999999}"""),
						"bigint"));
	}

	@Test
	void accountOutsideTheJournalCurrencyIsRejected() {
		String event = newEvent();

		assertThatThrownBy(() -> jdbc.sql("SELECT post_journal(?, 'CAPTURE', 'USD', 'payment-1', ?::jsonb)")
			.params(event, CAPTURE)
			.query(UUID.class)
			.single()).rootCause().hasMessageContaining("not in currency USD");

		assertThat(journalCount(event)).isZero();
	}

	@ParameterizedTest
	@MethodSource("invalidHeaders")
	void invalidJournalHeaderIsRejected(String event, String type, String reference) {
		assertThatThrownBy(() -> post(event, type, reference, CAPTURE)).rootCause()
			.hasMessageMatching("(?s).*(check constraint|not-null constraint).*");
	}

	static Stream<Arguments> invalidHeaders() {
		return Stream.of(Arguments.of("", "CAPTURE", "payment-1"), Arguments.of(null, "CAPTURE", "payment-1"),
				Arguments.of("x".repeat(201), "CAPTURE", "payment-1"), Arguments.of(newEvent(), "GIFT", "payment-1"),
				Arguments.of(newEvent(), "CAPTURE", ""), Arguments.of(newEvent(), "CAPTURE", null));
	}

	@Test
	void repeatedBusinessEventWithSameFactsReturnsTheOriginalJournalAndUsesNoNumber() {
		String event = newEvent();
		UUID original = post(event, "CAPTURE", "payment-1", CAPTURE);
		long lastSeq = lastPostingSeq();

		UUID repeated = post(event, "CAPTURE", "payment-1",
				entries(entry("PLATFORM_FEE_REVENUE", "CREDIT", 300), entry("MERCHANT_PAYABLE", "CREDIT", 9700),
						entry("PROVIDER_CLEARING", "DEBIT", 10000)));

		assertThat(repeated).isEqualTo(original);
		assertThat(journalCount(event)).isOne();
		assertThat(lastPostingSeq()).isEqualTo(lastSeq);
	}

	@ParameterizedTest
	@MethodSource("differentFacts")
	void repeatedBusinessEventWithDifferentFactsIsRejected(String type, String reference, String entries) {
		String event = newEvent();
		post(event, "CAPTURE", "payment-1", CAPTURE);

		assertThatThrownBy(() -> post(event, type, reference, entries)).rootCause()
			.hasMessageContaining("different facts");
	}

	static Stream<Arguments> differentFacts() {
		return Stream.of(Arguments.of("REFUND", "payment-1", CAPTURE), Arguments.of("CAPTURE", "payment-2", CAPTURE),
				Arguments.of("CAPTURE", "payment-1", refund(2000)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "INSERT INTO journals (posting_seq, business_event_id, journal_type, currency, business_reference) VALUES (999999, 'forged', 'CAPTURE', 'SGD', 'forged')",
			"UPDATE journals SET business_reference = 'changed'", "DELETE FROM journals", "TRUNCATE journals CASCADE",
			"INSERT INTO ledger_entries (journal_id, ordinal, account_id, side, amount_minor) SELECT j.id, 99, a.id, 'DEBIT', 1 FROM journals j, ledger_accounts a LIMIT 1",
			"UPDATE ledger_entries SET amount_minor = 1", "DELETE FROM ledger_entries", "TRUNCATE ledger_entries",
			"INSERT INTO ledger_accounts (code, currency, account_type, normal_side) VALUES ('FORGED', 'SGD', 'ASSET', 'DEBIT')",
			"UPDATE ledger_accounts SET code = 'CHANGED'", "DELETE FROM ledger_accounts", "TRUNCATE ledger_accounts CASCADE",
			"SELECT value FROM ledger_posting_counter", "UPDATE ledger_posting_counter SET value = 0",
			"DELETE FROM ledger_posting_counter", "SELECT * FROM resolve_entries('[]', 'SGD')" })
	void applicationRoleCannotWriteTheBooksDirectly(String sql) {
		post(newEvent(), "CAPTURE", "payment-1", CAPTURE);

		assertThatThrownBy(() -> jdbc.sql(sql).update()).rootCause().hasMessageContaining("permission denied");
	}

	@Test
	void postingFunctionIsLockedDown() {
		Map<String, Object> function = jdbc.sql("""
				SELECT pg_get_userbyid(proowner) AS owner, prosecdef AS definer, proconfig::text AS config,
					EXISTS (SELECT FROM aclexplode(proacl) WHERE grantee = 0) AS public_execute
				FROM pg_proc WHERE proname = 'post_journal'
				""").query().singleRow();

		assertThat(function).containsEntry("owner", "ledger_migrator")
			.containsEntry("definer", true)
			.containsEntry("config", "{\"search_path=\\\"\\\"\"}")
			.containsEntry("public_execute", false);
	}

	@Test
	void secondPostingWaitsForTheFirstToCommitSoSequenceFollowsCommitOrder() throws Exception {
		String firstEvent = newEvent();
		String secondEvent = newEvent();

		postSecondWhileFirstIsUncommitted(firstEvent, secondEvent);

		assertThat(postingSeq(secondEvent)).isEqualTo(postingSeq(firstEvent) + 1);
	}

	@Test
	void duplicateWaitingOnAnUncommittedPostingReturnsTheOriginalJournal() throws Exception {
		String event = newEvent();
		long lastSeq = lastPostingSeq();

		UUID duplicate = postSecondWhileFirstIsUncommitted(event, event);

		assertThat(duplicate).isEqualTo(journalId(event));
		assertThat(journalCount(event)).isOne();
		assertThat(lastPostingSeq()).isEqualTo(lastSeq + 1);
	}

	private UUID postSecondWhileFirstIsUncommitted(String firstEvent, String secondEvent) throws Exception {
		var firstPosted = new CountDownLatch(1);
		var commitFirst = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
				post(firstEvent, "CAPTURE", "payment-1", CAPTURE);
				firstPosted.countDown();
				try {
					commitFirst.await();
				}
				catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
			}));
			firstPosted.await();

			Future<UUID> second = executor.submit(() -> post(secondEvent, "CAPTURE", "payment-1", CAPTURE));

			try {
				await().atMost(Duration.ofSeconds(10)).until(this::aPostingIsWaitingOnALock);
			}
			finally {
				commitFirst.countDown();
			}
			first.get();
			return second.get();
		}
	}

	@Test
	void concurrentPostingsWithRollbacksStayGaplessAndPastBalancesStayStable() throws Exception {
		int workers = 8;
		int postingsPerWorker = 25;
		long journalsBefore = totalJournals();
		var running = new AtomicBoolean(true);
		List<long[]> gapSamples = new ArrayList<>();
		Map<Long, BigDecimal> pastBalances = new HashMap<>();

		try (ExecutorService executor = Executors.newFixedThreadPool(workers + 1)) {
			Future<?> sampler = executor.submit(() -> {
				while (running.get()) {
					gapSamples.add(jdbc.sql("SELECT count(*), coalesce(max(posting_seq), 0) FROM journals")
						.query((rs, row) -> new long[] { rs.getLong(1), rs.getLong(2) })
						.single());
					long seq = lastPostingSeq();
					pastBalances.put(seq, clearingBalanceAsOf(seq));
				}
			});

			List<Future<?>> postings = IntStream.range(0, workers)
				.<Future<?>>mapToObj(worker -> executor.submit(() -> IntStream.range(0, postingsPerWorker)
					.forEach(i -> transaction.executeWithoutResult(status -> {
						post(newEvent(), "CAPTURE", "payment-1", CAPTURE);
						if (i % 5 == 0) {
							status.setRollbackOnly();
						}
					}))))
				.toList();
			for (Future<?> posting : postings) {
				posting.get();
			}
			running.set(false);
			sampler.get();
		}

		assertThat(totalJournals()).isEqualTo(journalsBefore + workers * (postingsPerWorker - postingsPerWorker / 5));
		assertThat(lastPostingSeq()).isEqualTo(totalJournals());
		assertThat(gapSamples).isNotEmpty().allSatisfy(sample -> assertThat(sample[0]).isEqualTo(sample[1]));
		assertThat(pastBalances).isNotEmpty()
			.allSatisfy((seq, balance) -> assertThat(clearingBalanceAsOf(seq)).isEqualByComparingTo(balance));
	}

	private UUID post(String event, String type, String reference, String entries) {
		return jdbc.sql("SELECT post_journal(?, ?, 'SGD', ?, ?::jsonb)")
			.params(event, type, reference, entries)
			.query(UUID.class)
			.single();
	}

	private boolean aPostingIsWaitingOnALock() {
		return jdbc.sql("""
				SELECT EXISTS (SELECT FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND query LIKE '%post_journal%' AND pid <> pg_backend_pid())
				""").query(Boolean.class).single();
	}

	private UUID journalId(String event) {
		return jdbc.sql("SELECT id FROM journals WHERE business_event_id = ?").param(event).query(UUID.class).single();
	}

	private long journalCount(String event) {
		return jdbc.sql("SELECT count(*) FROM journals WHERE business_event_id = ?").param(event).query(Long.class).single();
	}

	private long totalJournals() {
		return jdbc.sql("SELECT count(*) FROM journals").query(Long.class).single();
	}

	private long lastPostingSeq() {
		return jdbc.sql("SELECT coalesce(max(posting_seq), 0) FROM journals").query(Long.class).single();
	}

	private long postingSeq(String event) {
		return jdbc.sql("SELECT posting_seq FROM journals WHERE business_event_id = ?").param(event).query(Long.class).single();
	}

	private BigDecimal clearingBalanceAsOf(long seq) {
		return jdbc.sql("""
				SELECT coalesce(sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
				FROM ledger_entries e
				JOIN journals j ON j.id = e.journal_id
				JOIN ledger_accounts a ON a.id = e.account_id
				WHERE a.code = 'PROVIDER_CLEARING' AND j.posting_seq <= ?
				""").param(seq).query(BigDecimal.class).single();
	}

	private Map<String, BigDecimal> balancesFor(String reference) {
		return jdbc.sql("""
				SELECT a.code, sum(CASE WHEN e.side = a.normal_side THEN e.amount_minor ELSE -e.amount_minor END) AS balance
				FROM ledger_entries e
				JOIN journals j ON j.id = e.journal_id
				JOIN ledger_accounts a ON a.id = e.account_id
				WHERE j.business_reference = ?
				GROUP BY a.code
				""")
			.param(reference)
			.query((rs, row) -> Map.entry(rs.getString("code"), rs.getBigDecimal("balance")))
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static String newEvent() {
		return "event-" + UUID.randomUUID();
	}

	private static String refund(long amount) {
		return entries(entry("MERCHANT_PAYABLE", "DEBIT", amount), entry("PROVIDER_CLEARING", "CREDIT", amount));
	}

	private static String entry(String account, String side, long amount) {
		return """
				{"account": "%s", "side": "%s", "amount_minor": %d}""".formatted(account, side, amount);
	}

	private static String entries(String... entries) {
		return "[" + String.join(",", entries) + "]";
	}

}
