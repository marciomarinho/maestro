# 0018. Propagate trace context through the outbox row, in application code

- **Status:** Accepted
- **Date:** 2026-08-12

## Context

One payment should produce one trace across all four services — the [operations posture](../operations/README.md) has promised this since Phase 0, and the envelope has carried a `traceParent` field since Phase 1 that nothing ever populated.

The hard part is the asynchronous hop. HTTP propagation is a solved problem: instrumentation copies the `traceparent` header from the incoming request to every outgoing call. But between payment-api deciding to authorize and the router hearing about it sit a database row and a polling relay ([ADR-0004](0004-transactional-outbox.md)). The relay publishes on its own schedule, on its own thread, inside its own trace — a scheduled poll that has nothing to do with any payment. Automatic instrumentation of the relay's producer would stamp every record with the *poll's* context, which is worse than none: every payment published in one batch would appear to share a trace with the batch, not with the request that caused it.

There is also a framework fact that shaped the mechanics, discovered the hard way. Spring Boot 4 gates its W3C propagator behind `@ConditionalOnEnabledTracingExport`: switch span export off and a **noop propagator silently takes over** — services stop writing `traceparent` to anything, with no warning. This platform deliberately runs without a trace backend in the everyday loop (the observability stack is an overlay), so "export off" is the common case, and the common case must still propagate: the outbox rows written during it must carry real contexts.

## Decision

The trace context crosses the asynchronous hop **as data, written by application code at the two moments it is on the right thread**:

1. **At append.** `OutboxWriter` reads the calling thread's current context — the HTTP request span in payment-api, the listener's processing span in the router — renders it as a W3C `traceparent` string and stores it on the outbox row, inside the same transaction as the state change. The append is the last moment the originating context is available; everything after runs on relay threads.
2. **At publish.** `OutboxRelay` copies the stored value onto the Kafka record as the standard `traceparent` header. The relay never opens a span of its own for the payment; it is a courier, not a participant.
3. **At consume.** Nothing custom: Spring Kafka's listener observation (`observation-enabled: true`) extracts the header before the handler runs, so the listener span joins the payment's trace by the same mechanism any HTTP server would.

Two supporting choices:

- **Propagation is unconditional; export is opt-in.** `lib-observability` contributes a plain W3C `TextMapPropagator` exactly when `management.tracing.export.enabled` is `false`, undoing the framework's coupling of the two. The compose observability overlay enables export and points it at the trace backend; nothing else changes.
- **The header is the canonical carrier.** The envelope's JSON `traceParent` field is populated too, for a human reading a raw record, but consumers rely on the header, because framework extraction happens before any application code sees the message.

## Consequences

**Positive.** A payment's trace is genuinely end to end: merchant request → outbox → Kafka → router → acquirer call → outcome event → payment-api and ledger consumers, one trace id throughout. An upstream caller's own trace context is honoured, so Maestro appears as a segment of the merchant's trace rather than a black box. The mechanism survives crashes and retries for free — the context is in the row, and a republished row republishes its context.

**Negative.** `trace_parent` is application-managed state: a future writer that forgets to populate it (or a new event path that bypasses `OutboxWriter`) silently detaches its consumers' spans, and no test framework will notice on its behalf. The integration test that drives a `traceparent` from HTTP to the Kafka header is the guard, and it guards only the paths it exercises. The custom propagator bean is coupled to a Boot conditional (`ConditionalOnEnabledTracingExport`) whose semantics could shift in an upgrade.

**Neutral.** The relay's own operational spans (poll timing, batch sizes) are simply absent; the relay's health is observed through the outbox gauges instead, which suit a queue better than spans do.

## Alternatives considered

### Instrument the relay's `KafkaTemplate` with observation

The idiomatic-looking choice: set `observation-enabled` on the producer and let the framework do everything. Rejected because the context it would propagate is the wrong one — the relay's scheduled poll — and it would *overwrite* a manually set header. Correctness here means the framework must not help.

### A span-per-event created by the relay from the stored context

The relay could restore each row's context, open a "publish" child span and let instrumentation inject it. This yields a prettier trace (an explicit publish hop) at the cost of the relay creating spans against contexts belonging to requests that finished long ago, on threads that never ran them. Restoring foreign contexts is exactly the kind of cleverness that turns into misattributed spans under concurrency; copying a string is not.

### Rely on the envelope's JSON field and extract in each listener

No Kafka header, application code extracts `traceParent` after deserialisation. Rejected because by the time application code runs, the listener observation has already started a span in the wrong trace; joining afterwards means custom scope surgery in every consumer, forever, versus one header write in one relay.

## Revisit when

A consumer outside the JVM (or outside Spring) joins the platform and needs the context in a different shape; or a Boot upgrade changes the export/propagation coupling this record works around, at which point `w3cPropagationDespiteDisabledExport` should be deleted rather than ported.
