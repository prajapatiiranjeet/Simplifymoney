package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.*;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles unclean SQL legacy data (merging duplicates and consolidating message IDs)
 * and ensures idempotency so that re-running or running after a partial failure
 * produces a clean, consistent target.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> allSql = source.all();
        long read = allSql.size();
        long written = 0;
        long skipped = 0;

        // Group rows that describe the same transaction to combine sourceMessageIds
        Map<String, NormalizedTxn> consolidated = new LinkedHashMap<>();

        for (NormalizedTxn t : allSql) {
            String signature = t.accountLast4() + "|" + t.occurredAt() + "|" + t.direction() + "|"
                    + t.amount().toPlainString() + "|" + t.merchant();

            if (consolidated.containsKey(signature)) {
                NormalizedTxn existing = consolidated.get(signature);
                Set<String> mergedIds = new LinkedHashSet<>(existing.sourceMessageIds());
                mergedIds.addAll(t.sourceMessageIds());

                NormalizedTxn merged = new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        List.copyOf(mergedIds));
                consolidated.put(signature, merged);
            } else {
                consolidated.put(signature, t);
            }
        }

        for (NormalizedTxn t : consolidated.values()) {
            boolean alreadyInTarget = false;
            for (String msgId : t.sourceMessageIds()) {
                if (target.byMessageId(msgId).isPresent()) {
                    alreadyInTarget = true;
                    break;
                }
            }

            if (alreadyInTarget) {
                continue;
            }

            target.save(t);
            written++;
        }

        skipped = read - written;
        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
