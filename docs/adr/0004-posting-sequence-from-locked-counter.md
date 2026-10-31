# Posting sequence comes from a locked counter row

`post_journal` takes its sequence number with `UPDATE ledger_posting_counter SET value = value + 1 RETURNING value`. The row lock is held until commit, so sequence order equals commit order and the sequence has no gaps.

Timestamps and PostgreSQL sequences don't give this, because a transaction holding an earlier value can commit after one holding a later value. With either, an "as of" balance could change after it was read and a journal cursor could skip a row. A gapless sequence also lets the invariant checker detect a missing journal.

The cost is that all postings run one at a time from the counter update until commit. This is acceptable because postings are short and there is one merchant. To stay out of deadlock cycles, every transaction takes this lock last. If posting throughput ever becomes the limit, the upgrade path is one counter per account set, or giving up commit order and accepting a stable-but-lagging read position.
