package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentStoreTest {

    @Test
    void servesThreeAccessPatternsDirectly() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();

        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-15T15:30:00+05:30");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-08-01T09:00:00+05:30");

        NormalizedTxn txn1 = new NormalizedTxn("4821", t1, Direction.DEBIT, new BigDecimal("500.00"),
                Category.SPEND, "AMAZON", List.of("msg-1"));
        NormalizedTxn txn2 = new NormalizedTxn("4821", t2, Direction.DEBIT, new BigDecimal("50.00"),
                Category.MICRO, "UPI/CHAI", List.of("msg-2", "msg-2-email"));
        NormalizedTxn txn3 = new NormalizedTxn("4821", t3, Direction.CREDIT, new BigDecimal("20000.00"),
                Category.INCOME, "SALARY", List.of("msg-3"));

        store.save(txn1);
        store.save(txn2);
        store.save(txn3);

        // Q1: one account's transactions for one month, newest first
        List<NormalizedTxn> july = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july.size());
        assertEquals("msg-2", july.get(0).sourceMessageIds().get(0)); // newest first
        assertEquals("msg-1", july.get(1).sourceMessageIds().get(0));

        // Q2: running totals per category for an account
        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("50.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("20000.00"), totals.get(Category.INCOME));

        // Q3: given a message id, which transaction did it produce?
        Optional<NormalizedTxn> byMsg2 = store.byMessageId("msg-2-email");
        assertTrue(byMsg2.isPresent());
        assertEquals("UPI/CHAI", byMsg2.get().merchant());
    }

    @Test
    void consistencyCheckerIdentifiesDeliberatelyAlteredDocument(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test-ledger.db");
        Path migrationDir = Path.of("db/migration");

        try (SqlLedgerStore sql = new SqlLedgerStore(dbFile)) {
            sql.migrate(migrationDir);

            InMemoryDocumentStore docs = new InMemoryDocumentStore();
            Backfill backfill = new Backfill(sql, docs);
            Backfill.Result res = backfill.run();
            assertTrue(res.written() > 0);

            // Re-run backfill to verify idempotency (should write 0 new items)
            Backfill.Result res2 = backfill.run();
            assertEquals(0, res2.written());

            // Initially consistent
            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divsInitial = checker.check();
            assertTrue(divsInitial.isEmpty(), "Expected stores to agree initially");

            // Deliberately alter the document store
            NormalizedTxn targetTxn = docs.byMessageId("m-legacy-0011").orElseThrow();
            // Create modified version with altered amount
            NormalizedTxn altered = new NormalizedTxn(
                    targetTxn.accountLast4(),
                    targetTxn.occurredAt(),
                    targetTxn.direction(),
                    new BigDecimal("99999.00"), // altered amount!
                    targetTxn.category(),
                    targetTxn.merchant(),
                    targetTxn.sourceMessageIds());
            docs.save(altered);

            // Checker must detect the alteration and name it!
            List<ConsistencyChecker.Divergence> divsAltered = checker.check();
            assertFalse(divsAltered.isEmpty(), "Checker must find the deliberate change");
            boolean foundAmountMismatch = divsAltered.stream()
                    .anyMatch(d -> d.what().contains("amount mismatch") && d.inDocuments().equals("99999.00"));
            assertTrue(foundAmountMismatch, "Checker must pinpoint the exact amount mismatch");
        }
    }
}
