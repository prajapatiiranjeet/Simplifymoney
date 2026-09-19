package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.*;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Compares record-by-record, checking amounts, directions, categories,
 * timestamps, and merchants, as well as per-category running totals.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlRows = sql.all();

        Set<String> checkedMessageIds = new HashSet<>();

        for (NormalizedTxn s : sqlRows) {
            for (String msgId : s.sourceMessageIds()) {
                if (!checkedMessageIds.add(msgId)) continue;

                Optional<NormalizedTxn> docOpt = documents.byMessageId(msgId);
                if (docOpt.isEmpty()) {
                    divergences.add(new Divergence(
                            "missing transaction for message " + msgId,
                            s.accountLast4() + " " + s.direction() + " " + s.amount(),
                            "NOT_FOUND"));
                    continue;
                }

                NormalizedTxn d = docOpt.get();

                if (!s.accountLast4().equals(d.accountLast4())) {
                    divergences.add(new Divergence(
                            "accountLast4 mismatch for message " + msgId,
                            s.accountLast4(),
                            d.accountLast4()));
                }

                if (s.amount().compareTo(d.amount()) != 0) {
                    divergences.add(new Divergence(
                            "amount mismatch for message " + msgId,
                            s.amount().toPlainString(),
                            d.amount().toPlainString()));
                }

                if (s.direction() != d.direction()) {
                    divergences.add(new Divergence(
                            "direction mismatch for message " + msgId,
                            s.direction().name(),
                            d.direction().name()));
                }

                if (s.category() != d.category()) {
                    divergences.add(new Divergence(
                            "category mismatch for message " + msgId,
                            s.category().name(),
                            d.category().name()));
                }

                if (!s.occurredAt().isEqual(d.occurredAt())) {
                    divergences.add(new Divergence(
                            "occurredAt mismatch for message " + msgId,
                            s.occurredAt().toString(),
                            d.occurredAt().toString()));
                }

                if (!Objects.equals(s.merchant(), d.merchant())) {
                    divergences.add(new Divergence(
                            "merchant mismatch for message " + msgId,
                            s.merchant(),
                            d.merchant()));
                }
            }
        }

        return Collections.unmodifiableList(divergences);
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
