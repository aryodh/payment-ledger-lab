# The outbox delivers over HTTP, with no broker

The outbox relay posts events to one configured HTTP sink. Kafka is the usual choice here, but this project has one consumer and no need for replay or fan-out, so a broker would only add an always-on dependency. Its one job would be feeding a consumer built to justify it.

The sink URL is configuration. A small demo sink lets the ledger run alone, and the webhook service can replace it later without a code change to the relay's delivery logic.

Delivery is at least once and unordered. A crash after the sink responds and before the relay records it produces a duplicate, so sinks deduplicate by event ID. A broker wouldn't remove this, because the relay could still crash between publishing and recording.
