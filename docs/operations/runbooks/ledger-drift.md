# Runbook: ledger drift detected

**Severity:** SEV1 — the books disagree with themselves. Money is not necessarily lost, but
nothing the ledger reports can be trusted until this is resolved.
**Owner:** platform_ops
**Related:** [ADR-0008](../../adr/0008-double-entry-projection.md) · [ADR-0016](../../adr/0016-separate-migration-and-application-roles.md) · `BalanceVerifier`

---

## Signal

`LEDGER INTEGRITY FAILURE` in the ledger service log, or a non-empty `drifts` or
`currency_imbalances` array from:

```bash
curl -sS -X POST localhost:8083/ops/ledger/verify -H "Authorization: Bearer $LEDGER_OPS_TOKEN" | jq .
```

The verifier runs every five minutes and compares two independently derived numbers: the
materialised `account_balance` rows, and the same balances recomputed from raw postings.

## What it means

There are two distinct failures, and they are not equally bad.

**`drifts` non-empty** — a materialised balance disagrees with the postings beneath it. The
postings are the source of truth and are append-only, so **the money record itself is
intact**; a cached number is wrong. Merchant-facing balances are unreliable until fixed.

**`currency_imbalances` non-empty** — the sum of every posting in a currency is not zero.
This should be impossible: a deferred constraint trigger rejects any transaction that does
not balance. A non-zero result here means the constraint was bypassed, which is a far more
serious situation than drift.

## Diagnose

1. **Establish which failure it is.** The `verify` response separates them. If
   `currency_imbalances` is non-empty, skip to *Escalate* — do not attempt a repair.

2. **Find the affected accounts.** Each drift reports the account, the stored balance, the
   recomputed balance and the difference:

   ```bash
   curl -sS -X POST localhost:8083/ops/ledger/verify \
     -H "Authorization: Bearer $LEDGER_OPS_TOKEN" | jq '.drifts[]'
   ```

3. **Check whether the constraint trigger is still installed.** Its absence explains an
   imbalance and nothing else will:

   ```sql
   SELECT tgname, tgenabled FROM pg_trigger
    WHERE tgname IN ('posting_transaction_balances', 'posting_is_append_only');
   ```
   Both must be present and enabled (`O`).

4. **Confirm the application role still lacks write privileges on postings.** If it has
   acquired `UPDATE` or `DELETE`, the append-only guarantee is gone and postings may have
   been altered:

   ```sql
   SELECT privilege_type FROM information_schema.role_table_grants
    WHERE grantee = 'maestro_ledger' AND table_name = 'posting';
   ```
   Expect exactly `SELECT` and `INSERT`.

5. **Correlate with recent deployments.** Drift that begins at a deployment points at the
   balance-update path in `LedgerRepository`; drift with no deployment points at
   infrastructure — a failover mid-transaction, or a restore.

## Likely causes

| Cause | Confirms it | Rules it out |
|---|---|---|
| Defect in the materialised balance update | Drift confined to accounts touched since a recent deploy; postings themselves consistent | Drift on accounts with no recent postings |
| Partial restore or failover | Drift begins at a known infrastructure event; `posting_count` disagrees too | Clean infrastructure timeline |
| Constraint trigger dropped or disabled | Step 3 returns nothing, and imbalances are present | Both triggers present and enabled |
| Postings altered directly | Step 4 shows unexpected privileges | Grants are exactly `SELECT, INSERT` |

## Remedy

**For drift only** — the materialised balances are a cache of the postings, so rebuilding
them from the postings is safe and non-destructive:

```sql
BEGIN;
UPDATE ledger.account_balance b
   SET balance_minor = recomputed.balance_minor,
       posting_count = recomputed.posting_count,
       updated_at    = now()
  FROM (SELECT account_id,
               SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END)
                   AS balance_minor,
               count(*) AS posting_count
          FROM ledger.posting GROUP BY account_id) AS recomputed
 WHERE b.account_id = recomputed.account_id;
COMMIT;
```

Then re-run `/ops/ledger/verify` and confirm it comes back clean.

**For a currency imbalance** — do not repair. Escalate.

## Do not

- **Do not adjust a posting.** They are append-only for a reason, and an edit destroys the
  evidence needed to work out what happened. A genuine correction is a reversing journal
  transaction.
- **Do not "fix" a balance to the number you expect.** The recomputation above derives it
  from the postings; typing a figure in by hand converts a detectable inconsistency into an
  undetectable one.
- **Do not disable the verification job to stop the alerts.** It is the only thing standing
  between a small inconsistency and a large one.
- **Do not restart the ledger service expecting it to help.** Balances are persisted; a
  restart changes nothing and delays diagnosis.

## Verify

`/ops/ledger/verify` returns `clean: true` with `accounts_checked` matching the number of
accounts, and stays clean across two subsequent scheduled runs (ten minutes).

## Escalate

Immediately, for any currency imbalance. Capture before touching anything: the full
`verify` output, the trigger and privilege queries from steps 3 and 4, and the output of

```sql
SELECT t.id, t.source_event_id, t.transaction_type,
       SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) AS imbalance
  FROM ledger.journal_transaction t JOIN ledger.posting p ON p.transaction_id = t.id
 GROUP BY t.id HAVING SUM(CASE WHEN p.direction = 'DEBIT'
                               THEN p.amount_minor ELSE -p.amount_minor END) <> 0;
```

which identifies the individual transactions that do not balance.

## Follow up

Any occurrence warrants an incident note. A currency imbalance warrants an ADR: the
database-level guarantee failed, and the design needs to change rather than the code.
