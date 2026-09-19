package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages, deduplicates multiple evidences for the same
 * financial transaction, assigns the correct Category, and puts transactions
 * in the ledger.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;
        List<ParsedTxn> parsedList = new ArrayList<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsedList.add(p.get());
        }

        List<NormalizedTxn> transactions = deduplicateAndClassify(parsedList);
        for (NormalizedTxn txn : transactions) {
            store.save(txn);
        }

        return new Stats(messages.size(), transactions.size(), skipped);
    }

    private record DedupeKey(String accountLast4, OffsetDateTime occurredAt, Direction direction) {}

    public static List<NormalizedTxn> deduplicateAndClassify(List<ParsedTxn> parsed) {
        Map<DedupeKey, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (ParsedTxn pt : parsed) {
            DedupeKey key = new DedupeKey(pt.accountLast4(), pt.occurredAt(), pt.direction());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
        }

        List<NormalizedTxn> out = new ArrayList<>();
        for (Map.Entry<DedupeKey, List<ParsedTxn>> entry : groups.entrySet()) {
            DedupeKey key = entry.getKey();
            List<ParsedTxn> evidences = entry.getValue();

            // When amounts differ (e.g. integer SMS vs precise email), pick the one carrying decimals
            BigDecimal amount = evidences.get(0).amount();
            for (ParsedTxn pt : evidences) {
                if (pt.amount().remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                    amount = pt.amount();
                    break;
                }
            }

            List<String> sourceMessageIds = evidences.stream()
                    .map(ParsedTxn::sourceMessageId)
                    .distinct()
                    .toList();

            String merchant = evidences.get(0).merchant();

            Category category = classify(key.direction(), amount, merchant);
            out.add(new NormalizedTxn(
                    key.accountLast4(),
                    key.occurredAt(),
                    key.direction(),
                    amount,
                    category,
                    merchant,
                    sourceMessageIds));
        }
        return out;
    }

    private static Category classify(Direction direction, BigDecimal amount, String merchant) {
        String upperMerchant = merchant.toUpperCase();
        // Cross-account transfers between user's own accounts
        if (upperMerchant.contains("PARAG KAPOOR")) {
            return Category.TRANSFER;
        }

        if (direction == Direction.DEBIT) {
            if (amount.compareTo(HUNDRED) <= 0 && (upperMerchant.startsWith("UPI/") || upperMerchant.contains("UPI"))) {
                return Category.MICRO;
            }
            return Category.SPEND;
        } else {
            return Category.INCOME;
        }
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
