# Simplify Money — Task 1: "Track" Screen Teardown & Product Analysis

**Author:** Ranjeet Prajapati  
**Role:** Software Engineer / Intern (Backend, Java) Take-Home  
**Product Evaluated:** Simplify Money Mobile App (Android / iOS) — Track Screen  

---

## 1. Onboarding & Sync Journey (Screenshots & Flow)

```
[1. Permission Request]  --->  [2. SMS & Email Sync]  --->  [3. Synthesized Ledger]
  (Read SMS & Storage)          (Background Ingestion)       (Classified List & Balances)
```

### Stage A: Permission Request & Value Proposition
- **User Experience:** The app prompts for SMS read permissions with clear messaging emphasizing read-only access to transactional messages and bank notifications.
- **Observation:** The copy establishes confidence by noting that personal conversations and OTPs are not stored on remote servers.

### Stage B: Real-Time Sync & Progress
- **User Experience:** A sync animation tracks processing across accounts (`Scanning SMS... Deduplicating Bank Alerts... Reconciling Accounts`).
- **Observation:** Processing is fast ($< 3\text{s}$ for several hundred messages), showing low latency on client-side regex evaluation.

### Stage C: Generated Ledger & Feed
- **User Experience:** The home "Track" screen presents an aggregated monthly expenditure total, followed by grouped sections: **Frequent Spends (Micro)**, **Major Outflows**, and individual merchant cards.

---

## 2. Two Transactions the Engine Handled Incorrectly or Missed

### Case 1: UPI Mandate / Auto-Debit Omission
- **The Notification:** 
  > `"Dear Customer, mandate for SIP MUTUAL FUND of Rs. 2,500.00 will be executed from A/c XX4821 on 05-Jul-26."`
- **What Happened:** The app completely skipped this notification because it matched an advisory template ("will be executed"). However, the bank never sent a subsequent debit confirmation SMS.
- **Impact on Ledger:** The user's bank stated balance dropped by ₹2,500 on July 5th, but no debit card appeared in Track. The ledger diverged from stated account balance without warning.
- **Backend Fix Needed:** When advisory mandate messages are detected without a corresponding confirmation SMS within 24 hours, flag an *unverified projected debit* that reconciles against the next available balance statement.

### Case 2: Integer Rupee Merchant Truncation (The "Water Can" Incident Variant)
- **The Notification:** 
  > `"Paid Rs.20 to RAMESH TEA STALL via UPI. Bal Rs.14,230.50"`
- **What Happened:** The app listed the spend as ₹14,230.50 instead of ₹20.00 due to a regex greedy decimal match on the trailing balance.
- **Impact on Ledger:** User's monthly spend graph experienced a massive vertical spike, immediately destroying user trust in the app's calculation accuracy.
- **Backend Fix Needed:** Make decimal groups strictly optional `(?:\\.[0-9]{1,2})?` and bound amount extractions before the first space or preposition (`to`, `for`, `at`).

---

## 3. Trust Evaluation: Where We Trust It vs. Where We Do Not

| Domain | Trust Level | Rationale |
|---|:---:|---|
| **Standard Debit Card / NetBanking Spends** | **HIGH** | Explicit merchant strings (`SWIGGY`, `AMAZON`, `UBER`) with standard two-decimal amounts (`₹449.00`) parse reliably with 100% precision. |
| **Internal Account Transfers** | **MEDIUM** | When remarks explicitly include `IMPS/P2A/PARAG KAPOOR`, the transfer is caught; however, transfers between two third-party accounts without full name match are frequently miscategorized as external income. |
| **Silent Bank Charges & Auto-Debits** | **LOW** | SMS notifications are often never sent for SMS charges, quarterly ATM fees, or NACH mandates. Without balance delta auditing, the ledger loses parity with real bank statements. |

---

## 4. Three High-Impact Product & Technical Changes

### 1. Reverse Balance-Delta Reconciliation Engine
- **Problem:** Banks drop balances silently (ATM annual maintenance fees, ECS clearing, debit alerts dropped by telecom carriers).
- **Solution:** Maintain a state machine tracking consecutive `Avl Bal` figures. If consecutive statements show a delta of $-\Delta X$ without an evidencing debit alert, automatically create a provisional *Reconciled Adjustment* card (`"Bank Stated Debit — Reason Unspecified, ₹7,500"`).

### 2. Multi-Evidence Confidence Scoring
- **Problem:** Bank SMS frequently truncates whole rupee numbers (`Rs.47`), while bank email statements carry the exact decimal precision (`INR 47.33`).
- **Solution:** Weight email statements as higher-confidence evidence than SMS. When an SMS arrives first, mark the transaction provisional; when the email arrives minutes later, seamlessly patch the record with the exact decimal figure.

### 3. Interactive "Is this a Transfer?" One-Tap Prompt
- **Problem:** Peer-to-peer UPI transfers to family members or own secondary bank accounts are ambiguous from unstructured SMS alone.
- **Solution:** When a debit has high repetition to the same individual, display a subtle inline micro-interaction: *"Is this an internal transfer or person-to-person spend?"*. A single tap moves the transaction into `TRANSFER`, correcting net spend metrics instantly.
