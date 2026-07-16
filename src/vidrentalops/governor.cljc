(ns vidrentalops.governor
  "VidRentalGovernor -- the independent compliance layer that earns
  the VidRentalAdvisor the right to commit. The advisor has no notion
  of whether a rental-account/inventory record is actually registered
  and verified, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has
  silently drifted into a permanently out-of-scope decision area, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor's scope is deliberately narrow -- VIDEO/DISK RENTAL
  OPERATIONS COORDINATION ONLY (checkout/return/inventory-condition
  logging, new-release/inventory-restock scheduling proposals,
  customer-concern flagging, supplier procurement coordination). It
  NEVER performs or authorizes:
    - finalizing a content-rating admission override (waiving or
      overriding an age-rating/content-rating check to admit a rental)
    - finalizing a damage-liability determination (who owes what for
      lost/damaged rental inventory)

  Both of those are ALWAYS either a hard permanent block (this
  governor) or an always-escalate op (`:flag-customer-concern`) --
  NEVER an auto-commit-eligible op in any phase, per this fleet's Wave
  4 person-facing-service safety guardrail (ADR-2607152500). This
  actor coordinates the back office around those decisions; it never
  makes them.

  Two HARD checks, ALL permanent, un-overridable by any human approval:

    1. Account unverified         -- the target rental-account/
                                      inventory record must exist AND
                                      be independently confirmed
                                      `:registered?`/`:verified?` in
                                      the store before ANY proposal for
                                      it may commit or even escalate.
                                      Never trusts a proposal's own
                                      claim about the account --
                                      re-derived from the account's own
                                      store record, the same 'ground
                                      truth, not self-report'
                                      discipline every sibling actor's
                                      governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op, rationale, summary,
                                      citations or draft value touches
                                      the ACT of finalizing a content-
                                      rating admission override, or the
                                      ACT of finalizing a damage-
                                      liability determination, is a
                                      HARD, PERMANENT block -- this
                                      actor's charter excludes that
                                      territory structurally, not as a
                                      rollout milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  IMPORTANT (self-trip discipline): `scope-excluded-terms` below are
  phrased as the FINALIZATION/EXECUTION ACTION ('finalize the age-
  rating admission override', 'finalize the liability determination'),
  never as a bare noun ('rating', 'age', 'liability', 'damage'). This
  actor's own legitimate happy-path proposals -- especially
  `:flag-customer-concern`, whose entire purpose is to talk ABOUT
  age-rating admission doubts and damage/liability disputes -- routinely
  use those bare nouns in their default rationale text. A bare-noun
  term list would self-trip the actor on its own default mock-advisor
  proposals; `governor-test` and `governor-contract-test` both assert
  this never happens.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-customer-concern` -- ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is. `vidrentalops.phase` independently agrees:
  `:flag-customer-concern` is never a member of any phase's `:auto`
  set either -- two layers, not one."
  (:require [clojure.string :as str]
            [vidrentalops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-rental-record :schedule-restocking-operation
    :flag-customer-concern :coordinate-supply-order})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-customer-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as attempting to
  directly FINALIZE a content-rating admission override or a damage-
  liability determination -- this actor's two permanently out-of-scope
  decision areas. Phrased as the finalization/execution ACTION, never
  as a bare noun, so this list never matches inside this actor's own
  legitimate proposals (which routinely discuss age-rating/damage/
  liability as topics without ever finalizing them). Scanned across
  the proposal's op/summary/rationale/cites/value, never trusting the
  advisor's own framing of its intent."
  ["finalize the age-rating admission override" "finalize age-rating admission override"
   "finalize the content-rating admission override" "finalize content-rating admission override"
   "override the age-rating admission decision" "grant the rating admission exception"
   "grant admission despite the rating" "authorize the age-rating override"
   "confirm the rating override decision" "waive the age-rating admission requirement"
   "clear the content rating for admission" "admit despite the rating restriction"
   "コンテンツレーティングの入場可否を確定" "年齢確認の入場可否を確定" "レーティング除外を確定して入場させる"
   "年齢制限の適用除外を確定" "入場可否の最終判断を下す"
   "finalize the damage-liability determination" "finalize damage-liability determination"
   "finalize the liability determination" "determine the final liability"
   "assess final liability" "authorize the liability charge"
   "finalize the liability charge" "determine who is liable for the damage"
   "rule on the liability dispute" "finalize the damage charge decision"
   "損害賠償責任を確定" "賠償責任の最終判断を下す" "損害額の最終決定を下す" "賠償請求額を確定する"])

;; ----------------------------- checks -----------------------------

(defn- account-unverified-violations
  "The target rental-account/inventory record must exist AND be
  independently `:registered?`/`:verified?` in the store -- never
  trust the proposal's own `:account-id` claim without a store lookup."
  [{:keys [account-id]} st]
  (let [r (store/account st account-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :account-unverified
        :detail (str account-id " は未登録または未検証のレンタルアカウント/在庫記録 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing a content-rating admission
  override or finalizing a damage-liability determination, regardless
  of confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "コンテンツレーティング入場可否の確定判断/損害賠償責任の最終判断に踏み込む提案は永久に禁止"}])))

(defn check
  "Censors a VidRentalAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [account-id (or (:account-id proposal) (:account-id request))
        hard (into []
                   (concat (account-unverified-violations {:account-id account-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :account-id (:account-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
