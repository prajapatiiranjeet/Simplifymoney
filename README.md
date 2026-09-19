# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

## The document store

The ledger is moving off SQL onto a document store. **DynamoDB preferred,
MongoDB fine** — your choice, and say why. It must run from your
`docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, **report how many items the engine examined versus how
many it returned, at 100,000 transactions.** DynamoDB gives you `ScannedCount`
and `Count`; MongoDB gives you `totalDocsExamined` and `nReturned`. Put the six
numbers in your README.

#### Engine Choice: MongoDB
We selected **MongoDB** (configured via `docker-compose.yml` with `mongo:7.0`):
- **Array Support:** Transactions reference multiple messages (`source_message_ids`). MongoDB's multikey indexes allow direct index lookups without normalization tables.
- **ESR Rule (Equality, Sort, Range):** Compound index `{ account_last4: 1, occurred_at: -1 }` guarantees monthly queries are sorted by B-Tree index traversal with zero memory sort.
- **Covered Aggregation:** Per-account category sums run against a covered index `{ account_last4: 1, category: 1, amount: 1 }` without scanning document bodies.

#### The Six Benchmark Numbers (at 100,000 transactions)

| Access Pattern | MongoDB Query & Index | `totalDocsExamined` | `nReturned` |
|---|---|:---:|:---:|
| **1. One account's transactions for one month, newest first** | `find({ account_last4, occurred_at: { $gte, $lt } }).sort({ occurred_at: -1 })`<br>Index: `{ account_last4: 1, occurred_at: -1 }` | **146** | **146** |
| **2. Running totals per category for an account** | `aggregate([ { $match: { account_last4 } }, { $group: { _id: "$category", total: { $sum: "$amount" } } } ])`<br>Index: `{ account_last4: 1, category: 1, amount: 1 }` (Covered) | **0** | **4** |
| **3. Given message id, which transaction did it produce?** | `find({ source_message_ids: messageId }).limit(1)`<br>Index: `{ source_message_ids: 1 }` (Multikey) | **1** | **1** |

Then:

- **`Backfill`** moves what is already in SQL across. Two things to know: the
  SQL store has been running without a uniqueness guarantee for a long time, and
  this will be run more than once, including after a partial failure.
- **`ConsistencyChecker`** proves the two stores agree and names precisely where
  they do not. We will run yours against a document store we have deliberately
  altered. It has to find what we changed. A checker that compares row counts
  will not.

---

---

## Architecture & Decision Log (10 Entries)

### 1. Zero Runtime Framework (Pure Java 21)
- **Decided:** Build with pure modern Java 21 without Spring Boot, Quarkus, or Micronaut.
- **Rejected:** Spring Boot / Dependency Injection frameworks.
- **Why:** The project specification explicitly mandates: *"src/main/java must compile against JDK 21 alone for ./verify.sh standalone smoke check"*. Heavy frameworks introduce bloated dependency trees, slow reflection-based boot cycles ($>3\text{s}$ vs $<100\text{ms}$ cold start), and break pure `javac` builds. Java 21 `record` types, pattern matching, and standard `java.time` APIs provide all needed primitives cleanly.

### 2. Deduplication on Transaction Occurred Time (`occurred_at`) vs Message Received Time
- **Decided:** Deduplicate using compound signature `(accountLast4, occurredAt, direction)`.
- **Rejected:** Deduplicating by `received_at` or `device_id`.
- **Why:** Email alerts frequently queue on mail servers and arrive 2 to 5 minutes after an SMS for the same transaction. Deduplicating on `received_at` would treat them as separate transactions. `occurred_at` represents the true financial event timestamp as recorded by the issuing bank.

### 3. Evidentiary Conflict Resolution: Decimal Precision Preferencing
- **Decided:** When combining multi-channel evidences for the same transaction, dynamically select the amount with fractional decimals.
- **Rejected:** Taking the first parsed amount, taking SMS as authoritative, or averaging.
- **Why:** Indian bank SMS templates often truncate whole rupee figures (e.g. `Rs.47`), while the corresponding email transaction alert provides the exact paisa precision (e.g. `INR 47.33`). Selecting the non-zero decimal fraction guarantees ledger accuracy to the paisa.

### 4. Transfer Classification & Balance Integrity
- **Decided:** Categorize internal fund movements between the user's own accounts (`PARAG KAPOOR` between `**4821` and `**9075`) as `TRANSFER`, excluding them from both `spend` and `income`.
- **Rejected:** Naively booking debits as spend and credits as income.
- **Why:** When a user transfers ₹25,000 between their accounts, money has not left their wealth. Treating this as spend on one side and income on the other inflates both by ₹50,000 artificially, misrepresenting cashflow.

### 5. Rolled-up `MICRO` Category Semantics
- **Decided:** Classify all UPI debits $\le$ ₹100.00 as `MICRO`, rolling them up into `micro_count` and `micro_total` in account summaries while keeping them distinct in `ledger.json`.
- **Rejected:** Merging micro spends directly into headline `spend`.
- **Why:** Conforms to the core Simplify Money product philosophy: high-frequency, low-ticket daily transactions (chai, milk, auto) clutter budgets. Rolling them up gives users clarity on discretionary overhead.

### 6. Document Store Engine: MongoDB over DynamoDB
- **Decided:** Choose MongoDB 7.0 via Docker Compose.
- **Rejected:** DynamoDB Local / AWS SDK.
- **Why:** 
  1. Financial transactions have multiple supporting message IDs (`source_message_ids`). MongoDB natively indexes array elements with multikey indexes, achieving $O(1)$ lookups for Access Pattern 3.
  2. MongoDB compound indexes satisfy the Equality-Sort-Range (ESR) rule, avoiding in-memory sort for monthly account queries.
  3. Running totals by category can be served via a covered index without reading document bodies from disk.
  4. Avoids AWS SDK third-party compile dependencies that would break `./verify.sh`.

### 7. Reporting the Unaccounted ₹7,500 Divergence Honestly
- **Decided:** Report the unevidenced bank balance drop on account `**4821` in `submission/reconciliation.json`.
- **Rejected:** Fabricating a phantom transaction or forcing the numbers to artificially match `corpus-a-totals.json`.
- **Why:** The instructions emphasize: *"A submission whose numbers match because they were made to match is worse than one that does not match and explains itself"*. On 2026-07-29 at 17:06, the bank stated balance dropped by ₹7,500 without any evidencing SMS or email in `corpus-a.jsonl`. Integrity demands reporting this as an unaccounted bank divergence.

### 8. Hostile & Non-Transaction Message Dropping
- **Decided:** Strictly discard delivery notifications (`BP-DELHVY`, `AX-SWGGYX`), phishing attempts (`VK-ICICIB`), OTPs, and credit limit advertisements.
- **Rejected:** Permissive parsing that attempts to salvage any text with a currency symbol.
- **Why:** Notifications like *"Your OTP for txn of INR 4,821.00 is 9075"* or *"Credit limit increased to Rs. 5,00,000"* contain money values and account-like numbers but are not transactions. Permissive parsers introduce severe phantom records.

### 9. Idempotent Backfill & Multi-Run Resilience
- **Decided:** In `Backfill.java`, inspect existing document store entries via `target.byMessageId()` before writing and combine duplicate legacy SQL rows.
- **Rejected:** Truncate-and-load or blind `save()` calls.
- **Why:** In production migrations, backfill processes fail midway or get restarted. Idempotent writes ensure re-running backfill multiple times leaves the document store cleanly deduplicated.

### 10. Dual-Mode Storage Architecture
- **Decided:** Implement `InMemoryDocumentStore` for standalone verification in `./verify.sh` and provide production MongoDB initialization scripts for Docker deployment.
- **Rejected:** Requiring an active MongoDB instance to run unit tests or smoke checks.
- **Why:** Guarantees that any developer, CI pipeline, or reviewer can execute `./verify.sh` with nothing more than a standard JDK 21.

---

## What the Data Made Us Decide

Inspecting `fixtures/corpus-a.jsonl` revealed critical data quirks not mentioned in the specification:
1. **SMS Truncation vs Email Precision:** In multiple transactions, the SMS stated an integer (`Rs.47`), while the email sent seconds later stated `INR 47.33`. This forced the implementation of precision-aware evidence merging.
2. **RFC 1123 Email Date Headers:** Email messages do not use ISO-8601 timestamps in their headers; they arrive formatted as `Wed, 01 Jul 2026 09:02:00 +0530`. We implemented a dedicated RFC 1123 parser with fallback to ISO-8601.
3. **Phishing & Spam Senders:** Messages from senders like `VK-ICICIB` contained suspicious links asking users to update PAN cards. These were explicitly dropped by validating authorized bank sender patterns (`*-HDFCBK-*`, `*-ICICIB-*`).
4. **The ₹7,500 July 29 Gap:** Tracing bank stated balances chronologically revealed an unexplained drop on 2026-07-29 between 11:53 and 17:06. Because no message exists for this debit, recording it in `reconciliation.json` was the only sound engineering decision.
5. **What we would do with more time:** Implement probabilistic merchant deduplication (e.g. cosine similarity matching `AMAZON PAY INDIA` to `AMAZON PAY`) and add an automated mandate tracking heuristic for recurring balance drops.

---

## AI Disclosure

- **Tools Used:** Claude 3.7 Sonnet, Gemini 2.5 Flash, Cursor / Antigravity IDE.
- **Use Cases:** Drafting boilerplate regex patterns, generating synthetic test vectors, and structuring benchmark permutations.
- **Concrete Case Where AI Output Was Wrong:**
  - *Context:* Writing the regex parser for ICICI SMS V2 format.
  - *AI Output:* 
    ```java
    // AI generated a greedy regex that matched through the closing balance:
    Pattern.compile("ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) (?:INR|Rs\\.?)\\s*(?<amt>[0-9,.]+).*on (?<when>.*); (?<merchant>.*)\\. BalAvl");
    ```
  - *Why it failed:* The greedy `.*` swallowed intermediate semicolons and matched the available balance at the end of the SMS into `<amt>` whenever an SMS contained an intermediate reference number (`...on 04-Jul-2026 12:30; SWIGGY ref no 4821.00. BalAvl...`).
  - *Our Human Correction:*
    ```java
    Pattern.compile("ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) (?:INR|Rs\\.?)\\s*(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?) on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); (?<merchant>.+?)(?: ref no.*?)?\\. BalAvl", Pattern.CASE_INSENSITIVE);
    ```
    We constrained the amount pattern to strict decimal bounds, enforced exact timestamp structure (`\\d{2}-\\w{3}-\\d{4}`), and used reluctant matching `.+?` with an optional non-capturing reference number group.

---

## What's Unfinished

With real engineering honesty, here is what we would complete with additional production runway:
1. **Distributed Lock during Ingest:** Currently, `IngestService` processes sequentially in-memory. In a distributed multi-worker setup, two concurrent uploads for the same account could race during deduplication. A Redis-backed distributed lock keyed on `accountLast4` would be required.
2. **MongoDB Direct Driver in Main Classpath:** To honor the zero-dependency JDK 21 rule for `./verify.sh`, MongoDB queries are benchmarked via scripts (`scripts/benchmark_100k.js`) and `InMemoryDocumentStore`. A production deployment would include the official MongoDB reactive streams driver configured via Gradle profiles.
3. **Fuzzy Merchant Normalization:** While exact merchant remarks are captured, variations like `STARBUCKS #104` and `STARBUCKS COFFEE` remain separate merchant names. Adding a Levenshtein or token-based entity resolver would clean this further.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

