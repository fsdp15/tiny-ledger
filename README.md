# Tiny Ledger

An HTTP API for recording money movements: deposits, withdrawals, current balance and transaction
history. Everything is held in memory, so restarting the app starts from a clean slate.

## Running it

You need a JDK on your `PATH`. The build targets Java 21 and Gradle will fetch a matching toolchain
if you don't already have one.

```bash
./gradlew bootRun
```

The API is on `http://localhost:8080`, with a Swagger UI on
`http://localhost:8080/swagger-ui.html` if you would rather click than curl.

```bash
./gradlew test
```

## The API

| Endpoint | |
|---|---|
| `POST /api/v1/accounts` | Open an account |
| `POST /api/v1/accounts/{id}/deposits` | Pay money in |
| `POST /api/v1/accounts/{id}/withdrawals` | Take money out, if the balance covers it |
| `GET /api/v1/accounts/{id}/balance` | Current balance |
| `GET /api/v1/accounts/{id}/transactions` | History, newest first |
| `GET /api/v1/accounts/{id}/transactions/{txId}` | One transaction |

Both movement endpoints require an `Idempotency-Key` header. The amount carries no currency, because
an account holds one for life.

The history endpoint takes `type` (`DEPOSIT` or `WITHDRAWAL`), `from` and `to` (ISO-8601, both
inclusive), `cursor` (the `nextCursor` from the previous page) and `limit` (20 by default, capped at
100).

Failures come back as a problem document with a stable `code` to switch on:

```json
{"title":"Unprocessable Entity","status":422,
 "detail":"balance is 69.50 EUR, cannot withdraw 9999.00 EUR",
 "code":"INSUFFICIENT_FUNDS"}
```

## Examples

Open an account. It is opened in euros unless you ask for something else:

```bash
curl -X POST localhost:8080/api/v1/accounts
```

```json
{"accountId":"43334fa5-596d-4601-8ed8-362bd6772a2a","currency":"EUR","balance":0.00,"openedAt":"2026-08-19T20:20:30.891501Z"}
```

```bash
export ACCOUNT=43334fa5-596d-4601-8ed8-362bd6772a2a
```

Pay some money in:

```bash
curl -X POST localhost:8080/api/v1/accounts/$ACCOUNT/deposits \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"amount":100.00,"reference":"salary"}'
```

```json
{"transactionId":"c3f1caa2-4c90-49a7-9178-584711414d17","sequence":1,"type":"DEPOSIT","amount":100.00,"currency":"EUR","balanceAfter":100.00,"recordedAt":"2026-08-19T20:20:30.904165Z","reference":"salary"}
```

Send that exact request again and nothing moves — you get the first transaction back, same
`transactionId`. Reuse the key for a *different* request and it is refused, because that is a client
bug rather than a retry (`409 IDEMPOTENCY_KEY_REUSED`).

Take money out:

```bash
curl -X POST localhost:8080/api/v1/accounts/$ACCOUNT/withdrawals \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-2' \
  -d '{"amount":30.50,"reference":"groceries"}'
```

```json
{"transactionId":"557892fc-...","sequence":2,"type":"WITHDRAWAL","amount":30.50,"balanceAfter":69.50, ...}
```

Read the balance and the history:

```bash
curl localhost:8080/api/v1/accounts/$ACCOUNT/balance
curl "localhost:8080/api/v1/accounts/$ACCOUNT/transactions?limit=2"
```

```json
{"accountId":"43334fa5-...","currency":"EUR","balance":69.50}

{"transactions":[{"sequence":2,"type":"WITHDRAWAL","amount":30.50,"balanceAfter":69.50, ...},
                 {"sequence":1,"type":"DEPOSIT","amount":100.00,"balanceAfter":100.00, ...}],
 "hasMore":false}
```

When `hasMore` is true, pass the returned `nextCursor` back as `?cursor=`.

Not every currency is decimal. Open one in krona (`-d '{"currency":"ISK"}'`) and the minor unit
disappears all the way out to the JSON:

```
1500   -> {"amount":1500,"currency":"ISK","balanceAfter":1500}
10.50  -> 400  "ISK allows at most 0 decimal place(s)"
```

Things that get rejected:

| Request | Response |
|---|---|
| Withdraw more than the balance | `422 INSUFFICIENT_FUNDS` |
| `10.005` into a EUR account | `400 INVALID_AMOUNT_SCALE` |
| `"amount": -5.00` | `400 VALIDATION_FAILED` |
| A movement with no `Idempotency-Key` | `400 MISSING_HEADER` |
| A key already used for a different body | `409 IDEMPOTENCY_KEY_REUSED` |
| Anything on an unknown account | `404 ACCOUNT_NOT_FOUND` |

## How it fits together

`LedgerController` → `LedgerCommandService` / `LedgerQueryService` → `AccountRepository`. Commands
and queries are separate classes because a ledger divides that way naturally, but it is a split of
code and not of storage: there is one store and reads are strongly consistent. The repository is an
interface with one in-memory implementation, so a real database would be a change of adapter.

**The history is the source of truth.** There is no stored balance to keep in sync — each entry
carries the running total it produced, and the balance is the one on the newest entry, so reading it
is a single field access rather than a sum over the history. It can always be *rebuilt* by summing,
and a test does exactly that after hammering an account from 32 threads.

**Money is a type, not a `BigDecimal`.** It pairs an amount with a currency and normalises the scale
to that currency's minor unit: two decimals for EUR, none for ISK, three for KWD. Without that,
`BigDecimal.equals` is scale-sensitive and `1.0` would not equal `1.00`. Amounts too precise for
their currency are rejected rather than rounded, and amounts are typed as `BigDecimal` all the way
out to the request body, so no `double` ever exists to round them on the way in.

**Writes are serialised per account, reads are never blocked.** `read balance → check → append` is a
check-then-act, so without a lock two concurrent withdrawals can both pass the check and overdraw.
Each `Account` holds a lock across that stretch and swaps in a new immutable history under it, so
readers get a consistent balance and history from one volatile read and never wait. The lock is
private rather than synchronizing on the account itself, which callers could lock from outside.
`AccountConcurrencyTest` races 40 threads at one account and fails if the lock is removed. Appending
copies the list, making a write O(n) in the history — fine at this size, though a real one would
append to a log and project the balance separately.

**Idempotency keys are required, and come from the client.** The failure that matters is a request
that succeeded and whose response was lost, so a server-generated key would be useless: the client
never saw it. Same key and same body replays the original transaction, and same key with a different
body is a `409`. The key is claimed before the movement runs and only given back if the movement was
rejected before anything was recorded, so a key left claimed — still running, or abandoned by an
attempt that failed without saying whether money moved — refuses the retry rather than repeating it.
Refusing a legitimate retry is recoverable, a second movement is not.
Required rather than optional because the payment rails treat it that way — ISO 20022 makes
`EndToEndId` mandatory — even though Stripe and Adyen keep it optional to ease a first integration.
Keys are scoped per account and held in an LRU with a TTL, since they are client-supplied and would
otherwise grow without limit.

**Paging is by cursor.** Read the first page of 10 from a 25-entry history, let three deposits land,
then ask for page 2: at offset 10 the window has shifted and you get back three rows you have
already seen. The cursor is the sequence number of the last row you read, so page 2 asks for
`sequence < 16` and stays correct. Ordering comes from that sequence rather than the timestamp,
because timestamps tie under load and clocks can move backwards.

Limits and the default currency live in `LedgerProperties` (`ledger.*` in `application.yml`).

## Assumptions

1. **No authentication**, per the brief. `accountId` comes from the URL and is trusted, so anyone who
   knows an id can read and move that account's money. In production it would have to come from the
   authenticated principal. A deliberate hole, not an oversight.
2. **Accounts are opened explicitly** and hold one currency for life. Creating one implicitly on
   first deposit would let a typo'd id silently mint a new account.
3. **No overdrafts.** A withdrawal that would take the balance below zero is refused.
4. **Amounts are rejected, not rounded**, when they are more precise than the currency allows.
   Quietly rounding a payment is how money goes missing.
5. **Single entry.** A real ledger is double-entry: every movement writes two legs and all entries
   sum to zero. That is the accounting-correct model, but it doubles the entry model and the history
   response for an exercise this size.
6. **The transaction limit is one flat number** across every currency. A real one would be per
   currency.
7. **Everything is in memory** and dies with the process, as the brief suggests.

## Left out on purpose

- **Authentication, logging and monitoring** — excluded by the brief.
- **Persistence.** The repository interface is the seam, and the per-account lock is where a JDBC
  adapter would put a row lock or an optimistic version column.
- **Asynchronous writes.** A ledger write has to be confirmed before the response, or the caller
  doesn't know whether their money moved. Going async means `202`, a status endpoint and
  eventually-consistent balances — a product decision rather than a technical one.
- **Rate limiting and velocity limits.** Rate limiting belongs at the gateway; velocity caps are
  fraud and AML rules needing a rules engine and a policy owner, not an `if` in a service.
- **Reversals.** A ledger is append-only, so a mistake is corrected by posting a compensating entry,
  never by editing history. `POST /transactions/{id}/reversals` would be the next thing I'd add.
- **Separate list endpoints for deposits and withdrawals.** `?type=DEPOSIT` already does it, and two
  ways to read the same list is upkeep with no new capability.
