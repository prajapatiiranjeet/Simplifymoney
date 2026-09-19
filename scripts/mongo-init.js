// MongoDB Initialization & Index Setup for Simplify Money Ledger Sync

db = db.getSiblingDB('ledger_sync');

// Ensure transactions collection exists
db.createCollection('transactions');

// 1. Index for Access Pattern 1: One account's transactions for one month, newest first
// Follows the Equality-Sort-Range (ESR) rule. Eliminates in-memory sorting.
db.transactions.createIndex(
  { account_last4: 1, occurred_at: -1 },
  { name: 'idx_account_occurred_desc' }
);

// 2. Index for Access Pattern 2: Running totals per category for an account
// Covered index supporting aggregation without reading document bodies
db.transactions.createIndex(
  { account_last4: 1, category: 1, amount: 1 },
  { name: 'idx_account_category_amount' }
);

// 3. Index for Access Pattern 3: Which transaction did a message produce
// Multikey index allowing O(1) direct lookup over source_message_ids array
db.transactions.createIndex(
  { source_message_ids: 1 },
  { name: 'idx_source_message_ids' }
);

print('Simplify Money MongoDB indexes created successfully:');
printjson(db.transactions.getIndexes());
