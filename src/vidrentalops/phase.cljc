(ns vidrentalops.phase
  "Phase 0->3 staged rollout for the ISIC-7722 RENTING OF VIDEO TAPES
  AND DISKS operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- rental-record (checkout/return/
                                     inventory-condition) logging
                                     allowed, every write needs human
                                     approval.
    Phase 2  assisted-coordination-- adds restocking scheduling,
                                     supply-order coordination
                                     proposals, still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                     `:log-rental-record`/
                                     `:schedule-restocking-operation`/
                                     `:coordinate-supply-order` may
                                     auto-commit. `:flag-customer-
                                     concern` NEVER auto-commits, at
                                     any phase.

  `:flag-customer-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Flagging an age-rating admission
  doubt, a damage/loss dispute, or fraud suspicion always needs a
  human to actually look at it.
  `vidrentalops.governor`'s own `always-escalate-ops` enforces the
  same invariant independently -- two layers, not one, agree on this."
  (:require [vidrentalops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-customer-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                                :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-rental-record}                                               :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-rental-record :schedule-restocking-operation
                                               :coordinate-supply-order}                                          :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-rental-record :schedule-restocking-operation :coordinate-supply-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-customer-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a VidRentalGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
