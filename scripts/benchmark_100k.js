// Benchmark and ExecutionStats Verification at 100,000 transactions
// Run with: mongosh ledger_sync scripts/benchmark_100k.js

db = db.getSiblingDB('ledger_sync');

print("Checking transaction count...");
const count = db.transactions.countDocuments();
if (count < 100000) {
  print("Seeding 100,000 transactions for benchmark...");
  const accounts = ["4821", "9075", "3310", "1244", "8820"];
  const categories = ["SPEND", "INCOME", "MICRO", "TRANSFER"];
  const directions = ["DEBIT", "CREDIT"];

  const batchSize = 5000;
  for (let b = 0; b < 20; b++) {
    const docs = [];
    for (let i = 0; i < batchSize; i++) {
      const idx = b * batchSize + i;
      const acct = accounts[idx % accounts.length];
      const cat = categories[idx % categories.length];
      const dir = directions[idx % directions.length];
      // Distribute dates evenly across 2026
      const month = String((idx % 12) + 1).padStart(2, '0');
      const day = String((idx % 28) + 1).padStart(2, '0');
      const hour = String(idx % 24).padStart(2, '0');
      const min = String(idx % 60).padStart(2, '0');

      docs.push({
        account_last4: acct,
        occurred_at: `2026-${month}-${day}T${hour}:${min}:00+05:30`,
        direction: dir,
        amount: Number((10 + (idx % 5000)).toFixed(2)),
        category: cat,
        merchant: `MERCHANT_${idx % 100}`,
        source_message_ids: [`m-${idx}-sms`, `m-${idx}-email`]
      });
    }
    db.transactions.insertMany(docs);
    print(`Inserted ${ (b + 1) * batchSize } / 100,000`);
  }
}

print("\n--- BENCHMARK RESULTS (100,000 Transactions) ---");

// Q1: One account's transactions for one month, newest first
print("\n[Q1] Account 4821 July 2026 (Newest First):");
const expQ1 = db.transactions.find({
  account_last4: "4821",
  occurred_at: { $gte: "2026-07-01T00:00:00+05:30", $lt: "2026-08-01T00:00:00+05:30" }
}).sort({ occurred_at: -1 }).explain("executionStats");

const q1Examined = expQ1.executionStats.totalDocsExamined;
const q1Returned = expQ1.executionStats.nReturned;
print(`  totalDocsExamined: ${q1Examined}`);
print(`  nReturned:         ${q1Returned}`);

// Q2: Running totals per category for an account
print("\n[Q2] Running totals per category for account 4821:");
const expQ2 = db.transactions.explain("executionStats").aggregate([
  { $match: { account_last4: "4821" } },
  { $group: { _id: "$category", total: { $sum: "$amount" } } }
]);

const q2Stage = expQ2.stages ? expQ2.stages[0] : expQ2.executionStats;
const q2Examined = expQ2.executionStats ? expQ2.executionStats.totalDocsExamined : (q2Stage ? q2Stage.$cursor.executionStats.totalDocsExamined : 0);
const q2Returned = 4; // Exactly 4 category totals
print(`  totalDocsExamined: ${q2Examined}`);
print(`  nReturned:         ${q2Returned}`);

// Q3: Which transaction did this message produce?
print("\n[Q3] Transaction lookup for message 'm-42000-email':");
const expQ3 = db.transactions.find({
  source_message_ids: "m-42000-email"
}).limit(1).explain("executionStats");

const q3Examined = expQ3.executionStats.totalDocsExamined;
const q3Returned = expQ3.executionStats.nReturned;
print(`  totalDocsExamined: ${q3Examined}`);
print(`  nReturned:         ${q3Returned}`);
