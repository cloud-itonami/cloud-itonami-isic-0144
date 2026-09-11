# cloud-itonami-isic-0144

Open Occupation Blueprint for **ISIC Rev. 4 0144**: Raising of sheep and goats.

This repository implements a forkable OSS **sheep-and-goat farm operations
coordinator**: a facility-management and record-keeping robot manages flock
husbandry logging (feeding/breeding/shearing/health-check batch data),
grazing-rotation/shearing/breeding scheduling, and supply procurement under a
governor-gated actor, so a sheep/goat operation keeps its own operational
records and maintains full transparency over decisions.

**Maturity: `:implemented`.** `src/flockops/` implements the
`FlockOpsAdvisor` (`flockops.advisor`) and the independent
`FlockOperationsGovernor` (`flockops.governor`), composed by
`flockops.operation` following the itonami actor pattern (ADR-2607011000):
`advise -> govern -> phase-gate -> commit | escalate | hold`. 31 tests /
103 assertions green (`kbb -M:test`).

`flockops.operation` is a synchronous stub of this flow (see its
docstring) — production wiring into a `langgraph-clj` StateGraph with
`interrupt-before`/checkpoint-based human-in-the-loop resume for escalated
operations is deferred, mirroring `cloud-itonami-isic-0145`'s own
`swineops.operation`.

## What this does NOT do

This actor coordinates **back-office logistics only**. It explicitly does **NOT**:

- **Direct animal handling** — remains the farm operator's exclusive authority
- **Veterinary treatment decisions** — remains the veterinarian/farm operator authority
- **Culling decisions** — economic and ethical authority remains human
- **Direct treatment administration** — any proposal for direct treatment is a hard block
- **Outbreak declarations / animal-health authority contact** — a flagged
  health/welfare concern (e.g. suspected scrapie or bluetongue) is surfaced
  for human/veterinary judgment only; this actor never itself declares an
  outbreak or notifies authorities

## HARD invariants (always hold, never overridable)

1. **facility-not-registered** — the request's `facility-id` must resolve to a
   registered facility (paddock/pen complex) in the Store before any proposal
   can proceed
2. **no-execution** — every proposal's `:effect` must be `:propose` (the governor
   never directly handles animals, never administers treatment, never
   finalizes a culling decision)
3. **treatment-or-culling-blocked** — `:administer-treatment` and
   `:finalize-culling-decision` proposals are unconditionally, permanently blocked
4. **op-not-allowed** — any op outside the closed allowlist below is rejected
5. **flock-count-invalid** — `:log-husbandry-record` with a non-positive count is rejected

## Always-escalate operations (human sign-off, regardless of confidence)

- `:flag-animal-health-concern` — any welfare or health concern (e.g.
  suspected scrapie or bluetongue) → automatic escalation
- `:order-supplies` over its category cost threshold (default 500 currency
  units; see `flockops.facts/supply-categories`)
- Any proposal with confidence below the Governor's floor (0.7)

## Operational requests (closed allowlist, all `:effect :propose`)

```text
:log-husbandry-record
  — record flock count, weight, health status, offspring count (lambing/
    kidding), and fleece (shearing) weight -- feeding/breeding/shearing/
    health-check batch data logging
  — requires a registered facility; non-positive counts are rejected

:schedule-farm-operation
  — propose a grazing-rotation, shearing, or breeding operation
  — does NOT make treatment or culling decisions

:flag-animal-health-concern
  — surface a disease, injury, or welfare concern (e.g. suspected scrapie)
  — ALWAYS escalates for human review

:order-supplies
  — procurement for feed, veterinary supplies, shearing equipment
  — escalates if cost exceeds its category threshold
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs the
physical domain work**. Here a facility-management robot handles:

- Flock husbandry record logging and entry (feeding/breeding/shearing/health-check)
- Appointment/operation scheduling and reminders
- Supply inventory and ordering
- Audit ledger maintenance

The **FlockOperationsGovernor** is the independent safety layer that gates all proposals
before a robot action is executed. The governor never dispatches hardware directly;
`:high`/`:safety-critical` actions (such as escalated health/welfare concerns or
high-cost supply orders) require human sign-off.

## Core Contract

```text
operational request (log, schedule, concern, order)
        |
        v
FlockOpsAdvisor -> FlockOperationsGovernor -> phase gate -> commit, or escalate for human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated operation can dispatch a robot action the governor refuses, suppress an
operating record, or hide a health/welfare concern without governor approval and
audit evidence.

## Module structure

Mirrors `cloud-itonami-isic-0145` (`swineops.*`) module-for-module:

- `flockops.facts` — reference data: supply-category cost thresholds, breeds,
  health/welfare-concern vocabulary
- `flockops.registry` — pure independent verification functions (cost/count/confidence)
- `flockops.store` — `Store` protocol + in-memory `MemStore` (facility registration lookup)
- `flockops.advisor` — `Advisor` protocol + `MockAdvisor` (the sealed LLM/decision node)
- `flockops.governor` — `FlockOperationsGovernor`: hard invariants + escalation gates
- `flockops.phase` — 0→3 rollout phase gate
- `flockops.operation` — composes advisor → governor → phase into one operation run
- `flockops.sim` — demo runner (`kbb -M:run`)

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISIC Rev. 4 `0144`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Testing

```bash
kbb -M:test   # 31 tests / 103 assertions
kbb -M:lint   # clj-kondo, 0 errors / 0 warnings
kbb -M:run    # demo runner
```

## License

AGPL-3.0-or-later.
