## 1. Database — Locking

Locking is how a database prevents concurrent transactions from corrupting each other's work. Every time you read or write a row, the DB acquires one or more locks behind the scenes. Understanding what locks exist, when they are acquired, and how long they are held is essential for designing a payment system that is both correct and performant under concurrent load.

---

### Shared Lock (S) — Read Lock

A shared lock is acquired when a transaction reads a row and wants to prevent others from modifying it during that read. Multiple transactions can hold a shared lock on the same row simultaneously — they are all reading, so they don't block each other. However, a shared lock blocks any transaction that wants to acquire an exclusive lock (i.e. modify the row).

In PostgreSQL, you explicitly request a shared lock with:
```sql
SELECT * FROM accounts WHERE id = 1 FOR SHARE;
```
The row is locked until the transaction commits or rolls back. Another session can also `SELECT ... FOR SHARE` the same row — both coexist. But a `SELECT ... FOR UPDATE` from another session will block until the first transaction releases its shared lock.

**When to use**: when you need to read a value and ensure nobody modifies it while you are making a decision based on it, but you don't need to modify it yourself. Example: reading an account's KYC status before allowing a transfer, where you want to ensure another process doesn't change the KYC status mid-flight.

---

### Exclusive Lock (X) — Write Lock

An exclusive lock is acquired when a transaction intends to modify a row. It blocks all other transactions from reading or writing the same row — no other shared or exclusive locks are allowed while an exclusive lock is held.

Acquired automatically on `UPDATE` and `DELETE`. Explicitly requested with:
```sql
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
```

This is the most important lock in a payment system. The pattern for a safe transfer is:

```sql
BEGIN;
SELECT balance FROM accounts WHERE id = :from_id FOR UPDATE;  -- acquires X lock
SELECT balance FROM accounts WHERE id = :to_id   FOR UPDATE;  -- acquires X lock
-- Now no other transaction can touch either row until this transaction ends
UPDATE accounts SET balance = balance - :amount WHERE id = :from_id;
UPDATE accounts SET balance = balance + :amount WHERE id = :to_id;
COMMIT;
```

The `FOR UPDATE` ensures that between reading the balance and writing the new balance, no other transaction can read-and-update the same row. Without it, two concurrent withdrawals could both read the same balance, both decide there are sufficient funds, and both write back — leaving the account overdrawn.

**`FOR UPDATE NOWAIT`**: if the row is already locked, fail immediately instead of waiting. Returns an error. Useful when you want to fail fast rather than queue behind a slow transaction.

**`FOR UPDATE SKIP LOCKED`**: skip any rows already locked by another transaction and return only the available ones. This is the correct pattern for a job queue with competing consumers — each consumer grabs a different unlocked row, processes it, and the others are automatically skipped.

```sql
SELECT id, payload FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

Without `SKIP LOCKED`, all consumers would pile up waiting for the same row. With it, each consumer independently picks a different row.

---

### Row-Level Lock

Row-level locking is what PostgreSQL does by default for `UPDATE`, `DELETE`, and `SELECT FOR UPDATE/SHARE`. It locks only the specific rows being accessed, not the entire table or page. This is the most granular locking strategy — two transactions can modify different rows in the same table simultaneously.

Row-level locks are held in memory and tracked in the row's tuple header (the `xmax` field in PostgreSQL's heap storage). They are released at transaction end.

**Why this matters**: in a payments table with millions of rows, row-level locks mean thousands of transfers can proceed concurrently as long as they touch different accounts. A table-level lock would make the entire database single-threaded.

---

### Table-Level Lock

Locks the entire table. No other transaction can read or write any row in the table while a table lock is held.

Acquired automatically by DDL statements (`ALTER TABLE`, `DROP TABLE`, `TRUNCATE`). Can be acquired explicitly:
```sql
LOCK TABLE accounts IN EXCLUSIVE MODE;
```

**In production, DDL on a live table is dangerous** — `ALTER TABLE` acquires an `ACCESS EXCLUSIVE` lock that blocks all reads and writes. A migration that adds a column with a default value (pre-PostgreSQL 11) would lock the table for the entire duration of the backfill. This is why:
- Use `ALTER TABLE ... ADD COLUMN col TYPE DEFAULT NULL` (no lock) and backfill separately.
- Use `CREATE INDEX CONCURRENTLY` — builds the index without holding a table lock.

PostgreSQL has multiple table lock modes with different compatibility rules. The most common you encounter in practice:
- `ACCESS SHARE` — acquired by `SELECT`. Compatible with almost everything.
- `ROW EXCLUSIVE` — acquired by `INSERT`, `UPDATE`, `DELETE`. Compatible with reads but not DDL.
- `ACCESS EXCLUSIVE` — acquired by `ALTER TABLE`, `DROP`, `TRUNCATE`. Blocks everything.

---

### Page-Level Lock

Some databases (SQL Server, older MySQL engines) use page-level locks as an intermediate granularity — locking a disk page (typically 8KB) containing multiple rows. This is a compromise between row-level (high overhead per row) and table-level (kills concurrency).

PostgreSQL does not use page-level locks for row data. It uses row-level locks almost exclusively, with table-level locks only for DDL. You will not encounter page locks in PostgreSQL day-to-day.

---

### Advisory Lock

An advisory lock is a user-defined lock with no built-in DB semantics — it's just a lock token stored in shared memory. The application decides what it means.

```sql
-- Try to acquire — returns true if acquired, false if already held
SELECT pg_try_advisory_lock(12345);

-- Blocking acquire
SELECT pg_advisory_lock(12345);

-- Release
SELECT pg_advisory_unlock(12345);
```

The lock key is a 64-bit integer (or two 32-bit integers). You derive the key from something meaningful — e.g. `hashtext('process-daily-settlement')` or a user_id.

**Use case in FinTech**: ensuring only one instance of a scheduled job runs at a time across a cluster. All instances try `pg_try_advisory_lock(job_id)` at startup — only one succeeds and runs the job, the others skip. When the job finishes, the lock is released. This is simpler and more reliable than a `RUNNING` flag in the DB, which can get stuck if the process crashes.

**Session vs transaction advisory locks**: session-level advisory locks persist until explicitly released or the session ends. Transaction-level advisory locks are released at transaction end — useful when you want the lock tied to a DB transaction's lifecycle.

---

### Predicate Lock (SERIALIZABLE isolation)

Predicate locks are invisible to the application and are used internally by PostgreSQL's SERIALIZABLE isolation level (SSI — Serializable Snapshot Isolation). They lock not just rows that exist, but the *predicate* (the WHERE condition), preventing other transactions from inserting rows that would have matched the predicate.

Example: transaction A reads `SELECT * FROM transfers WHERE user_id = 5 AND status = 'PENDING'` under SERIALIZABLE. A predicate lock is placed on the range `user_id=5, status=PENDING`. If transaction B inserts a new row matching that predicate, PostgreSQL detects the conflict and aborts one of the transactions.

This prevents phantom reads — a scenario where a transaction re-reads a range and finds new rows inserted by a concurrent transaction.

**You don't manage predicate locks directly.** They are a consequence of using SERIALIZABLE isolation. The trade-off: SERIALIZABLE prevents all anomalies but increases transaction abort rate under high concurrency, requiring retry logic in the application.

---

### Deadlock

A deadlock occurs when two (or more) transactions are each waiting for a lock held by the other, forming a cycle with no resolution. Neither can proceed.

**Example with a payment transfer**:
- Transaction 1: transfers £100 from account A to account B. Locks A, then tries to lock B.
- Transaction 2: transfers £50 from account B to account A. Locks B, then tries to lock A.
- Transaction 1 holds A and waits for B. Transaction 2 holds B and waits for A. Neither can proceed.

PostgreSQL detects deadlocks automatically via a background cycle-detection algorithm. When detected, it aborts one of the transactions (the one that has done less work) with:
```
ERROR: deadlock detected
DETAIL: Process 1234 waits for ShareLock on transaction 5678; blocked by process 5678.
```

The aborted transaction must be retried by the application.

**How to avoid deadlocks — deterministic lock ordering**: always acquire locks on multiple rows in the same consistent order across all transactions. For transfers, always lock the account with the lower ID first:

```java
long firstId  = Math.min(fromAccountId, toAccountId);
long secondId = Math.max(fromAccountId, toAccountId);

// In SQL:
// SELECT ... FROM accounts WHERE id = firstId  FOR UPDATE;
// SELECT ... FROM accounts WHERE id = secondId FOR UPDATE;
```

Now Transaction 1 (A→B) and Transaction 2 (B→A) both try to lock the lower ID first. One of them acquires it; the other waits. No cycle — no deadlock.

**Other avoidance strategies**:
- Keep transactions short — the less time locks are held, the smaller the deadlock window.
- Use `SELECT FOR UPDATE NOWAIT` or `tryLock` with timeout and retry with exponential backoff rather than blocking indefinitely.
- Use optimistic locking (version column) for low-contention scenarios — no locks acquired at read time, conflict detected only at write time.

---

### Optimistic vs Pessimistic Locking

**Pessimistic locking** assumes conflict is likely. Lock the row at read time and hold the lock until the transaction completes. Guarantees no conflict but reduces concurrency — other transactions block.

```sql
-- Pessimistic: lock immediately
SELECT balance, version FROM accounts WHERE id = 1 FOR UPDATE;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;
```

Use when: high contention on the same rows (e.g. a shared account with many concurrent transfers), or when the cost of a rollback is high.

**Optimistic locking** assumes conflict is rare. Read the row without locking, do the work, then write with a check that the row hasn't changed since you read it. If it has changed (another transaction got there first), the update affects 0 rows — detect this and retry.

```sql
-- Optimistic: read without lock
SELECT balance, version FROM accounts WHERE id = 1;
-- version = 7

-- Later, at write time:
UPDATE accounts
SET balance = balance - 100, version = version + 1
WHERE id = 1 AND version = 7;
-- If this updates 0 rows, someone else modified it first — retry the whole operation
```

In Java with raw JDBC:
```java
int rowsUpdated = ps.executeUpdate();
if (rowsUpdated == 0) {
    throw new OptimisticLockException("Account modified concurrently, retry");
}
```

Use when: low to medium contention, reads vastly outnumber writes, retry cost is low.

**Staff-level insight**: in a payment system, use pessimistic locking (`SELECT FOR UPDATE`) for balance mutations — the cost of an overdraft or double-spend far exceeds the cost of reduced concurrency. Use optimistic locking for lower-stakes updates like profile fields, notification preferences, or configuration — where contention is rare and a retry is cheap.

---

## 2. Database — Scaling Reads and Writes

A single PostgreSQL primary handles reads and writes up to a point — typically several thousand transactions per second on well-tuned hardware. Beyond that, you need a deliberate strategy. Reads and writes scale differently, and conflating them leads to bad architecture decisions.

---

### Scaling Reads

**Read Replicas (Streaming Replication)**

PostgreSQL supports streaming replication — the primary continuously ships WAL (Write-Ahead Log) records to one or more standby replicas. Replicas apply these records and maintain an up-to-date copy of the data. Application reads can be routed to replicas, offloading the primary.

The critical constraint is **replication lag** — replicas apply WAL asynchronously, so they may be milliseconds to seconds behind the primary. This means:
- Safe to read from replica: exchange rates, product catalogue, historical transaction list, analytics queries.
- Unsafe to read from replica: account balance before a withdrawal (you might read a stale balance and allow an overdraft), idempotency key checks (you might miss a recently inserted key and process a duplicate).

The routing decision must be explicit. A common pattern is to have two DataSource instances in the application — one pointing to the primary (for writes and consistency-sensitive reads) and one pointing to a replica pool (for everything else). Do not blindly route all reads to replicas.

**Connection Pooling (PgBouncer)**

Every PostgreSQL connection is a separate OS process consuming approximately 5–10 MB of RAM and a file descriptor. A primary instance can comfortably handle 100–500 connections before connection overhead dominates. But a typical microservice deployment might have 50 pods, each with a thread pool of 200 threads — that's 10,000 connections attempting to hit one database.

PgBouncer sits between the application and PostgreSQL and multiplexes many application connections onto a small pool of real DB connections. In **transaction mode** (the most efficient), a DB connection is assigned to an application only for the duration of a single transaction, then returned to the pool. This reduces actual PostgreSQL connections from thousands to tens.

The gotcha: in transaction mode, session-level features do not work — `SET LOCAL`, prepared statements, `LISTEN/NOTIFY`, advisory session locks behave incorrectly because the underlying connection changes between transactions. Design your application to be stateless across connections.

**Caching (Redis / Memcached)**

Hot reads that are expensive to compute or hit the DB repeatedly should be cached. The canonical pattern is **cache-aside**:
1. Application reads from cache.
2. Cache miss → read from DB → write result to cache with a TTL.
3. On cache hit → return cached value directly.

On writes, either invalidate the cache entry (simpler, brief inconsistency window) or update it (complex, risk of stale writes). For financial data, always prefer invalidate — a slightly stale exchange rate display is acceptable; a stale balance that prevents a valid transfer is not.

What to cache: exchange rates (updated every few seconds, read millions of times), currency metadata, account limits and tiers, fee schedules, fraud rule thresholds. What not to cache: account balances (must be authoritative), idempotency keys, anything that informs a financial decision.

**CQRS Read Model**

For complex query patterns — dashboards, search, aggregations — maintaining a separate read model that is optimised for those queries avoids hammering the normalised transactional DB with expensive joins. The write model (normalised, strongly consistent) emits events; a projector consumes those events and maintains the read model (denormalised, indexed for query patterns, eventually consistent).

The read model is disposable — if it gets corrupted or you need a new query pattern, replay all events from Kafka and rebuild it. This is the key architectural insight: **the event log is the source of truth; the read model is a derived cache**.

---

### Scaling Writes

**Vertical Scaling**

The first move — larger CPU, more RAM, faster NVMe storage. PostgreSQL is single-primary, so vertical scaling buys significant headroom before you need to architect around it. A well-tuned `c5.4xlarge` (16 vCPU, 32 GB RAM) with NVMe storage handles tens of thousands of transactions per second for typical OLTP workloads. Do this before sharding — sharding is expensive complexity.

**Write Batching**

Instead of issuing one `INSERT` per event, accumulate events in memory and flush in a single bulk `INSERT`:
```sql
INSERT INTO ledger_entries (id, account_id, amount, type, created_at)
VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?), ...  -- hundreds of rows at once
```
PostgreSQL's throughput for batched inserts is dramatically higher than single-row inserts — one round-trip, one WAL flush, one index update cycle. The risk is data loss on crash if you batch in memory without persistence. Mitigate with the Outbox pattern — write to the outbox transactionally, batch-consume from there.

**Async Writes via Queue**

For write spikes, decouple the write path: the API endpoint writes to Kafka (fast, in-memory, durable), returns `202 Accepted` immediately, and a consumer processes the event and writes to the DB at its own pace. This absorbs burst write load at the cost of consistency — the DB is eventually consistent with what the API accepted.

This is appropriate for audit logs, notification events, analytics events, and secondary data. It is **not** appropriate for the primary payment record — a user who just made a transfer must be able to see it immediately. The core transfer must be synchronously committed to the DB.

**Table Partitioning**

PostgreSQL native partitioning splits a logical table into physical partitions based on a partition key, all within one database instance. Unlike sharding, queries still go through the single primary — partitioning helps with:
- Pruning: `WHERE created_at BETWEEN x AND y` only scans the relevant monthly partition.
- Maintenance: `VACUUM`, `ANALYZE`, index rebuilds run per partition (smaller, faster).
- Archival: detach and archive old partitions without touching live data.

A `transactions` table partitioned by month: each month is a separate physical table. Queries for recent transactions only touch one or two partitions. `pg_partman` automates partition creation.

```sql
CREATE TABLE transactions (
    id UUID,
    account_id BIGINT,
    amount NUMERIC,
    created_at TIMESTAMPTZ
) PARTITION BY RANGE (created_at);

CREATE TABLE transactions_2026_08
    PARTITION OF transactions
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
```

**Sharding**

Sharding splits rows across multiple independent database instances. Each shard is a separate PostgreSQL primary. A shard router (in the application layer or a middleware like Citus) determines which shard owns a given row based on the shard key.

Sharding solves write throughput limits — each shard handles a fraction of the total write load. The costs are severe:
- Cross-shard queries require scatter-gather: query all shards, merge results in the application. Slow and complex.
- Cross-shard transactions require distributed transactions or sagas — no native ACID.
- Re-sharding (adding more shards) requires migrating data and is operationally painful.

**Shard key choice is critical**: shard on `user_id` for a payments system — all of a user's accounts and transfers are co-located on one shard. But be aware of hot shards — a single high-volume user (a business account processing millions of transactions) overloads one shard. Mitigate with consistent hashing and virtual nodes, or by detecting hot keys and splitting them across shards at a sub-user granularity.

**Append-Only Ledger**

The most important write-scaling pattern for FinTech: never `UPDATE` a financial record. Every financial event is an immutable `INSERT` into a ledger table. Balance is derived from the ledger.

```sql
CREATE TABLE ledger_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  BIGINT NOT NULL,
    amount      NUMERIC(19,4) NOT NULL,  -- positive = credit, negative = debit
    entry_type  TEXT NOT NULL,           -- TRANSFER_OUT, TRANSFER_IN, FEE, etc.
    reference   UUID NOT NULL,           -- transfer_id or other business reference
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Balance computation:
```sql
SELECT SUM(amount) FROM ledger_entries WHERE account_id = :id;
```

For performance, maintain a materialised balance in `accounts.balance`, updated atomically with each ledger insert in the same transaction. The ledger is the source of truth; the materialised balance is a cache that can be rebuilt by replaying the ledger.

**Why append-only scales**: `INSERT` acquires no locks on existing rows — multiple concurrent transfers to different accounts insert without contention. `UPDATE accounts SET balance = ...` requires a row lock on the account row for the entire transaction. With high-volume accounts (shared wallets, escrow accounts), that lock becomes a serialisation bottleneck. Append-only eliminates it.

**Staff-level insight**: sharding should be your last resort, not your first instinct. In practice, most payment systems reach enormous scale on a single well-tuned PostgreSQL primary with read replicas, connection pooling, partitioning, and an append-only ledger. Shopify runs their core on a single MySQL primary sharded only at the tenant level. Start vertical, then add replicas, then partition. Shard only when you have exhausted all other options and have concrete throughput measurements proving you need it.

---

## 3. Database — Isolation Levels

Isolation levels define how much one transaction can see of another concurrent transaction's in-progress or committed changes. The SQL standard defines four levels, each protecting against a specific class of anomaly. PostgreSQL implements them, but with stronger-than-standard behaviour at the higher levels due to its MVCC architecture.

Understanding isolation levels is not about memorising a table — it is about understanding what can go wrong in concurrent systems and choosing the right trade-off between correctness and throughput.

---

### The Anomalies You Are Protecting Against

**Dirty Read**: Transaction A reads a row that Transaction B has modified but not yet committed. If B rolls back, A has read data that never existed. This is catastrophic in payments — you might see a balance that gets rolled back.

**Non-Repeatable Read**: Transaction A reads a row. Transaction B commits a change to that row. Transaction A reads the same row again and sees a different value. Within a single transaction, the same query returns different results. This is a problem for any multi-step business logic that reads a value twice and expects consistency.

**Phantom Read**: Transaction A runs a range query (e.g. `SELECT * FROM transfers WHERE amount > 1000`). Transaction B inserts a new row matching that range and commits. Transaction A runs the same query again and sees the new row that wasn't there before. The set of matching rows changed mid-transaction.

**Lost Update**: Transaction A and Transaction B both read a value, both compute a new value based on it, and both write back. B's write overwrites A's — A's update is silently lost. Classic race condition on a balance field.

---

### The Four Levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Lost Update |
|-------|-----------|---------------------|--------------|-------------|
| READ UNCOMMITTED | Possible | Possible | Possible | Possible |
| READ COMMITTED | Prevented | Possible | Possible | Possible |
| REPEATABLE READ | Prevented | Prevented | Possible (not in PG) | Prevented in PG |
| SERIALIZABLE | Prevented | Prevented | Prevented | Prevented |

---

### READ UNCOMMITTED

Allows dirty reads — a transaction can read uncommitted changes from another transaction. Not used in practice for any serious workload. PostgreSQL does not actually implement READ UNCOMMITTED — it silently upgrades it to READ COMMITTED.

---

### READ COMMITTED (PostgreSQL default)

Each statement within a transaction sees a fresh snapshot of committed data at the time that statement starts. You never read uncommitted data. But if you run the same `SELECT` twice within a transaction, the second execution may see rows committed by other transactions between the two reads.

```sql
BEGIN; -- isolation level: READ COMMITTED
SELECT balance FROM accounts WHERE id = 1; -- returns 1000
-- Another transaction commits: UPDATE accounts SET balance = 800 WHERE id = 1;
SELECT balance FROM accounts WHERE id = 1; -- returns 800 (different result!)
COMMIT;
```

This is acceptable for most reads. It is **not acceptable** for a check-then-act pattern without locking:

```sql
-- WRONG — race condition under READ COMMITTED
BEGIN;
SELECT balance FROM accounts WHERE id = 1; -- reads 500
-- concurrent transaction also reads 500 and proceeds
IF balance >= 200 THEN
    UPDATE accounts SET balance = balance - 200 WHERE id = 1;
END IF;
COMMIT;
```

Two concurrent transactions both read 500, both pass the balance check, both subtract 200 — account ends at 100 instead of 300. The fix is `SELECT FOR UPDATE` which acquires an exclusive row lock at read time, serialising the two transactions.

**When to use**: the default for all OLTP queries. Pair with `SELECT FOR UPDATE` wherever you need a consistent read-then-write.

---

### REPEATABLE READ

A transaction takes a snapshot of the entire database at the moment it starts (`BEGIN`). All reads within the transaction see data as of that snapshot, regardless of what other transactions commit in the meantime. This prevents non-repeatable reads.

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = 1; -- returns 1000
-- Another transaction commits: UPDATE accounts SET balance = 800 WHERE id = 1;
SELECT balance FROM accounts WHERE id = 1; -- still returns 1000 (snapshot)
COMMIT;
```

PostgreSQL's REPEATABLE READ uses MVCC (Multi-Version Concurrency Control) — it stores multiple versions of rows and each transaction reads from its own consistent snapshot. No read locks are acquired. This is why REPEATABLE READ in PostgreSQL has higher read concurrency than in databases that implement it with read locks.

**PostgreSQL bonus**: PostgreSQL's REPEATABLE READ also prevents phantom reads (unlike the SQL standard). The snapshot taken at transaction start captures the entire DB state — new rows inserted by other transactions are invisible.

**Lost update detection**: under PostgreSQL's REPEATABLE READ, if two transactions both read a row and both try to update it, the second update will either wait for the first to commit (then update the new version) or — if using optimistic patterns — the application detects a 0-row update and retries. PostgreSQL does not silently discard the second update.

**When to use**: long-running reports or analytics that must see a consistent snapshot of the DB state as of query start. Also useful for batch jobs that read many rows and need consistency across the batch.

---

### SERIALIZABLE

The strongest isolation level. Transactions execute as if they ran one at a time in serial order, even though they actually run concurrently. Prevents all anomalies including phantoms and write skew.

**Write skew** is the anomaly that REPEATABLE READ does not prevent: two transactions read overlapping data, each decides to write based on what they read, and the combined effect violates an invariant that neither transaction individually broke.

Example — two doctors trying to go off-call:
```sql
-- Both transactions run concurrently
-- Rule: at least one doctor must be on-call
-- Doctor A: reads on-call count = 2, decides to go off-call
-- Doctor B: reads on-call count = 2, decides to go off-call
-- Both commit — on-call count = 0. Rule violated.
```
Neither transaction individually saw a violation, but the combined effect is incorrect. SERIALIZABLE detects this conflict and aborts one transaction.

PostgreSQL implements SERIALIZABLE using **SSI (Serializable Snapshot Isolation)** — a lock-free algorithm that tracks read/write dependencies between transactions and detects dangerous cycles at commit time. The loser gets:
```
ERROR: could not serialize access due to read/write dependencies among transactions
```

The application must catch this error and retry the transaction.

**Performance implications**: SSI adds overhead per transaction (tracking dependencies) and increases abort rate under high concurrency. For a high-throughput payment system doing thousands of transfers per second, this retry overhead is significant. This is why most payment systems use READ COMMITTED + explicit `SELECT FOR UPDATE` rather than SERIALIZABLE — they get the correctness they need with finer-grained control over what is locked and for how long.

**When to use SERIALIZABLE**: complex invariants that span multiple rows or tables that would require locking too many rows with `SELECT FOR UPDATE`. Example: ensuring a user's total pending transfers across all accounts never exceeds their credit limit — checking and updating across multiple rows. With SERIALIZABLE, you read freely and let the DB detect conflicts. With `SELECT FOR UPDATE`, you'd have to lock every relevant row upfront.

---

### Setting Isolation Level in JDBC

```java
// Per transaction
conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);    // default
conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

// Or in SQL
conn.prepareStatement("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").execute();
```

Always set before the first statement in the transaction. Changing it mid-transaction has no effect.

---

### Practical Decision Guide for Revolut

| Operation | Isolation Level | Locking |
|-----------|----------------|---------|
| Read transaction history (display) | READ COMMITTED | None |
| Debit / credit balance | READ COMMITTED | `SELECT FOR UPDATE` on account row |
| Multi-account transfer | READ COMMITTED | `SELECT FOR UPDATE` on both, consistent order |
| Generate monthly statement (snapshot) | REPEATABLE READ | None |
| Complex invariant across many rows | SERIALIZABLE | None (SSI handles it) |
| Idempotency key insert | READ COMMITTED | Unique constraint (not a lock) |

**Staff-level insight**: the most common mistake in FinTech interviews is saying "use SERIALIZABLE for correctness". That shows you know the theory but not the production cost. The better answer is: use READ COMMITTED with `SELECT FOR UPDATE` for targeted row locking on balance mutations, and reserve SERIALIZABLE for complex invariants where locking would be impractical. Always have retry logic regardless — even `SELECT FOR UPDATE` can deadlock.

---

## 4. Database — Internals & Indexing

Understanding how indexes work internally — not just how to create them — separates engineers who write fast queries from those who wonder why their queries are slow. For a payment system at Revolut's scale, incorrect indexing is the single most common cause of production performance incidents.

---

### How PostgreSQL Stores Data — The Heap

PostgreSQL stores table rows in **heap files** — fixed-size 8 KB pages on disk. Each page contains a header, a list of item pointers, and the actual row data (tuples). When you read a row, PostgreSQL loads the page containing that row from disk into the shared buffer cache, then returns the row.

A sequential scan (`Seq Scan`) reads every page in the heap file from start to finish. For a table with 100 million rows, this means reading gigabytes of data even if you only want one row. This is why indexes exist — they allow the DB to locate specific rows without scanning the entire table.

---

### B-Tree Index — The Default

Every `PRIMARY KEY`, `UNIQUE` constraint, and `CREATE INDEX` without a type specification creates a B-Tree index. It is a **balanced tree** where every leaf node is at the same depth, ensuring O(log n) lookup regardless of data distribution.

**Structure**: the tree has internal nodes (containing key ranges and pointers to child nodes) and leaf nodes (containing the actual indexed values and pointers — called TIDs, tuple IDs — to the heap rows). A search starts at the root, compares the search key to the key ranges, follows the matching pointer down to the next level, and repeats until reaching a leaf.

```
                    [500]
                   /     \
           [250]           [750]
          /     \         /     \
      [100,250] [300,500] [600,750] [800,1000]
```

**What it supports**:
- Equality: `WHERE id = 42` — O(log n), follows tree to the leaf containing 42.
- Range: `WHERE amount BETWEEN 100 AND 500` — finds the start leaf, then scans sibling leaves in order (leaf nodes are doubly linked).
- Ordering: `ORDER BY created_at` — the index is already sorted, no sort step needed.
- Prefix LIKE: `WHERE name LIKE 'John%'` — treated as a range query on the prefix.

**What it does not support well**:
- Suffix LIKE: `WHERE name LIKE '%Smith'` — cannot use the index, must scan all leaf nodes.
- Low-cardinality columns: `WHERE status = 'ACTIVE'` on a table where 95% of rows are ACTIVE — the index would point to almost every row in the heap. PostgreSQL's query planner will choose a sequential scan instead. Use a partial index for low-cardinality filters on a minority subset.

**Page splits**: when a B-Tree leaf node is full and a new key must be inserted, the node splits into two. This is expensive (writes two pages, updates parent pointers) and causes write amplification under high-cardinality random inserts (e.g. inserting UUIDs in random order). UUIDs as primary keys cause frequent page splits compared to sequential IDs (`BIGSERIAL`). Use `gen_random_uuid()` for correctness, but be aware of the write overhead.

---

### Hash Index

A hash index computes a hash of the indexed value and stores `(hash_bucket → TID)` pairs. Lookup is O(1) for equality — compute the hash, jump to the bucket, return the row pointer.

**What it supports**: only equality — `WHERE id = 'abc-123'`. Cannot do range scans, ordering, or LIKE.

**When to use**: UUID primary key lookups where you only ever do point lookups (no range scans). The O(1) vs O(log n) difference matters at extreme scale — billions of rows, millions of lookups per second. For most workloads, a B-Tree on a UUID is fast enough.

PostgreSQL hash indexes are WAL-logged since PostgreSQL 10 — they are crash-safe and can be replicated.

---

### Composite Index

A composite index covers multiple columns: `CREATE INDEX idx_txn_user_date ON transactions(user_id, created_at)`.

**Column order is critical**. The index is organised by the leftmost column first, then the second column within each group of equal values in the first column. The query planner can use this index when:
- The query filters on `user_id` alone: `WHERE user_id = 5` ✓ (uses index prefix)
- The query filters on both: `WHERE user_id = 5 AND created_at > '2026-01-01'` ✓
- The query filters on `created_at` alone: `WHERE created_at > '2026-01-01'` ✗ (cannot use — the leading column is missing)

The mental model: a composite index is like a phone book sorted by last name then first name. You can find everyone with last name "Smith" (leading column lookup). You can find "John Smith" (both columns). But you cannot efficiently find everyone named "John" without scanning every entry (leading column missing).

**Design principle**: put the most selective equality column first (the one that eliminates the most rows), then range columns, then columns only needed for ordering or covering.

For a payments system: `CREATE INDEX ON transfers(account_id, created_at DESC)` supports `WHERE account_id = ? ORDER BY created_at DESC LIMIT 20` — both the filter and the sort are served by the index.

---

### Covering Index (INCLUDE)

After finding a matching row in the index, PostgreSQL normally has to do a **heap fetch** — follow the TID pointer from the index leaf to the actual heap page to read the full row. This is a random I/O operation: the heap page might not be in the buffer cache, so it requires a disk read.

A covering index eliminates the heap fetch by storing extra columns in the index leaf node itself:

```sql
CREATE INDEX idx_txn_user_covering
ON transactions(user_id, created_at DESC)
INCLUDE (amount, status);
```

Now a query like `SELECT user_id, created_at, amount, status FROM transactions WHERE user_id = 5 ORDER BY created_at DESC LIMIT 20` can be satisfied entirely from the index — no heap fetch. PostgreSQL calls this an **Index Only Scan**.

**When it matters**: hot query paths executed millions of times per hour. Avoiding heap fetches reduces I/O dramatically. The index becomes larger (stores more data per leaf), which is the trade-off — use INCLUDE only for columns actually needed by hot queries.

---

### Partial Index

A partial index includes only rows matching a `WHERE` clause. The index is smaller, faster to scan, and more selective.

```sql
-- Only index pending transfers — not the millions of completed ones
CREATE INDEX idx_transfers_pending
ON transfers(account_id, created_at)
WHERE status = 'PENDING';
```

This index is tiny compared to a full index on `(account_id, created_at)`. A query `WHERE account_id = 5 AND status = 'PENDING'` uses this index and scans only the few pending rows — not the millions of historical completed rows.

**Use cases in FinTech**:
- Job queues: `WHERE processed = false` — only index unprocessed jobs.
- Fraud review queue: `WHERE flagged = true AND reviewed = false`.
- Active accounts: `WHERE deleted_at IS NULL`.

PostgreSQL will use the partial index only if the query's WHERE clause implies the index's WHERE clause. `WHERE account_id = 5 AND status = 'PENDING'` implies `status = 'PENDING'`, so the partial index applies. `WHERE account_id = 5` alone does not imply it — the full index is used instead.

---

### MVCC — Multi-Version Concurrency Control

MVCC is the mechanism that allows PostgreSQL to provide snapshot isolation without read locks. Every row version (tuple) is annotated with:
- `xmin`: the transaction ID that created this version.
- `xmax`: the transaction ID that deleted or updated this version (0 if still live).

When a transaction reads a row, PostgreSQL checks these fields against its snapshot — which transactions were committed when my transaction started? If `xmin` is committed and `xmax` is either 0 or not yet committed, the row is visible.

When a row is updated, PostgreSQL does not modify it in place. It marks the old version with `xmax = current_txn_id` and inserts a new version with `xmin = current_txn_id`. Both versions coexist on disk until VACUUM cleans up the old one.

**Implication**: heavy `UPDATE` workloads create a lot of dead tuples (old versions no longer visible to any transaction). These dead tuples bloat the table, slow down sequential scans, and eventually need to be reclaimed by VACUUM.

---

### VACUUM and Table Bloat

VACUUM reclaims dead tuples — old row versions that are no longer visible to any active transaction. AUTOVACUUM runs in the background automatically, triggered when a table accumulates enough dead tuples (configurable threshold).

Problems when AUTOVACUUM lags:
- Table bloat: the heap file grows. Queries scan more pages. Buffer cache is wasted on dead tuples.
- Index bloat: dead tuple pointers accumulate in index leaf nodes. Index scans follow dead pointers to the heap, discover the row is dead, and discard it — wasted I/O.
- Transaction ID wraparound: PostgreSQL uses 32-bit transaction IDs. After ~2 billion transactions, IDs wrap around. Without VACUUM, the DB would be unable to distinguish old transactions from new ones. PostgreSQL will forcefully stop accepting writes if wraparound is imminent — one of the few ways to bring down a healthy PostgreSQL instance.

Monitor with:
```sql
SELECT relname, n_dead_tup, n_live_tup, last_autovacuum
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

For a high-write payment ledger, tune autovacuum aggressively:
```sql
ALTER TABLE ledger_entries SET (
    autovacuum_vacuum_scale_factor = 0.01,  -- trigger at 1% dead tuples (default 20%)
    autovacuum_analyze_scale_factor = 0.005
);
```

---

### Missing FK Index — The Classic Gotcha

PostgreSQL does not automatically create indexes on foreign key columns (unlike MySQL). This means:

```sql
CREATE TABLE transfers (
    id         UUID PRIMARY KEY,
    account_id BIGINT REFERENCES accounts(id),  -- FK — no index created automatically
    amount     NUMERIC
);
```

A `DELETE FROM accounts WHERE id = 5` requires PostgreSQL to check if any `transfers` row references account 5. Without an index on `transfers.account_id`, this is a **sequential scan of the entire transfers table** — potentially millions of rows, holding a lock on the transfers table for the entire scan duration.

Always index FK columns:
```sql
CREATE INDEX ON transfers(account_id);
```

Also index any column that appears in JOIN conditions, ORDER BY clauses on large tables, or WHERE clauses in frequent queries.

**Staff-level insight**: run this query periodically to find unindexed FK columns in your schema:
```sql
SELECT conrelid::regclass AS table,
       a.attname AS column,
       conname AS constraint
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
      SELECT 1 FROM pg_index i
      WHERE i.indrelid = c.conrelid
        AND a.attnum = ANY(i.indkey)
  );

---

## 5. Flyway Migrations

Database migrations in production are irreversible operations on live data. Getting them wrong means downtime, data loss, or a table lock that takes down your payment service during peak hours. Flyway provides the versioning mechanism — understanding how to write safe migrations is what separates a senior engineer from a staff engineer.

---

### How Flyway Works

Flyway maintains a `flyway_schema_history` table in the database. When the application starts, Flyway scans the classpath for migration files, compares them against the history table, and runs any that have not been applied yet.

Migration files are named with a strict convention: `V{version}__{description}.sql`:
- `V1__create_accounts.sql`
- `V2__add_status_column_to_transfers.sql`
- `V3__create_index_transfers_account_id.sql`

The version can be a number (`1`, `2`, `3`) or a timestamp (`20260810143000`). Timestamps are preferable in teams — they prevent version conflicts when two developers create migrations simultaneously.

Once applied, a migration is **never run again**. Flyway stores a checksum of each file — if you edit an already-applied migration, Flyway will refuse to start, detecting the checksum mismatch. This is a safety feature: applied migrations are immutable history.

**Repeatable migrations** (`R__description.sql`) are re-applied whenever their checksum changes. Use for views, functions, and stored procedures — not for schema changes.

---

### The Locking Problem

`ALTER TABLE` in PostgreSQL acquires an `ACCESS EXCLUSIVE` lock — the most restrictive lock mode, blocking all reads and writes on the table for the entire duration of the DDL operation. For a `transfers` table with 500 million rows, operations like:
- `ALTER TABLE transfers ADD COLUMN risk_score INTEGER DEFAULT 0 NOT NULL` — pre-PostgreSQL 11, this rewrites every row in the table, holding the lock for the entire rewrite. On 500 million rows, this could be minutes.
- `CREATE INDEX ON transfers(account_id)` — builds the index while holding an exclusive lock, blocking all writes.
- `ALTER TABLE transfers ADD CONSTRAINT ... FOREIGN KEY ...` — validates the constraint against all existing rows, holding a lock.

During this time, every read and write to the table queues behind the lock. In a payment service, this means failed requests, timeouts, and a production incident.

---

### Safe Migration Patterns

**Adding a column with a default value (PostgreSQL 11+)**

PostgreSQL 11 changed the behaviour of `ALTER TABLE ... ADD COLUMN ... DEFAULT`: it now stores the default in the catalog and returns it at read time without rewriting rows. This is safe and instant.

```sql
-- PostgreSQL 11+ — instant, no table rewrite
ALTER TABLE transfers ADD COLUMN risk_score INTEGER DEFAULT 0;
```

Pre-PostgreSQL 11, this rewrote every row. The safe pattern was:
```sql
-- Step 1: add nullable (instant)
ALTER TABLE transfers ADD COLUMN risk_score INTEGER;
-- Step 2: backfill in batches (no table lock — just row locks during UPDATE)
UPDATE transfers SET risk_score = 0 WHERE id BETWEEN 1 AND 100000;
-- ... repeat for all batches
-- Step 3: add NOT NULL constraint (fast — all values are now set)
ALTER TABLE transfers ALTER COLUMN risk_score SET NOT NULL;
```

**Adding a NOT NULL column without a default**

If the column genuinely has no default and no existing rows can have a value, the only safe path is:
1. Add as nullable.
2. Ensure the application writes the value for all new rows.
3. Backfill existing rows.
4. Add `NOT NULL` constraint.

Do not try to do this in one migration — step 4 must wait until all rows have a value.

**Adding an index**

`CREATE INDEX` takes an `ACCESS EXCLUSIVE` lock on the table. Never use it on a live production table. Always use `CREATE INDEX CONCURRENTLY`:

```sql
-- WRONG — locks the table
CREATE INDEX idx_transfers_account ON transfers(account_id);

-- CORRECT — builds in background, no write lock
CREATE INDEX CONCURRENTLY idx_transfers_account ON transfers(account_id);
```

`CREATE INDEX CONCURRENTLY` builds the index in two passes while the table remains fully available. It takes longer and uses more CPU, but it never blocks writes. The only caveat: it cannot run inside a transaction block — Flyway by default wraps each migration in a transaction. You must disable that for this migration:

```java
// In Java-based migration (Flyway supports Java migrations for complex cases):
@Override
public boolean canExecuteInTransaction() {
    return false;  // required for CREATE INDEX CONCURRENTLY
}
```

Or use a raw SQL migration with `-- flyway:notransaction` annotation (Flyway 9+):
```sql
-- flyway:notransaction
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_transfers_account ON transfers(account_id);
```

**Renaming a column**

Never `ALTER TABLE transfers RENAME COLUMN old_name TO new_name` in one step on a live system. The application reads `old_name` — after the rename, the application breaks immediately. The safe three-release pattern:

- **Release 1**: add new column `new_name`. Write to both `old_name` and `new_name`.
- **Release 2**: backfill `new_name` from `old_name` for historical rows. Switch reads to `new_name`.
- **Release 3**: drop `old_name`. Stop writing to it.

**Dropping a column**

Never drop a column in the same release as removing the code that uses it. If the code deploys but the migration hasn't run yet (rolling deploy), the code will fail reading a column that still exists. If the migration runs but old code is still running, the old code will fail because the column is gone.

Safe pattern:
- **Release 1**: remove the column from all application code. Deploy. Column still exists in DB — harmless.
- **Release 2**: `ALTER TABLE ... DROP COLUMN ...`. No code reads it anymore.

**Constraint validation**

`ALTER TABLE transfers ADD CONSTRAINT fk_account FOREIGN KEY (account_id) REFERENCES accounts(id)` validates every existing row against the constraint, holding a lock for the full scan.

PostgreSQL supports adding constraints as `NOT VALID` first (acquires a brief lock to prevent new violations), then validating separately (no lock during validation):

```sql
-- Step 1: add constraint, mark not valid (brief lock)
ALTER TABLE transfers
ADD CONSTRAINT fk_account FOREIGN KEY (account_id) REFERENCES accounts(id)
NOT VALID;

-- Step 2: validate existing rows (no write lock, runs concurrently)
ALTER TABLE transfers VALIDATE CONSTRAINT fk_account;
```

---

### Multi-Instance Deployment Safety

When deploying a new version to a cluster of 50 pods all starting simultaneously, all 50 pods will attempt to run Flyway on startup. Flyway handles this with a DB-level advisory lock on `flyway_schema_history` — only one instance runs migrations, the others wait and proceed once the lock is released.

This is safe but has two implications:
1. **Startup latency**: if migrations are slow (large backfill), all pods wait at startup. For a rolling deploy, this blocks the entire deployment.
2. **Stateful containers**: Flyway's auto-migration on startup couples schema changes to code deployment. A failed migration prevents the application from starting at all.

The staff-level pattern is to **separate migration from deployment**: run Flyway as a Kubernetes init container or a pre-deployment job step. The migration completes before any new pods start. This decouples schema evolution from pod startup, allows independent rollback of migrations, and prevents startup timeouts.

```yaml
# Kubernetes: run migration as init container
initContainers:
  - name: flyway-migrate
    image: flyway/flyway:latest
    args: ["migrate"]
    env:
      - name: FLYWAY_URL
        value: "jdbc:postgresql://db:5432/payments"
```

---

### Staff-level insight

The expand-contract pattern (also called parallel change) is the correct mental model for all production migrations: every change goes through **expand** (add new structure, keep old), **migrate** (move data, update code), **contract** (remove old structure). Never skip phases. Never combine expand and contract in the same release. This applies to columns, tables, indexes, constraints, and even API fields — the same principle governs all backward compatibility.

---

## 6. Java Locking

Java provides multiple layers of locking — from the language-level `synchronized` keyword to the explicit locks in `java.util.concurrent.locks` to lock-free atomic operations. Choosing the right tool requires understanding what each one does internally, not just what API it exposes.

---

### The Memory Model — Why Locking Exists

Before diving into specific mechanisms, understand why they exist. The Java Memory Model (JMM) allows the JVM and CPU to reorder instructions, cache values in registers, and delay writes to main memory for performance. Without synchronisation, one thread's writes may not be visible to another thread at all — not because of race conditions on a single variable, but because the write has not yet been flushed from a CPU cache to main memory.

Synchronisation in Java has two effects:
1. **Mutual exclusion**: only one thread executes the critical section at a time.
2. **Memory visibility**: all writes made before releasing a lock are visible to any thread that subsequently acquires the same lock.

`volatile` provides only (2) — visibility without mutual exclusion.

---

### `synchronized`

`synchronized` is a language keyword that uses the intrinsic monitor lock (also called the object monitor) built into every Java object.

```java
// Synchronise on instance — one thread at a time per instance
public synchronized void deposit(long amount) {
    this.balance += amount;
}

// Equivalent explicit form
public void deposit(long amount) {
    synchronized (this) {
        this.balance += amount;
    }
}

// Synchronise on class — one thread at a time across all instances
public static synchronized void globalOperation() { ... }

// Synchronise on a private lock object — better encapsulation
private final Object balanceLock = new Object();
public void deposit(long amount) {
    synchronized (balanceLock) {
        this.balance += amount;
    }
}
```

Using a private lock object is preferable to `synchronized(this)` — it prevents external code from accidentally locking the same monitor and causing unexpected contention or deadlock.

**Internals**: under the hood, `synchronized` uses the `monitorenter` and `monitorexit` JVM bytecodes. The JVM implements these with a thin lock (a CAS on the object header for uncontended case) that inflates to an OS-level mutex under contention. Uncontended `synchronized` is very fast — just a CAS operation with no system call.

**Reentrancy**: a thread can re-acquire a lock it already holds without deadlocking. This is essential for recursive methods and calling `synchronized` methods from other `synchronized` methods on the same object.

**Limitations**:
- No timeout — `synchronized` blocks indefinitely. If the lock holder is slow, all waiters are stuck.
- Not interruptible — a thread waiting for `synchronized` cannot be interrupted.
- No fairness — threads are not guaranteed to acquire the lock in arrival order. Under high contention, some threads may starve.
- One condition queue — `wait()/notify()` is the only coordination mechanism, and it is clunky.

**Use when**: simple, low-contention critical sections with no need for timeouts or interruptibility. For most in-process state protection, `synchronized` is sufficient and has the lowest overhead.

---

### `ReentrantLock`

`ReentrantLock` from `java.util.concurrent.locks` provides the same mutual exclusion as `synchronized` but with explicit lock/unlock and a richer API.

```java
private final ReentrantLock lock = new ReentrantLock();

public void transfer(long amount) {
    lock.lock();
    try {
        // critical section — only one thread here at a time
        this.balance -= amount;
    } finally {
        lock.unlock();  // MUST be in finally — if the body throws, lock must still release
    }
}
```

The `finally` block is non-negotiable. If the critical section throws and you forget `finally`, the lock is never released — every subsequent thread blocks forever.

**Additional capabilities**:

`tryLock()` — attempts to acquire the lock without blocking. Returns `true` if acquired, `false` if already held. Use when you want to skip work rather than wait:

```java
if (lock.tryLock()) {
    try {
        // do work
    } finally {
        lock.unlock();
    }
} else {
    // lock is held — skip this cycle, try again later
}
```

`tryLock(timeout, unit)` — blocks up to the timeout, then gives up. Essential for avoiding indefinite blocking in payment flows:

```java
if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
    try { ... }
    finally { lock.unlock(); }
} else {
    throw new LockAcquisitionTimeoutException("Could not acquire account lock");
}
```

`lockInterruptibly()` — blocks until the lock is acquired OR the thread is interrupted. Useful when you want cancellation to be able to abort a waiting thread.

**Fair mode**: `new ReentrantLock(true)` — threads acquire the lock in the order they requested it (FIFO). Prevents starvation but reduces throughput — fair mode adds overhead to every lock acquisition. Default (non-fair) mode allows barging — a thread that just released the CPU can re-acquire the lock before a thread that has been waiting, which improves throughput but can cause starvation of long-waiting threads.

**Condition variables**: `ReentrantLock` supports multiple `Condition` objects, unlike `synchronized` which has only one condition queue (`wait/notify`):

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notEmpty = lock.newCondition();
private final Condition notFull  = lock.newCondition();

// Producer: signal notEmpty when item added
// Consumer: await notEmpty when queue is empty
// This allows selective notification — only wake consumers, not producers
```

**Use when**: you need tryLock with timeout, interruptibility, fair ordering, or multiple condition variables.

---

### `ReentrantReadWriteLock`

Allows multiple concurrent readers OR one exclusive writer, but not both simultaneously. This is optimal for read-heavy shared state.

```java
private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
private final ReadWriteLock.ReadLock  readLock  = rwLock.readLock();
private final ReadWriteLock.WriteLock writeLock = rwLock.writeLock();

private Map<String, BigDecimal> exchangeRates = new HashMap<>();

public BigDecimal getRate(String currency) {
    readLock.lock();
    try {
        return exchangeRates.get(currency);  // multiple threads can read simultaneously
    } finally {
        readLock.unlock();
    }
}

public void updateRates(Map<String, BigDecimal> newRates) {
    writeLock.lock();
    try {
        exchangeRates = new HashMap<>(newRates);  // exclusive write
    } finally {
        writeLock.unlock();
    }
}
```

**When it helps**: exchange rate cache read millions of times per second, updated once per minute. With `synchronized`, every read would be serialised. With `ReentrantReadWriteLock`, all reads proceed in parallel and only updates are exclusive.

**When it doesn't help**: if writes are frequent (> ~20% of operations), the overhead of managing the read/write lock distinction outweighs the benefit. In that case, `synchronized` or a lock-free structure is better.

---

### `StampedLock`

`StampedLock` (Java 8+) goes further than `ReentrantReadWriteLock` by offering **optimistic reads** — reading without acquiring any lock at all, then validating that no write occurred during the read. If validation passes, you avoided all locking overhead.

```java
private final StampedLock lock = new StampedLock();
private double x, y;

public double distanceFromOrigin() {
    long stamp = lock.tryOptimisticRead();  // no lock acquired
    double currentX = x;
    double currentY = y;

    if (!lock.validate(stamp)) {
        // A write occurred between tryOptimisticRead and validate — retry with real read lock
        stamp = lock.readLock();
        try {
            currentX = x;
            currentY = y;
        } finally {
            lock.unlockRead(stamp);
        }
    }
    return Math.sqrt(currentX * currentX + currentY * currentY);
}
```

**Caveat**: `StampedLock` is not reentrant. A thread that holds a write lock cannot acquire a read lock without deadlocking. It is also harder to use correctly — the optimistic read pattern must be implemented carefully to avoid reading partially-updated state.

**Use when**: extremely read-heavy state (exchange rate cache, config) where write latency is acceptable but read throughput must be maximised.

---

### `volatile`

`volatile` is a field modifier, not a lock. It makes reads and writes to the field directly to main memory (bypassing CPU caches) and prevents instruction reordering around the field access.

```java
private volatile boolean shutdownRequested = false;

// Thread A (control thread)
public void requestShutdown() {
    shutdownRequested = true;  // written to main memory immediately
}

// Thread B (worker thread)
public void run() {
    while (!shutdownRequested) {  // reads from main memory — sees the update
        processNextEvent();
    }
}
```

Without `volatile`, Thread B might cache `shutdownRequested` in a register and never see Thread A's write — the loop runs forever.

**What `volatile` does NOT do**: it does not make compound operations atomic. `count++` is a read-modify-write — three separate operations. Even with `volatile int count`, two threads doing `count++` concurrently will produce a race condition. Use `AtomicInteger` for atomic compound operations.

**Use when**: a single writer, multiple readers, no compound operations. Simple boolean flags, state machine flags (`RUNNING`, `STOPPED`), double-checked locking (the `instance` field in singleton patterns must be `volatile`).

**Double-checked locking — the correct pattern**:
```java
private volatile Singleton instance;

public Singleton getInstance() {
    if (instance == null) {                    // first check (no lock)
        synchronized (Singleton.class) {
            if (instance == null) {            // second check (with lock)
                instance = new Singleton();    // volatile ensures full construction visible
            }
        }
    }
    return instance;
}
```

Without `volatile` on `instance`, the JVM could publish a partially-constructed `Singleton` to other threads due to instruction reordering.

---

### Deadlock — Detection and Avoidance

A deadlock is a cycle of threads where each holds a lock that another needs. The system makes no progress — all threads in the cycle block forever.

**Detection at runtime**:
```bash
jstack <pid>           # dump all thread stacks — look for "BLOCKED" with "waiting to lock"
jcmd <pid> Thread.print
```

Programmatically:
```java
ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
long[] deadlockedThreads = mxBean.findDeadlockedThreads();
if (deadlockedThreads != null) {
    ThreadInfo[] info = mxBean.getThreadInfo(deadlockedThreads);
    // log and alert
}
```

**Avoidance — deterministic lock ordering**: the most reliable strategy. Assign a total order to all lockable objects and always acquire them in that order. For bank accounts, use account ID:

```java
public void transfer(Account from, Account to, long amount) {
    Account first  = from.getId() < to.getId() ? from : to;
    Account second = from.getId() < to.getId() ? to   : from;

    synchronized (first) {
        synchronized (second) {
            from.debit(amount);
            to.credit(amount);
        }
    }
}
```

`transfer(A→B)` and `transfer(B→A)` will both lock the lower-ID account first. The second thread will block on the first lock, not on the second — breaking the cycle.

**Avoidance — tryLock with timeout and backoff**:
```java
public boolean transfer(Account from, Account to, long amount) throws InterruptedException {
    while (true) {
        if (from.getLock().tryLock(50, TimeUnit.MILLISECONDS)) {
            try {
                if (to.getLock().tryLock(50, TimeUnit.MILLISECONDS)) {
                    try {
                        from.debit(amount);
                        to.credit(amount);
                        return true;
                    } finally {
                        to.getLock().unlock();
                    }
                }
            } finally {
                from.getLock().unlock();
            }
        }
        Thread.sleep(ThreadLocalRandom.current().nextLong(10, 50)); // random backoff
    }
}
```

The random backoff prevents livelock — two threads repeatedly failing to acquire the second lock at exactly the same time.

**Avoidance — lock-free structures**: `AtomicLong`, `ConcurrentHashMap`, `LongAdder` use CAS (Compare-And-Swap) hardware instructions — no locks, no deadlocks. For simple counters and maps, prefer these over any lock-based structure.

---

## 7. Java Concurrency Classes

The `java.util.concurrent` package provides building blocks that are correct, well-tested, and performant. The skill is knowing which one to reach for and why — and understanding the internals well enough to reason about their behaviour under load.

---

### Atomic Classes — Lock-Free Operations via CAS

All atomic classes use **Compare-And-Swap (CAS)** — a single CPU instruction that atomically reads a memory location, compares it to an expected value, and writes a new value only if the comparison succeeds. If another thread changed the value between your read and your CAS, the CAS fails and you retry. No locks, no context switches, no kernel calls.

**`AtomicLong`** — lock-free counter. The most important atomic class for a payment system.

```java
AtomicLong balance = new AtomicLong(1000);

// Atomic increment — safe from any number of threads
balance.incrementAndGet();

// Atomic add — safe concurrent deposit
balance.addAndGet(amount);

// Atomic CAS — conditional update (optimistic locking pattern)
long current = balance.get();
long newBalance = current - withdrawAmount;
if (current >= withdrawAmount) {
    boolean success = balance.compareAndSet(current, newBalance);
    if (!success) {
        // another thread changed balance between get() and compareAndSet() — retry
    }
}
```

**`AtomicReference<T>`** — CAS on an object reference. Use to atomically swap out an immutable object:
```java
AtomicReference<Map<String, BigDecimal>> ratesRef = new AtomicReference<>(loadRates());

// Atomic rate update — swap the entire immutable map
ratesRef.set(Collections.unmodifiableMap(newRates));

// Readers always get a consistent snapshot
BigDecimal rate = ratesRef.get().get("USD");
```

**`AtomicStampedReference<T>`** — CAS + integer stamp to prevent the ABA problem. The ABA problem: value changes A→B→A, your CAS sees A and succeeds, but you missed the intermediate B. The stamp (a version counter) changes with every update, so even if the value reverts to A, the stamp is different:
```java
AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 0);
int[] stamp = new int[1];
String value = ref.get(stamp);  // value="A", stamp[0]=0
// another thread does A→B→A, stamp becomes 2
ref.compareAndSet("A", "C", stamp[0], stamp[0] + 1);  // fails — stamp is 2, not 0
```

**`LongAdder`** — better than `AtomicLong` for high-contention counters. Under heavy contention, many threads CAS-failing on the same `AtomicLong` cell causes a lot of retries. `LongAdder` maintains a cell array — different threads update different cells, reducing contention. Reading the total requires summing all cells (`sum()`). The trade-off: reads are slightly more expensive and may see a value that's slightly stale if updates are in-flight. For metrics counters (request count, total transfer volume) this is ideal.

```java
LongAdder requestCount = new LongAdder();
requestCount.increment();        // fast, low contention
long total = requestCount.sum(); // reads all cells — slightly expensive
```

---

### Concurrent Collections

**`ConcurrentHashMap`**

The most widely used concurrent collection. Reads are non-blocking — no locks at all for `get()`. Writes lock only the affected segment (Java 8+: individual bucket level), so concurrent writes to different keys proceed in parallel.

Critical method: `computeIfAbsent(key, mappingFunction)` — atomically: if the key is absent, compute a value and insert it; if present, return the existing value. This is the correct pattern for a cache:

```java
ConcurrentHashMap<String, AccountCache> cache = new ConcurrentHashMap<>();

AccountCache cached = cache.computeIfAbsent(accountId, id -> loadFromDb(id));
```

**Caveat**: the mapping function may be called twice for the same key if two threads call `computeIfAbsent` concurrently and both find the key absent. Both calls run; the second result is discarded. For cheap functions (e.g. creating a `new ArrayList<>()`) this is fine. For expensive operations (DB query), use `Caffeine` cache which uses a per-key promise to ensure the function runs exactly once.

**`CopyOnWriteArrayList`**

Every write (add, set, remove) creates a new copy of the underlying array. Reads are lock-free — they always see a consistent snapshot. The iterator is a snapshot taken at creation time and is unaffected by subsequent modifications.

```java
CopyOnWriteArrayList<TransactionListener> listeners = new CopyOnWriteArrayList<>();
listeners.add(listener);                     // copies the array
listeners.forEach(l -> l.onTransaction(t)); // no lock needed — reads the snapshot
```

Use for: event listener lists, plugin registries — small, rarely modified, frequently iterated. Do not use for large lists with frequent writes — the copy-on-write overhead dominates.

**`LinkedBlockingQueue` and `ArrayBlockingQueue`**

Both implement `BlockingQueue` — a queue with blocking `put()` and `take()` operations. These are the correct building block for producer-consumer patterns.

`LinkedBlockingQueue` — optionally bounded, backed by a linked list. Separate locks for head and tail — producers and consumers rarely contend with each other.

`ArrayBlockingQueue` — always bounded, backed by an array. Single lock for both head and tail — simpler but more contention between producers and consumers.

```java
BlockingQueue<PaymentEvent> queue = new LinkedBlockingQueue<>(1000); // bounded

// Producer thread
queue.put(event);          // blocks if full — back-pressure

// Consumer thread
PaymentEvent event = queue.take();  // blocks until item available
```

The bounded capacity is important — it provides back-pressure. If the consumer can't keep up, the producer blocks rather than creating an unbounded queue that grows until OOM.

**`ConcurrentLinkedQueue`** — unbounded, non-blocking FIFO. Uses CAS internally. `offer()` and `poll()` never block. Use when you want to avoid blocking and can tolerate an unbounded queue (e.g. low-volume event bus).

---

### Executors and Thread Pools

**`Executors.newFixedThreadPool(n)`**

Creates a pool of exactly `n` threads. Excess tasks queue in an unbounded `LinkedBlockingQueue`. Threads are never idle-terminated — they persist for the pool's lifetime.

The right size for `n` depends on workload:
- CPU-bound work: `n = Runtime.getRuntime().availableProcessors()` — more threads just context-switch.
- I/O-bound work (JDBC calls, HTTP): `n = desired_concurrency / (1 - blocking_fraction)`. If tasks block 90% of the time, you need 10× more threads than CPUs to keep CPUs busy.

**`Executors.newCachedThreadPool()`**

Creates threads on demand, terminates idle threads after 60 seconds. No bound on thread count. Under a sudden spike — 10,000 tasks submitted at once — 10,000 threads are created. Each Java platform thread consumes ~1 MB stack by default. 10,000 threads = 10 GB RAM instantaneously, followed by an OOM crash. This is what killed the StockCart service (from prior incidents). Never use in production without an explicit bound.

**`ScheduledExecutorService`**

For periodic and delayed tasks:
```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

// Run once after 5 seconds
scheduler.schedule(task, 5, TimeUnit.SECONDS);

// Run every 10 seconds, first run after 10 seconds
scheduler.scheduleAtFixedRate(task, 10, 10, TimeUnit.SECONDS);

// Run 10 seconds after the previous run COMPLETES (not starts)
scheduler.scheduleWithFixedDelay(task, 10, 10, TimeUnit.SECONDS);
```

`scheduleAtFixedRate` is dangerous if a task occasionally runs longer than the interval — it queues up pending runs. `scheduleWithFixedDelay` is safer for tasks with variable duration.

**`ForkJoinPool`**

Work-stealing pool designed for recursive divide-and-conquer tasks. Each thread has its own deque. When a thread's deque is empty, it steals tasks from the tail of another thread's deque. This minimises idle time and maximises CPU utilisation for recursive tasks.

Used implicitly by parallel streams (`stream().parallel()`) and `CompletableFuture.supplyAsync()` (which uses the common pool). The common `ForkJoinPool` has `availableProcessors - 1` threads — not suitable for blocking I/O tasks. If your `CompletableFuture` chain does JDBC calls, supply a custom executor:

```java
ExecutorService jdbcPool = Executors.newFixedThreadPool(20);
CompletableFuture.supplyAsync(() -> loadAccount(id), jdbcPool)
    .thenApply(account -> validate(account))
    .thenCompose(account -> persistTransfer(account));
```

**Virtual Threads (Java 21)**

Virtual threads are lightweight threads managed by the JVM, not the OS. Creating one million virtual threads is routine — they use a few KB each (vs ~1 MB per platform thread). They are multiplexed onto a small pool of carrier (OS) threads.

When a virtual thread blocks on I/O (JDBC, HTTP), the carrier thread is released to run other virtual threads — no thread sitting idle waiting for a network response.

```java
// One virtual thread per task — replace thread pools for I/O-bound work
ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
vt.submit(() -> processTransfer(event));
```

For a payment service that does JDBC calls, you can replace a 200-thread fixed pool with virtual threads and handle 10,000 concurrent transfers with the same CPU footprint. **Caveat**: virtual threads can be pinned (blocked as a platform thread, not unmounted) if they synchronise inside a `synchronized` block that calls a blocking I/O operation. Use `ReentrantLock` instead of `synchronized` in I/O-heavy code with virtual threads.

---

### Synchronisers

**`CountDownLatch`**

One or more threads wait until a set of operations completes. Initialise with a count; call `countDown()` for each completed operation; call `await()` to block until count reaches zero.

```java
CountDownLatch startSignal = new CountDownLatch(1);
CountDownLatch doneSignal  = new CountDownLatch(5);

// 5 worker threads wait for start signal, then do work, then count down
for (int i = 0; i < 5; i++) {
    executor.submit(() -> {
        startSignal.await();    // wait for gun
        doWork();
        doneSignal.countDown(); // signal completion
    });
}

startSignal.countDown();  // fire the gun — all workers proceed
doneSignal.await();       // wait for all workers to finish
```

One-shot — cannot be reset. Use for startup coordination, test parallelism setup.

**`CyclicBarrier`**

N threads meet at a barrier point, then all proceed together. Reusable — after all threads pass, the barrier resets for the next cycle.

```java
CyclicBarrier barrier = new CyclicBarrier(3, () -> System.out.println("All ready"));

// Each of 3 threads calls barrier.await() — all 3 block until all 3 have called it
barrier.await(); // thread 1 waits
barrier.await(); // thread 2 waits
barrier.await(); // thread 3 arrives — all 3 released simultaneously, action runs
```

Use for simulation steps, batch processing phases where all workers must complete phase N before any starts phase N+1.

**`Semaphore`**

A counter that limits concurrent access to a resource. `acquire()` decrements the counter (blocks if zero). `release()` increments it.

```java
Semaphore connectionPool = new Semaphore(10); // max 10 concurrent DB connections

connectionPool.acquire();  // blocks if 10 connections already in use
try {
    useDatabase();
} finally {
    connectionPool.release();
}
```

Use for: connection pool limits, rate limiting (N concurrent requests to an external API), resource-bounded thread access.

**`CompletableFuture`**

Async pipeline built on callbacks. The key insight is that each stage runs on a thread pool, not the calling thread — so you can chain expensive operations without blocking.

```java
CompletableFuture.supplyAsync(() -> loadAccount(fromId), jdbcPool)
    .thenCombine(
        CompletableFuture.supplyAsync(() -> loadAccount(toId), jdbcPool),
        (from, to) -> new TransferRequest(from, to, amount)
    )
    .thenApplyAsync(request -> validateAndExecute(request), jdbcPool)
    .thenAccept(result -> publishEvent(result))
    .exceptionally(ex -> {
        log.error("Transfer failed", ex);
        return null;
    });
```

`thenApply` — synchronous transformation on the same thread that completed the previous stage. `thenApplyAsync` — runs on the executor. `thenCompose` — flat-maps a future that returns another future (avoid nested `CompletableFuture<CompletableFuture<T>>`). `allOf` — waits for all futures in parallel. `anyOf` — returns the first to complete.

**Staff-level insight**: the `CompletableFuture` common pool (default executor) is `ForkJoinPool.commonPool()` — it is a fixed-size pool shared across all async operations in the JVM. If you submit blocking I/O operations to it (JDBC, HTTP), you starve the pool for everyone. Always provide an explicit executor for I/O-bound stages.

---

## 8. Idempotency

Idempotency is the property of an operation whereby executing it multiple times produces exactly the same result as executing it once. In a distributed payment system, this is not a nice-to-have — it is a correctness requirement. Networks are unreliable. Clients retry. Servers crash mid-operation. Without idempotency, retries cause duplicate charges, double transfers, and phantom debits that destroy user trust.

---

### Why Idempotency Is Hard in Payments

Consider a `POST /transfers` request. The client sends the request. The server receives it, executes the transfer, and is about to send the `200 OK` response — and then the network drops. The client receives no response. From the client's perspective, the request may have failed. It retries.

If the server has no idempotency mechanism, the second request executes a second transfer. The user is charged twice. This is a correctness violation — not just a UX problem. Depending on jurisdiction, it may also be a regulatory violation.

At Revolut's scale — millions of transfers per day across unreliable mobile networks — retries are not edge cases. They are routine.

---

### How to Generate Idempotency Keys

**Client-generated UUID v4** — the correct pattern for FinTech. The client generates a random UUID before sending the request and includes it as a header:

```
POST /transfers
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

The client generates this UUID once, stores it locally, and reuses it for every retry of the same logical operation. If the server responds successfully, the client discards the key. If the request fails (network timeout, 5xx), the client retries with the same key.

**Why UUID v4**: it is globally unique with overwhelming probability — `2^122` possible values. No coordination with the server needed. No risk of collision.

**Deterministic key from request content** — hash of (user_id + target_account + amount + client-side timestamp_bucket). The same intent within a time window always produces the same key. Avoids the client needing to persist the UUID between retries. Trade-off: if the user genuinely wants to make two identical transfers minutes apart, the second one is rejected as a duplicate if it falls in the same bucket. Bucket size must be tuned to the expected retry window.

```java
String key = DigestUtils.sha256Hex(
    userId + ":" + targetAccount + ":" + amount + ":" + (epochSeconds / 300)
    // 5-minute buckets
);
```

**Server-generated key** — server creates a key on first request and returns it in the response; client includes it on retries. Requires two round-trips for the first call (pre-create key, then execute). Useful when the client cannot generate keys (e.g. legacy systems). Adds latency and complexity.

**Best practice**: client-generated UUID v4. The client owns the retry logic and the key lifecycle.

---

### Where to Store Idempotency Keys

Dedicated table in PostgreSQL:

```sql
CREATE TABLE idempotency_keys (
    key              UUID        PRIMARY KEY,
    status           TEXT        NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    request_hash     TEXT        NOT NULL,  -- hash of request body to detect mismatched retries
    response_status  INTEGER,               -- HTTP status code to return on duplicate
    response_body    JSONB,                 -- full response payload to return verbatim
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,  -- TTL — typically 24-48 hours
    user_id          BIGINT      NOT NULL   -- for multi-tenancy and cleanup
);

CREATE INDEX ON idempotency_keys(expires_at);  -- for cleanup job
```

**Why a dedicated table**: separates idempotency concerns from business logic. The business `transfers` table has its own schema optimised for payment queries. The idempotency table is optimised for key lookups.

**Why store the response**: on a duplicate request, you must return the exact same response as the first — same HTTP status, same body, same transfer ID. If you just say "already processed", the client doesn't know what ID to use for the transfer they initiated.

**Why store the request hash**: a client might send a different request body with the same idempotency key (a bug on the client side). You must detect this and return `422 Unprocessable Entity` — not silently process the wrong request or return the wrong cached response.

```java
String hash = DigestUtils.sha256Hex(objectMapper.writeValueAsString(requestBody));
if (!storedKey.getRequestHash().equals(hash)) {
    throw new IdempotencyKeyConflictException(
        "Request body does not match the original request for this idempotency key");
}
```

**TTL**: 24–48 hours is standard for payment APIs. After expiry, the key is deleted and a new request with that key is treated as fresh. A scheduled cleanup job deletes expired keys:

```sql
DELETE FROM idempotency_keys WHERE expires_at < now();
```

---

### The Full Request Flow

```
Client                          Server                         DB
  |                               |                             |
  |-- POST /transfers             |                             |
  |   Idempotency-Key: abc-123 -->|                             |
  |                               |-- SELECT * FROM             |
  |                               |   idempotency_keys          |
  |                               |   WHERE key = 'abc-123' --->|
  |                               |<-- 0 rows                   |
  |                               |                             |
  |                               |-- BEGIN TRANSACTION         |
  |                               |-- INSERT INTO               |
  |                               |   idempotency_keys          |
  |                               |   (key, status='PROCESSING',|
  |                               |    request_hash, expires_at)|
  |                               |   ON CONFLICT DO NOTHING -->|
  |                               |-- (if 0 rows inserted:      |
  |                               |   another request is        |
  |                               |   in-flight → return 409)   |
  |                               |                             |
  |                               |-- execute transfer logic    |
  |                               |-- INSERT INTO transfers ... |
  |                               |-- UPDATE idempotency_keys   |
  |                               |   SET status='COMPLETED',   |
  |                               |   response_body=...         |
  |                               |-- COMMIT ------------------>|
  |                               |                             |
  |<-- 201 Created                |                             |
  |    { transfer_id: ... }       |                             |
  |                               |                             |
  |   (network drops — client     |                             |
  |    retries)                   |                             |
  |                               |                             |
  |-- POST /transfers             |                             |
  |   Idempotency-Key: abc-123 -->|                             |
  |                               |-- SELECT * FROM             |
  |                               |   idempotency_keys          |
  |                               |   WHERE key = 'abc-123' --->|
  |                               |<-- status=COMPLETED,        |
  |                               |    response_body={...}      |
  |<-- 201 Created (cached)       |                             |
  |    { transfer_id: ... }       |                             |
  |   (same response — no         |                             |
  |    second transfer executed)  |                             |
```

---

### Handling the Race Condition

Two simultaneous requests arrive with the same idempotency key (network retry + original still in-flight):

The guard is `INSERT ... ON CONFLICT DO NOTHING`:
```sql
INSERT INTO idempotency_keys (key, status, request_hash, expires_at)
VALUES ('abc-123', 'PROCESSING', :hash, NOW() + INTERVAL '24 hours')
ON CONFLICT (key) DO NOTHING;
```

PostgreSQL's unique constraint on `key` ensures only one insert succeeds. The other gets 0 rows inserted. The application checks `getUpdateCount()`:

```java
int inserted = ps.executeUpdate();
if (inserted == 0) {
    // Another request is in-flight or already completed — check status
    IdempotencyKey existing = findByKey(key);
    if ("COMPLETED".equals(existing.getStatus())) {
        return existing.getCachedResponse();
    } else {
        throw new RequestInFlightException("Request with this key is already being processed");
    }
}
```

The insert must happen **inside** the same transaction as the business logic. If they are in separate transactions, a crash between them leaves the key in PROCESSING permanently (stuck key), and all retries get `409 Conflict` forever.

---

### The Crash Scenario — Why Outbox Is Required

Idempotency keys alone do not solve the following scenario:

1. Server inserts idempotency key with `status=PROCESSING`.
2. Server executes the transfer (DB rows written, money debited).
3. Server is about to update `status=COMPLETED` and send the response.
4. Server crashes.

On restart, the key is in `PROCESSING` state. The client retries. The server finds `PROCESSING` and returns `409 Conflict`. The client's retry is rejected. But the transfer was actually executed. The client has no way to find out.

The correct solution is the **Outbox pattern**: the transfer record, the idempotency key update, and an outbox event are all written in the same DB transaction. A separate processor reads the outbox and publishes the event to the client (via webhook, push notification, or a polling endpoint). Even if the server crashes, the outbox record survives. The processor retries sending the event until the client acknowledges it.

This gives you **exactly-once processing** (idempotency key prevents double execution) combined with **at-least-once delivery** (outbox processor retries until acknowledged) — the combination required for a correct payment system.

---

### Staff-level insight

Idempotency is a contract between client and server. The server's half is the deduplication mechanism. The client's half is: generate the key before the first attempt, store it durably (not just in memory — a crash before the response must not lose the key), reuse it for all retries, and treat a successful response as confirmation. A client that generates a new key on each retry defeats the entire mechanism — the server sees each retry as a new request. This is why you must document idempotency key semantics explicitly in your API contract, not just implement them silently on the server.

---

## 9. PostgreSQL with Raw Java (JDBC — no Spring)

The interview explicitly says no Spring. That means no JPA, no `@Transactional`, no `JdbcTemplate`. You connect to PostgreSQL directly with JDBC. This is the layer that Spring abstracts — knowing it proves you understand what Spring actually does.

---

### Connection Pool — Always Use HikariCP

`DriverManager.getConnection(url)` opens a new TCP connection to PostgreSQL every time. That involves a TCP handshake, PostgreSQL authentication, session setup — 10–50ms per call. In a service handling 1,000 requests per second, this is catastrophic.

A connection pool maintains a set of persistent, pre-opened connections and hands them out on demand. HikariCP is the fastest and most widely used pool for Java:

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost:5432/payments");
config.setUsername("app");
config.setPassword("secret");
config.setMaximumPoolSize(20);          // max 20 connections to PostgreSQL
config.setMinimumIdle(5);              // keep 5 alive even when idle
config.setConnectionTimeout(3000);     // fail fast if no connection available in 3s
config.setIdleTimeout(600_000);        // close connections idle for 10 min
config.setMaxLifetime(1800_000);       // recycle connections every 30 min (avoids stale)
config.setAutoCommit(false);           // always manage transactions explicitly

HikariDataSource dataSource = new HikariDataSource(config);
```

Always use `try-with-resources` — it calls `connection.close()` which returns the connection to the pool (does not actually close the TCP connection):

```java
try (Connection conn = dataSource.getConnection()) {
    // use conn
} // conn returned to pool automatically
```

---

### Transaction Management

Without Spring's `@Transactional`, you manage transactions manually. The pattern is always:

```java
try (Connection conn = dataSource.getConnection()) {
    conn.setAutoCommit(false);  // start transaction
    try {
        // ... do work ...
        conn.commit();
    } catch (Exception e) {
        conn.rollback();
        throw e;
    }
}
```

**Savepoints** — partial rollback within a transaction:
```java
Savepoint savepoint = conn.setSavepoint("after_debit");
try {
    creditAccount(conn, toAccountId, amount);
    conn.commit();
} catch (SQLException e) {
    conn.rollback(savepoint);  // undo credit but keep debit
    // handle partial failure
}
```

**Setting isolation level** — must be set before the first statement:
```java
conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);   // default
conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
```

---

### Executing Queries — Always Use PreparedStatement

Never concatenate user input into SQL. Always use `PreparedStatement` — it prevents SQL injection and reuses the query plan:

```java
String sql = "SELECT id, balance, version FROM accounts WHERE id = ?";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setLong(1, accountId);
    try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
            long id      = rs.getLong("id");
            long balance = rs.getLong("balance");
            int  version = rs.getInt("version");
        }
    }
}
```

**Batch inserts** — for inserting multiple rows efficiently:
```java
String sql = "INSERT INTO ledger_entries (id, account_id, amount, type) VALUES (?, ?, ?, ?)";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    for (LedgerEntry entry : entries) {
        ps.setObject(1, entry.getId());
        ps.setLong(2, entry.getAccountId());
        ps.setBigDecimal(3, entry.getAmount());
        ps.setString(4, entry.getType());
        ps.addBatch();
    }
    int[] rowCounts = ps.executeBatch();
}
```

**Retrieving generated keys** — for auto-generated IDs:
```java
try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO transfers (from_id, to_id, amount) VALUES (?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS)) {
    ps.setLong(1, fromId);
    ps.setLong(2, toId);
    ps.setBigDecimal(3, amount);
    ps.executeUpdate();
    try (ResultSet keys = ps.getGeneratedKeys()) {
        if (keys.next()) {
            long transferId = keys.getLong(1);
        }
    }
}
```

---

### SELECT FOR UPDATE — Pessimistic Locking

`SELECT FOR UPDATE` acquires an exclusive row lock. The row is locked until the transaction commits or rolls back. No other transaction can acquire a lock on the same row (read or write) until the lock is released.

```java
public Transfer executeTransfer(long fromId, long toId, long amountCents)
        throws SQLException, InsufficientFundsException {

    // Lock both accounts — always in consistent order to avoid deadlock
    long firstId  = Math.min(fromId, toId);
    long secondId = Math.max(fromId, toId);

    String lockSql = "SELECT id, balance FROM accounts WHERE id = ANY(?) FOR UPDATE";
    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        try {
            // Lock both rows in one query, consistent order enforced by ORDER BY
            String sql = "SELECT id, balance FROM accounts " +
                         "WHERE id IN (?, ?) ORDER BY id FOR UPDATE";
            Map<Long, Long> balances = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, firstId);
                ps.setLong(2, secondId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        balances.put(rs.getLong("id"), rs.getLong("balance"));
                    }
                }
            }

            if (balances.get(fromId) < amountCents) {
                conn.rollback();
                throw new InsufficientFundsException("Insufficient funds");
            }

            String debit  = "UPDATE accounts SET balance = balance - ? WHERE id = ?";
            String credit = "UPDATE accounts SET balance = balance + ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(debit)) {
                ps.setLong(1, amountCents);
                ps.setLong(2, fromId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(credit)) {
                ps.setLong(1, amountCents);
                ps.setLong(2, toId);
                ps.executeUpdate();
            }

            conn.commit();
            return new Transfer(fromId, toId, amountCents);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }
}
```

The `ORDER BY id` in the `SELECT ... FOR UPDATE` ensures both rows are locked in the same order regardless of which direction the transfer goes — preventing deadlock.

---

### SELECT FOR UPDATE SKIP LOCKED — Job Queue Pattern

Used when multiple consumer threads/pods compete to process rows from a queue table. `SKIP LOCKED` makes each consumer skip rows already locked by another consumer instead of blocking:

```java
public Optional<OutboxEvent> claimNextEvent(Connection conn) throws SQLException {
    String sql = """
        SELECT id, aggregate_type, aggregate_id, event_type, payload
        FROM outbox_events
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """;
    try (PreparedStatement ps = conn.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
            return Optional.empty();  // no pending events
        }
        UUID id = rs.getObject("id", UUID.class);
        // mark as PROCESSING in the same transaction
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE outbox_events SET status = 'PROCESSING' WHERE id = ?")) {
            update.setObject(1, id);
            update.executeUpdate();
        }
        return Optional.of(new OutboxEvent(id, rs.getString("event_type"), rs.getString("payload")));
    }
}
```

Without `SKIP LOCKED`: 10 consumer pods all queue up waiting for the same row. One processes it; the other 9 acquire the lock, see the row is now PROCESSING, and skip it manually. This is the thundering herd problem — wasteful serialisation.

With `SKIP LOCKED`: each consumer immediately gets a different row (or nothing if the queue is empty). True parallelism.

---

### Pagination — Cursor vs Offset

**Offset Pagination**

```java
String sql = "SELECT id, amount, created_at FROM transactions " +
             "WHERE account_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setLong(1, accountId);
    ps.setInt(2, pageSize);
    ps.setInt(3, pageNumber * pageSize);
    // ...
}
```

**How it works internally**: PostgreSQL fetches all matching rows up to `OFFSET + LIMIT`, discards the first `OFFSET` rows, and returns the rest. For `OFFSET 10000 LIMIT 20`, the DB fetches 10,020 rows, discards 10,000 — even if an index is used, those index entries still need to be traversed.

**Problems**:
- Performance degrades with high offsets — O(OFFSET) work regardless of LIMIT.
- Inconsistency under concurrent inserts: if a new row is inserted between page 1 and page 2, every subsequent page shifts by one — you either see a row twice or skip one.
- No way to efficiently resume — you must remember the page number.

**Use when**: admin UIs with small datasets, total result sets under a few thousand rows, or when arbitrary page jumping is required.

**Cursor (Keyset) Pagination**

```java
// First page — no cursor
String firstPage = """
    SELECT id, amount, created_at
    FROM transactions
    WHERE account_id = ?
    ORDER BY created_at DESC, id DESC
    LIMIT ?
    """;

// Subsequent pages — use last row's (created_at, id) as cursor
String nextPage = """
    SELECT id, amount, created_at
    FROM transactions
    WHERE account_id = ?
      AND (created_at, id) < (?, ?)   -- strictly less than the last seen row
    ORDER BY created_at DESC, id DESC
    LIMIT ?
    """;

try (PreparedStatement ps = conn.prepareStatement(nextPage)) {
    ps.setLong(1, accountId);
    ps.setTimestamp(2, lastSeenCreatedAt);
    ps.setLong(3, lastSeenId);
    ps.setInt(4, pageSize);
}
```

**How it works internally**: the composite condition `(created_at, id) < (?, ?)` is evaluated by the B-Tree index on `(created_at DESC, id DESC)`. PostgreSQL finds the position of the cursor row in the index and scans forward from there — O(log n) to find the start position, then O(LIMIT) to read the results. No matter how deep the page, it is always fast.

**Why two columns in the cursor**: if you only cursor on `created_at`, rows with identical timestamps produce non-deterministic ordering — the cursor position is ambiguous. Adding the unique `id` as a tiebreaker makes the ordering total and deterministic.

**Encoding the cursor for an API**: convert `(created_at, id)` to an opaque base64 string — prevents clients from manipulating cursor values and makes it clear the cursor is not a page number:

```java
String cursor = Base64.getEncoder().encodeToString(
    (lastCreatedAt.toInstant().toEpochMilli() + ":" + lastId).getBytes());
```

**Use when**: transaction history feeds, activity logs, infinite scroll, any large dataset where you don't need arbitrary page jumping.

---

### Common JDBC Pitfalls

**Not closing ResultSet/Statement**: every open ResultSet holds a server-side cursor in PostgreSQL. Leaking them exhausts the cursor limit and eventually fails with "too many open cursors". Always use try-with-resources.

**Forgetting rollback on exception**: if the critical section throws and you don't roll back, the connection is returned to the pool with an open transaction. The next user of that connection inherits a broken transaction state.

**Using `Statement` instead of `PreparedStatement`**: `Statement` with string concatenation is a SQL injection vulnerability. Never use it with user-supplied data.

**Setting autoCommit=true for multi-step operations**: each SQL statement commits immediately. A crash between debit and credit leaves the account in an inconsistent state. Always `setAutoCommit(false)` for anything involving more than one statement.

---

## 10. Event-Driven Systems

Event-driven architecture is the backbone of any large-scale payment system. Services communicate through events rather than synchronous calls — this decouples them, allows independent scaling, and provides a durable audit log of everything that happened. Understanding the trade-offs between messaging systems, delivery guarantees, and distributed transaction patterns is essential for a staff-level system design interview.

---

### Pub/Sub vs Message Queue — The Fundamental Distinction

These two models solve different problems and are frequently confused.

**Message Queue** (SQS, RabbitMQ): competing consumers share a queue. When consumer A reads a message, it is removed from the queue — consumer B never sees it. This is the right model for task distribution: N workers processing a queue of jobs, each job processed exactly once.

```
Producer → [Queue] → Consumer A (processes message)
                   → Consumer B (does NOT see the same message)
```

**Pub/Sub** (Kafka, SNS+SQS fan-out): every subscriber receives every message. Consumer group A and consumer group B each get their own copy of every message. Within a consumer group, messages are distributed across instances — but different groups each see the full stream.

```
Producer → [Topic] → Consumer Group A (fraud detection) — all messages
                   → Consumer Group B (audit log writer) — all messages
                   → Consumer Group C (notification service) — all messages
```

Kafka is primarily pub/sub, but consumer groups within Kafka behave like a queue (competing consumers within the group). This makes Kafka a superset — it can do both.

---

### Kafka Architecture — Internals

**Topic**: a logical stream of records, identified by name. Topics are append-only logs — producers write to the end, consumers read from any position.

**Partition**: a topic is split into N partitions, each a separate ordered log. Partitions are the unit of parallelism and distribution.

```
Topic "transfers" with 3 partitions:
  Partition 0: [t0, t3, t6, t9, ...]
  Partition 1: [t1, t4, t7, t10, ...]
  Partition 2: [t2, t5, t8, t11, ...]
```

Each partition is assigned to one broker (the leader) and optionally replicated to other brokers (followers). The leader handles all reads and writes; followers replicate.

**Ordering guarantee**: Kafka guarantees ordering within a partition but NOT across partitions. If you need all events for a given account to be processed in order, all events for that account must go to the same partition. You achieve this by setting the partition key to the account ID:

```java
producer.send(new ProducerRecord<>("transfers", accountId.toString(), transferJson));
// All events for the same accountId go to the same partition → in-order processing
```

**Offset**: the position of a record within a partition. Consumers track which offset they have processed by committing their offset back to Kafka (stored in an internal `__consumer_offsets` topic). On restart, a consumer resumes from the last committed offset.

**Consumer group**: N consumer instances sharing the work of consuming a topic. Kafka assigns partitions to consumers within the group — if there are 3 partitions and 3 consumer instances, each instance gets exactly 1 partition. If there are 3 partitions and 5 instances, 2 instances are idle. You cannot have more active consumers than partitions.

**Replication factor**: how many broker copies of each partition exist. RF=3 means the partition has 1 leader and 2 followers. The producer can configure `acks`:
- `acks=0`: fire-and-forget — producer doesn't wait for any acknowledgment. Fastest, but messages can be lost.
- `acks=1`: leader acknowledges — message is durable as long as the leader doesn't crash before followers replicate.
- `acks=all`: all in-sync replicas (ISR) acknowledge — message survives leader failure. Highest durability, higher latency.

**Retention**: unlike a queue, Kafka retains messages for a configurable time (default 7 days) or size. Consumers can seek to any offset — old, new, or time-based. This enables replay, backfill, and re-processing.

---

### Kafka vs SQS — When to Use Which

| Dimension | Kafka | SQS |
|-----------|-------|-----|
| Message retention | Days/weeks — full replay | Until consumed — no replay |
| Multiple consumers | Each consumer group gets all messages | One consumer gets each message |
| Throughput | Millions of messages/second | Tens of thousands/second |
| Ordering | Per-partition ordering (with key routing) | No ordering (FIFO queue available but limited) |
| Ops overhead | You manage brokers (or use MSK/Confluent Cloud) | Fully managed — zero ops |
| Use case | Event sourcing, audit log, analytics, fan-out | Task queues, job dispatch, decoupled services |
| Replay | Yes — seek to any offset or timestamp | No |
| Exactly-once | Yes — with Kafka transactions | No — at-least-once only |

**Choose Kafka when**: you need replay (re-processing events with a bug fix), multiple independent consumer types, high throughput (payment events, fraud signals), event sourcing, or an audit log that must be immutable and replayable.

**Choose SQS when**: you want zero ops, moderate throughput, simple task dispatch (resize image, send email, run report), and you don't need replay or multiple independent consumers.

**Common mistake**: using SQS where you need Kafka, then realising you can't re-process events after a consumer bug because messages have already been deleted.

---

### Delivery Guarantees

**At-most-once**: the message is delivered zero or one times — never more. Achieved by committing the offset before processing. If the consumer crashes after committing but before processing, the message is lost. Use when: losing occasional messages is acceptable — metrics, non-critical notifications, analytics sampling.

```
commit offset → process message
If crash between commit and process: message lost (at-most-once)
```

**At-least-once**: the message is delivered one or more times. Achieved by committing the offset only after successful processing. If the consumer crashes after processing but before committing, the message is redelivered — processed twice. Consumers must be idempotent to handle duplicates.

```
process message → commit offset
If crash between process and commit: message redelivered (at-least-once)
```

This is the correct default for payment events. Design consumers to be idempotent (use the event ID as an idempotency key).

**Exactly-once**: the message is processed exactly once, end-to-end. In Kafka, this requires:
1. **Idempotent producer** (`enable.idempotence=true`): producer assigns a sequence number to each message. The broker deduplicates retries from the same producer session.
2. **Kafka transactions**: the producer opens a transaction, publishes messages, and commits atomically. Consumers set `isolation.level=read_committed` — they only see committed messages, never in-progress or aborted ones.

```java
producer.initTransactions();
producer.beginTransaction();
producer.send(new ProducerRecord<>("payments", key, value));
producer.send(new ProducerRecord<>("audit-log", key, auditValue));
producer.commitTransaction(); // or producer.abortTransaction() on error
```

The trade-off: transactions add latency (additional coordinator round-trips) and reduce throughput. Use exactly-once only when duplicates cause genuine harm and idempotent processing is impractical.

---

### CQRS — Command Query Responsibility Segregation

CQRS separates the write path (commands) from the read path (queries). They use different models, different storage, and different services.

**Write model**: normalised, strongly consistent, optimised for transactional integrity. The source of truth. Writes go here. It emits events after every state change.

**Read model**: denormalised, eventually consistent, optimised for query patterns. Derived from the event stream. Rebuilt by replaying events. Read queries go here — no joins, pre-aggregated, indexed exactly for the access patterns needed.

```
Client
  │
  ├── POST /transfers ──► TransferCommandService ──► transfers table (write model)
  │                                                        │
  │                                                        ▼ TransferCreated event
  │                                             TransferProjector (consumer)
  │                                                        │
  │                                                        ▼ UPDATE user_transfer_summary
  │
  └── GET /accounts/5/transfers ──► TransferQueryService ──► user_transfer_summary (read model)
```

**The killer feature**: the read model is disposable. If you need a new query pattern (e.g. "transfers grouped by currency"), create a new projector that consumes the event log from the beginning and builds the new read model. The event log is the source of truth — read models are just materialised views of it.

**In a payments context**: the write model is the ledger (append-only, strongly consistent, source of truth for balances). The read model is the transaction history display — denormalised, sorted, paginated, with account names and currency symbols pre-joined.

---

### Outbox Pattern — Atomic DB Write + Event Publish

The problem: a payment service writes a transfer to the DB and must also publish a `TransferCreated` event to Kafka. These are two separate systems — there is no distributed transaction that spans both. If the DB write succeeds but the Kafka publish fails (or the service crashes between the two), the event is lost. Downstream services (fraud detection, notification, audit) never learn about the transfer.

The solution — Transactional Outbox:

**Step 1**: write the business record and an outbox row in the same DB transaction. The outbox row IS the event, stored in the DB.

```sql
BEGIN;
INSERT INTO transfers (id, from_account, to_account, amount, status)
VALUES (gen_random_uuid(), 5, 10, 100.00, 'COMPLETED');

INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, published)
VALUES (
    gen_random_uuid(),
    'Transfer',
    :transfer_id,
    'TransferCreated',
    '{"from": 5, "to": 10, "amount": 100.00}',
    false
);
COMMIT;
-- If this commit fails, neither the transfer nor the outbox row exists.
-- If it succeeds, both exist atomically.
```

**Step 2**: a separate processor reads unpublished outbox rows and publishes them to Kafka.

```java
// Runs on a scheduled thread or as a Debezium CDC connector
List<OutboxEvent> pending = findUnpublished();
for (OutboxEvent event : pending) {
    kafkaProducer.send(new ProducerRecord<>(event.getEventType(), event.getPayload()));
    markAsPublished(event.getId());  // update outbox row — also retryable
}
```

**Step 3**: mark events as published. If the processor crashes after publishing but before marking, the event is republished on restart — **at-least-once delivery from DB to Kafka**. Consumers must be idempotent (which they should be anyway).

**Debezium CDC**: instead of a polling processor, use Debezium to stream PostgreSQL WAL changes to Kafka. Every insert into `outbox_events` appears as a Kafka message within milliseconds. No polling loop, no DB load from polling, sub-second latency. The outbox row can even be deleted immediately — Debezium captures the insert event before deletion.

**Why this is the correct pattern for FinTech**: it gives you **dual write safety** — the event is published if and only if the DB transaction commits. No phantom events (payment event without a DB record) and no silent failures (DB record without a downstream event).

---

### Saga Pattern — Distributed Transactions Without 2PC

A distributed transaction spans multiple services. The traditional solution — Two-Phase Commit (2PC) — requires a distributed coordinator and locks resources across services until the commit. Under partial failure, it can leave resources locked indefinitely. At Revolut's scale, 2PC is impractical.

The Saga pattern breaks a distributed transaction into a sequence of local transactions, each with a compensating transaction (an undo operation) that reverses its effect if a later step fails.

**Example — cross-border transfer saga**:

```
Step 1: DebitSourceAccount      compensation: CreditSourceAccount (undo debit)
Step 2: ApplyFXConversion       compensation: ReverseConversion
Step 3: CreditTargetAccount     compensation: DebitTargetAccount (undo credit)
Step 4: NotifyUser              compensation: (none — notification already sent, idempotent)
```

If Step 3 fails, run compensations in reverse: reverse Step 2, credit back Step 1. The system is back to its initial state.

**Choreography saga**: each service listens for the previous service's completion event and emits its own. No central coordinator.

```
DebitService emits → DebitCompleted
FXService consumes → emits FXConverted
CreditService consumes → emits CreditCompleted  OR CreditFailed
If CreditFailed → FXService compensates → DebitService compensates
```

Simple to implement for 2–3 steps. Becomes a tangle of event dependencies as complexity grows — hard to trace what went wrong and in what order.

**Orchestration saga**: a central saga orchestrator issues commands and waits for replies. The orchestrator owns the state machine.

```java
class TransferSagaOrchestrator {
    void execute(TransferRequest request) {
        SagaState state = new SagaState(request);
        state = debitService.debit(state);
        if (state.isFailed()) { compensate(state); return; }
        state = fxService.convert(state);
        if (state.isFailed()) { compensate(state); return; }
        state = creditService.credit(state);
        if (state.isFailed()) { compensate(state); return; }
        notifyUser(state);
    }
}
```

Clearer control flow, easier to add steps, single place to add logging and monitoring. The orchestrator becomes a point of coupling — all steps depend on it. Prefer orchestration for complex, multi-step business processes where visibility matters.

**Critical constraints on compensations**:
1. Compensations must be idempotent — the orchestrator may retry them on failure.
2. Sagas do not provide isolation — between Step 1 (debit) and Step 3 (credit), another transaction might read the debited source account and see a lower balance. If Step 3 fails and Step 1 is compensated, that concurrent transaction saw a phantom state. Design for this — do not show intermediate saga states to users (mark transfers as PENDING until the saga completes).
3. Not all steps have meaningful compensations — sending a notification, generating a PDF. For these, make them idempotent and accept that they may happen multiple times.

---

## 11. Deployment, Metrics & Monitoring

Deploying a payment service is not like deploying a blog. A bad deploy at Revolut can mean failed transactions, incorrect balances, or a regulatory incident. Understanding deployment strategies, what to measure, and how to detect problems before users notice is what distinguishes a staff engineer from one who just writes code.

---

### Rolling Deployment

The default Kubernetes deployment strategy. Pods are replaced one at a time — a new pod starts, passes its health check, and an old pod is terminated. During the rollout, old and new versions run simultaneously.

**Requirement**: old and new code must be compatible with the same DB schema at all times during the rollout. This is why the expand-contract migration pattern exists — you never rename a column in one step, because during a rolling deploy, old pods are still reading the old column name while new pods expect the new name.

**Rollback**: `kubectl rollout undo deployment/payment-service` — Kubernetes starts replacing new pods with old pods, one at a time. Fast and automatic. The rollback window is as long as Kubernetes retains the previous ReplicaSet (configurable, default 10).

---

### Blue-Green Deployment

Two identical environments run in parallel. Blue is live (serving production traffic). Green is the new version, deployed and tested in isolation. When Green is validated, the load balancer switches all traffic from Blue to Green in one atomic step. Blue stays up as an instant rollback target — if anything goes wrong with Green, the load balancer switches back in seconds.

```
Before cutover:
  Users → Load Balancer → Blue (v1)    Green (v2, idle)

After cutover:
  Users → Load Balancer → Blue (v1, idle)    Green (v2)

On rollback:
  Users → Load Balancer → Blue (v1)    Green (v2, idle)
```

**Advantages**: zero-downtime cutover, instant rollback, Green is fully tested before any user traffic hits it.

**DB schema challenge**: during the cutover window, both Blue and Green connect to the same DB. If Green's migration added a column that Blue's code doesn't know about, Blue silently ignores it — fine. If Green's migration dropped a column that Blue reads, Blue crashes — catastrophic. This is why you must use expand-contract: never drop in the same release as adding the replacement.

**Stateful sessions**: if users have active sessions (JWT, cookies) tied to a Blue instance, they may see errors after cutover if Green doesn't honour the same session format. Ensure session tokens are stateless (JWT) or stored in a shared session store (Redis).

**Cost**: running two full environments simultaneously doubles the infrastructure cost during the deployment window. Acceptable for critical payment services where risk outweighs cost.

---

### Canary Release

Route a small percentage of traffic (e.g. 1–5%) to the new version while the majority continues to hit the old version. Monitor the canary's error rates, latency, and business metrics. Gradually increase traffic to the canary if it is healthy. Roll back instantly if metrics degrade.

```
Users → Load Balancer → v1 (95% of traffic)
                      → v2/canary (5% of traffic)
```

**Traffic splitting mechanisms**:
- Load balancer weight rules (nginx, AWS ALB weighted target groups).
- Service mesh (Istio, Linkerd) traffic splitting — precise percentage routing at the L7 layer.
- Feature flags — route specific user segments (e.g. internal employees, low-value accounts) to the canary.

**What to monitor on the canary**:
- **Error rate delta**: is the canary's 5xx rate higher than the baseline? Even a 0.1% increase in a payment service is significant.
- **p99 latency delta**: is the canary slower? Compare against the same time window on v1.
- **Business metrics**: is PISR (payment success rate) lower on the canary? Are more transfers going to the fraud queue?
- **DB metrics**: is the canary running more expensive queries? More lock waits?

**Automated canary analysis**: tools like Argo Rollouts or Flagger automate the analysis — they compare canary metrics against baseline, automatically pause or roll back if thresholds are exceeded. This removes the human delay in catching a bad deploy.

**Staff-level insight**: a canary that only handles low-risk traffic (e.g. only small-value transfers, only internal test accounts) is not a representative canary. It will pass analysis while a catastrophic bug in high-value transfer logic goes undetected. Make sure your canary traffic sample mirrors the full production traffic distribution.

---

### Expand-Contract Migration Pattern (for DB + API)

This is the universal pattern for zero-downtime changes to any shared interface — DB columns, API fields, Kafka event schemas.

**Three phases**:

**Phase 1 — Expand**: add the new structure alongside the old. Both old code (reading old structure) and new code (reading new structure) work simultaneously. No behaviour change yet.

Example — renaming `amount` to `amount_cents`:
```sql
-- Phase 1 migration: add new column
ALTER TABLE transfers ADD COLUMN amount_cents BIGINT;
```
```java
// Phase 1 code: write to both columns
transfer.setAmount(amount);           // old column
transfer.setAmountCents(amount * 100); // new column
// Read from old column — new column not yet fully populated
```

**Phase 2 — Migrate**: backfill old data into the new structure. Switch reads to the new structure. Old structure still written to for backward compatibility.

```sql
-- Backfill
UPDATE transfers SET amount_cents = amount * 100 WHERE amount_cents IS NULL;
```
```java
// Phase 2 code: read from new column
return transfer.getAmountCents();
// Write to both (still) — old pods may still be running during rolling deploy
```

**Phase 3 — Contract**: remove the old structure. All traffic is on the new code. Old pods are gone.

```sql
-- Phase 3 migration
ALTER TABLE transfers DROP COLUMN amount;
```
```java
// Phase 3 code: only use amount_cents. Old column removed.
```

This same pattern applies to:
- Renaming API response fields — keep old field alongside new field, deprecate, remove.
- Kafka event schema changes — add new field, consumers start reading it, old field removed in later version.
- Changing FK references — add new FK, backfill, remove old FK.

---

### Key Metrics for a Payment Service

Metrics are your early warning system. A drop in business metrics often precedes user complaints by minutes — if you are monitoring the right things, you can detect and roll back a bad deploy before most users notice.

**PISR — Payment Initiation Success Ratio**

`PISR = (confirmed_orders / initiated_orders) * 100`

The most important single metric. A drop in PISR means payments are failing. Normal is > 99%. An alert at < 98% should wake someone up. An alert at < 95% is a P1 incident.

Segment PISR by: payment method (card vs bank transfer), country, amount range, user tier. A PISR drop only for high-value transfers points to a specific code path; a drop across all categories points to infrastructure.

**Latency — p50, p99, p999**

p50 (median) tells you what the typical user experiences. p99 tells you what the worst 1% experiences. p999 tells you the extreme tail — relevant for high-value customers who may be retrying.

For a payment transfer API, target:
- p50 < 100ms
- p99 < 500ms
- p999 < 2s

A p99 spike without a p50 spike suggests a specific slow code path (e.g. a missing index causing occasional slow DB queries) rather than general overload.

**Error rate by type**

Separate 4xx (client errors — wrong input, auth failures) from 5xx (server errors — bugs, DB failures, timeout). A spike in 5xx after a deploy is a rollback trigger. A spike in 4xx may be a client-side issue or an API contract change that broke clients.

**Kafka consumer lag**

Consumer lag = latest offset in partition - consumer's committed offset. If lag grows, consumers are falling behind producers. In a payment system, growing lag means:
- Fraud detection is delayed — payments process before fraud signals are evaluated.
- Notifications are delayed — users don't see their transfer confirmation.
- Audit log is delayed — regulatory reporting may be stale.

Alert on: lag > 10,000 messages, or lag growing for > 5 consecutive minutes (not just a transient spike).

**DB connection pool saturation**

If all connections in the HikariCP pool are in use, new requests queue at `getConnection()`. This adds latency directly to your API response time. If the queue fills beyond `connectionTimeout`, requests fail with `SQLTimeoutException`.

Monitor: `HikariCP pool active connections / max pool size`. Alert at > 80% utilisation.

**Dead Letter Queue (DLQ) depth**

A DLQ holds messages that a consumer failed to process after N retries. A growing DLQ means events are permanently failing — silent data loss. In a payment system, a DLQ full of `TransferCreated` events means transfers that never notified the user, never hit the fraud system, never reached the ledger.

Alert on: any message in the DLQ. Investigate immediately — never let DLQ messages pile up.

**Slow query log and lock wait time**

PostgreSQL's `pg_stat_statements` shows the top queries by total time. After a deploy, a new slow query (missing index, N+1 pattern, missing partition pruning) will appear in the top 10. Monitor `pg_stat_activity` for queries in `lock_wait` state — growing lock waits indicate contention introduced by new code.

---

### Distributed Tracing

Every request into the payment service is assigned a `trace-id`. This ID is propagated through every downstream call — other services, Kafka messages, DB queries (via application logs), and async jobs. Each step creates a `span` — a timed segment with the trace-id, operation name, and metadata.

At the end, you can query the tracing backend for `trace-id: abc-123` and see the complete timeline of everything that happened — which service was called, how long each step took, where it failed.

```
GET /transfers/abc-123  [trace-id: xyz]
  │── AccountService.getAccount [50ms]
  │── FraudService.check [120ms]
  │── DB: INSERT INTO transfers [30ms]
  └── Kafka: publish TransferCreated [5ms]
```

**Log correlation**: include `trace-id` in every log line. When a user reports "my transfer failed at 14:32", search logs for their `trace-id` — every log line from every service involved in that request is correlated.

**Kafka propagation**: include `trace-id` in Kafka message headers — not just the payload. Consumers extract it and use it as the trace context for their processing span. This lets you trace a payment from the initial API call all the way through async fraud detection, ledger posting, and notification delivery.

**Staff-level insight**: distributed tracing is only valuable if you instrument the right operations. Don't just trace the entry point — trace DB queries, Kafka publishes, calls to external payment processors (Visa, SWIFT). The slow operation is almost never where you expect it to be, and without tracing, you are guessing.
