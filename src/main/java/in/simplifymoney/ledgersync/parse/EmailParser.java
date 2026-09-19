package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Handles transaction alert emails from HDFC (alerts@hdfcbank.net) and
 * ICICI (alerts@icicibank.com).
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE = Pattern.compile("^Date:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern BODY = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with (?<amount>.+?)\\.\\s*"
                    + "Merchant / Remarks:\\s*(?<merchant>[^\\n]+)", Pattern.DOTALL);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();
        Matcher md = DATE.matcher(body);
        Matcher mb = BODY.matcher(body);

        if (!md.find() || !mb.find()) {
            return Optional.empty();
        }

        String dateStr = md.group(1).trim();
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(dateStr, DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (Exception e) {
            at = Dates.ist(dateStr);
            if (at == null) return Optional.empty();
        }

        Direction d = "debited".equalsIgnoreCase(mb.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount = Amounts.first(mb.group("amount"));
        if (amount == null) return Optional.empty();

        String merchant = mb.group("merchant").trim();
        return Optional.of(new ParsedTxn(mb.group("acct"), at, d, amount, merchant, null, m.messageId()));
    }
}
