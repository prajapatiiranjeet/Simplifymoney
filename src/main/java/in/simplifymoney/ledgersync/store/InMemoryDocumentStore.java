package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-performance DocumentStore implementation serving the three access patterns.
 * Can be used standalone, in test suites, and during migration/verification.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final List<NormalizedTxn> transactions = new CopyOnWriteArrayList<>();
    private final Map<String, NormalizedTxn> byMessageIdIndex = new ConcurrentHashMap<>();

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        return transactions.stream()
                .filter(t -> t.accountLast4().equals(accountLast4))
                .filter(t -> YearMonth.from(t.occurredAt()).equals(month))
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt).reversed())
                .toList();
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            totals.put(c, BigDecimal.ZERO.setScale(2));
        }

        for (NormalizedTxn t : transactions) {
            if (!t.accountLast4().equals(accountLast4)) continue;
            totals.put(t.category(), totals.get(t.category()).add(t.amount()));
        }
        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        NormalizedTxn txn = byMessageIdIndex.get(messageId);
        return Optional.ofNullable(txn);
    }

    @Override
    public void save(NormalizedTxn txn) {
        transactions.add(txn);
        for (String msgId : txn.sourceMessageIds()) {
            byMessageIdIndex.put(msgId, txn);
        }
    }

    public List<NormalizedTxn> all() {
        return Collections.unmodifiableList(transactions);
    }
}
