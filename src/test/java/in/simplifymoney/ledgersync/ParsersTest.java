package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParsersTest {

    @Test
    void parsesHdfcEmailAlert() {
        EmailParser parser = new EmailParser();
        String body = """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                Transaction reference: 1597155421

                This is a system generated email.
                """;

        RawMessage msg = new RawMessage("m-email-01", "email", "alerts@hdfcbank.net",
                OffsetDateTime.now(), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> txn = parser.parse(msg);
        assertTrue(txn.isPresent());
        assertEquals("4821", txn.get().accountLast4());
        assertEquals(Direction.CREDIT, txn.get().direction());
        assertEquals(new BigDecimal("45000.00"), txn.get().amount());
        assertEquals("SALARY CREDIT", txn.get().merchant());
    }

    @Test
    void parsesIciciEmailAlert() {
        EmailParser parser = new EmailParser();
        String body = """
                Date: Mon, 06 Jul 2026 11:25:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 9075 has been debited with Rs.129.67.
                Merchant / Remarks: RELIANCE SMART
                Transaction reference: 6576810104

                This is a system generated email.
                """;

        RawMessage msg = new RawMessage("m-email-02", "email", "alerts@icicibank.com",
                OffsetDateTime.now(), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> txn = parser.parse(msg);
        assertTrue(txn.isPresent());
        assertEquals("9075", txn.get().accountLast4());
        assertEquals(Direction.DEBIT, txn.get().direction());
        assertEquals(new BigDecimal("129.67"), txn.get().amount());
        assertEquals("RELIANCE SMART", txn.get().merchant());
    }

    @Test
    void parsesIcicV2SmsFormat() {
        IciciSmsParser parser = new IciciSmsParser();
        String body = "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30";
        RawMessage msg = new RawMessage("m-icici-v2", "sms", "VM-ICICIB-T",
                OffsetDateTime.now(), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> txn = parser.parse(msg);
        assertTrue(txn.isPresent());
        assertEquals("9075", txn.get().accountLast4());
        assertEquals(Direction.DEBIT, txn.get().direction());
        assertEquals(new BigDecimal("5.00"), txn.get().amount());
        assertEquals("UPI/BARBER", txn.get().merchant());
        assertEquals(new BigDecimal("52841.30"), txn.get().statedBalance());
    }
}
