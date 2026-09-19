-- Fix INC-2026-09-11: Correct water can debit amount from 92213.10 to 5.00
UPDATE ledger
SET amount = 5.00
WHERE source_message_ids = 'm-legacy-0041' AND amount = 92213.10;
