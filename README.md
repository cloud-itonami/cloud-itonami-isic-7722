# cloud-itonami-isic-7722

**Renting of video tapes and disks** — ISIC Rev.4 class 7722.

A coordination-only actor for video/disk rental store back-office operations, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-rental-record, schedule-restocking-operation, flag-customer-concern, coordinate-supply-order (all `:effect :propose`).
- **Two HARD governor checks** (permanent, un-overridable):
  1. **Account verified** — target rental-account/inventory record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — this actor NEVER finalizes a content-rating admission override and NEVER finalizes a damage-liability determination. Any proposal whose content attempts to finalize either is permanently blocked. An op outside the closed four-op allowlist is folded into the same check.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: rental-record logging only (approval-gated)
  - Phase 2: + restocking scheduling, supply-order coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (customer concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL scope exclusions

This actor coordinates the *back office* of a video/disk rental storefront — it never makes the decisions that ultimately gate a customer's rental or a damage claim. It structurally cannot:

- **Finalize a content-rating admission override** — waiving or overriding an age-rating/content-rating check to admit a rental (e.g. an 18+-rated title). That is always either a hard permanent block, or (for `:flag-customer-concern`) an always-escalate op requiring human sign-off.
- **Finalize a damage-liability determination** — deciding who owes what for lost or damaged rental inventory. Same treatment.

The governor's `scope-excluded-terms` are deliberately phrased as the *finalization/execution action* ("finalize the age-rating admission override", "finalize the liability determination"), never as a bare noun ("rating", "age", "liability", "damage"), because this actor's own legitimate happy-path proposals — especially `:flag-customer-concern`, whose entire purpose is to talk *about* age-rating admission doubts and damage/liability disputes — routinely use those bare nouns. `governor-test` and `governor-contract-test` both assert the default mock-advisor proposals never self-trip this check.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/vidrentalops/governor_test.clj` — unit tests of governor hard checks, scope exclusion, and the self-trip regression test
- `test/vidrentalops/advisor_test.clj` — advisor proposal shape and consistency
- `test/vidrentalops/phase_test.clj` — rollout phase logic
- `test/vidrentalops/governor_contract_test.clj` — full graph integration, audit trail
- `test/vidrentalops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `vidrentalops.store` — SSoT (MemStore, String-keyed account directory, append-only ledger)
- `vidrentalops.advisor` — contained intelligence node (mock + real-LLM seam)
- `vidrentalops.governor` — independent compliance layer
- `vidrentalops.phase` — staged rollout (0→3)
- `vidrentalops.operation` — langgraph-clj StateGraph
- `vidrentalops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and the per-actor coverage ADR in `com-junkawasaki/root` `90-docs/adr/` for design decisions.
