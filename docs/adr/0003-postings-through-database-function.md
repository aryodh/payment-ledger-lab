# Postings go through a database function

The application role has no insert, update, or delete privilege on journals and entries. It can only execute `post_journal`, which validates the whole journal, including balance, before inserting it.

Balance spans several rows, so a plain `CHECK` constraint can't express it. Validating only in Java would let any bug or stray SQL statement write an unbalanced journal. Keeping this logic in SQL makes it harder to unit test and splits domain rules across two languages, and that cost is accepted because the books are the one thing this system cannot get wrong.

Application credentials can't change the books, but a database administrator still can.
