# Payment Ledger

A merchant payment backend that keeps double-entry books and reconciles them against a provider's settlement reports.

## Language

### Parties

**Merchant**:
The single business whose payments this system handles.
_Avoid_: Tenant, customer, client

**Provider**:
The external payment processor that moves money. In this project it is a deterministic mock.
_Avoid_: Gateway, PSP, acquirer

**Operator**:
A person who investigates discrepancies and records corrections.
_Avoid_: Admin, support

### Payments

**Payment**:
A merchant's request to collect an amount, which ends as succeeded or failed.
_Avoid_: Transaction, charge, order

**Capture**:
The provider's movement of a payment's money. There is no separate authorization step.
_Avoid_: Charge, settlement

**Refund**:
A return of some or all of a captured payment's amount. It is a business event of its own, never a reversal.
_Avoid_: Reversal, chargeback

**Refund reservation**:
An amount held against a payment while a refund's outcome is pending, so concurrent refunds cannot exceed the capture.
_Avoid_: Hold, lock

**Platform fee**:
The amount the platform earns on a capture, fixed when the payment is accepted and not returned on refund.
_Avoid_: Commission, provider fee

**Provider operation**:
One capture or refund the system has asked the provider to perform, identified by a key that never changes across retries.
_Avoid_: Attempt, request, job

**Attempt**:
One try at sending a provider operation. An operation can have many.

**Unknown outcome**:
The state of a provider operation that may or may not have happened, such as after a timeout. It is not a decline.
_Avoid_: Failed, timed out, pending

### Ledger

**Business event**:
A fact that must post at most once, such as "this capture was confirmed". Many deliveries can carry the same one.
_Avoid_: Message, transport event

**Journal**:
An immutable, balanced record of one business event.
_Avoid_: Transaction, ledger entry

**Entry**:
One debit or credit line in a journal.
_Avoid_: Line, posting

**Posting**:
The act of committing a journal to the books.
_Avoid_: Booking, recording

**Posting sequence**:
The position of a journal in the order its postings committed.
_Avoid_: Journal ID, timestamp

**Merchant deficit**:
A negative merchant payable balance, such as the fee left over after a full refund.
_Avoid_: Debt, overdraft

### Reconciliation

**Settlement report**:
An immutable list of movements the provider states it made during a declared interval.
_Avoid_: Statement, payout report

**Reconciliation run**:
One comparison of a settlement report against the books at a fixed point in time.
_Avoid_: Job, sync

**Finding**:
The classified result of a reconciliation run for one operation or report row, including matches.
_Avoid_: Mismatch, exception, error

**Discrepancy**:
Contradictory facts about a provider operation, from reconciliation or from a provider callback.
_Avoid_: Conflict, break

**Correction**:
An operator's recorded action that posts a journal to bring the books in line with a proven discrepancy.
_Avoid_: Adjustment, fix, manual journal

**Reversal**:
A correction journal that swaps the debits and credits of an earlier journal, which stays unchanged.
_Avoid_: Refund, void, rollback

### Events

**Outbox event**:
A notification committed with the state change it describes, delivered afterward.
_Avoid_: Message

**Sink**:
The configured HTTP destination for outbox events.
_Avoid_: Consumer, subscriber, webhook
