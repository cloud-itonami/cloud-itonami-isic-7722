(ns vidrentalops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-7722`: this
  repo previously had NO demo page and no generator at all.

  Everything on the emitted page is produced by ACTUALLY EXECUTING this
  repo's own actor stack at build time --
  `vidrentalops.operation` (the langgraph StateGraph) ->
  `vidrentalops.governor` (the independent censor) ->
  `vidrentalops.store` (the SSoT) -- against a fresh
  `store/seed-db`. Nothing on the page is hand-typed:

    - the account rows come from `store/all-accounts` (the seeded
      `store/demo-data` directory: account-1, account-2, account-3);
    - the action-gate rows are DERIVED at build time from
      `governor/allowed-ops`, `governor/always-escalate-ops` and
      `phase/phases` -- so if the op contract or the rollout ladder
      changes, the table changes with it and cannot drift into a lie;
    - every hold reason is the governor's own `:rule` keyword and its
      own `:detail` string, copied out of the audit fact it wrote;
    - the approver column is MEASURED, not assumed: it reads the
      approver back out of the committed record exactly as this repo's
      `store/commit-record!` actually retained it, and discloses the
      provenance (retained in record / audit trail only / auto-commit).
      If the store is later changed, the disclosure follows.

  Determinism: the page contains no timestamps and no randomness; the
  seeded store, the mock advisor and the graph are all deterministic,
  so two consecutive runs are byte-identical. Verify with
  `clojure -M:dev:render-html a.html && clojure -M:dev:render-html b.html && cmp a.html b.html`.

  Build-time invariant (see `-main`): the run MUST produce at least one
  `:governor-hold` fact, and at least one of those must be a HARD hold
  (a non-empty `:basis`, i.e. an un-overridable governor violation that
  never reaches a human). If a future refactor makes the governor
  silently stop holding, this generator THROWS and the build fails --
  the guarantee is executable, not a comment.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [vidrentalops.advisor :as advisor]
            [vidrentalops.governor :as governor]
            [vidrentalops.operation :as op]
            [vidrentalops.phase :as phase]
            [vidrentalops.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator "rental-desk-coordinator-1")

(defn- ctx
  "Actor context. `phase` is the rollout phase `vidrentalops.phase`
  gates against."
  [ph]
  {:actor-id "coord-1" :actor-role :rental-desk-coordinator :phase ph})

(defn- step!
  "Runs ONE coordination request through the real compiled actor and
  returns what actually happened. When the actor interrupts before
  `:request-approval` (the human-in-the-loop gate) and `:approver` is
  supplied, a human approval is resumed into the same thread; when no
  approver is supplied the request is left where the actor put it.

  Returns {:id :label :op :account-id :phase :disposition :audit
           :approver :interrupted?}."
  [actor {:keys [id label request phase approver]}]
  (let [first-pass (g/run* actor {:request request :context (ctx phase)}
                           {:thread-id id})
        interrupted? (= :interrupted (:status first-pass))
        final (if (and interrupted? approver)
                (g/run* actor {:approval {:status :approved :by approver}}
                        {:thread-id id :resume? true})
                first-pass)
        state (:state final)]
    {:id           id
     :label        label
     :op           (:op request)
     :account-id   (:account-id request)
     :phase        phase
     :disposition  (:disposition state)
     :audit        (vec (:audit state))
     ;; the exact record this run handed to the :commit node (nil when
     ;; held) -- kept so the rental log can be joined back to the run
     ;; that produced it by identity, never by a lossy op+account match.
     :record       (:record state)
     :approver     (when interrupted? approver)
     :interrupted? interrupted?}))

(defn- direct-actuation-advisor
  "A deliberately misbehaving advisor that takes the mock proposal and
  flips `:effect` to `:commit` -- i.e. claims to actuate directly
  instead of proposing. Exercises `governor/effect-not-propose-violations`
  end to end. (Same hook `vidrentalops.sim` uses.)"
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn- out-of-charter-advisor
  "A deliberately misbehaving advisor that proposes an op outside the
  closed `governor/allowed-ops` allowlist -- an advisor proposing
  something it was never authorized to propose. Exercises the
  `:op-not-allowed` half of `governor/scope-exclusion-violations`."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      {:op         :finalize-damage-liability
       :account-id (:account-id request)
       :summary    (str (:account-id request) " の破損案件について賠償責任の最終判断を行う")
       :rationale  "この操作はこのアクターの closed allowlist の外にある。"
       :cites      [(:account-id request)]
       :effect     :propose
       :value      {:account-id (:account-id request)}
       :confidence 0.97})))

(defn run-demo!
  "Drives a fresh seeded store through a scenario that reaches every
  disposition this actor can produce, using ONLY the account ids this
  repo's own `store/demo-data` actually seeds (account-1 and account-2
  registered+verified, account-3 registered but NOT verified) plus one
  deliberately absent id (account-99):

    - account-1 logs a checkout at phase 1 (assisted-logging: the op is
      writable but not auto-eligible, so the phase gate escalates even
      though the governor was clean) -- a human approves and it commits;
    - account-1 logs a return at phase 3 -- governor-clean, high
      confidence, auto-commits with no human;
    - account-2 schedules a restocking operation and coordinates a
      supply order at phase 3 -- both auto-commit;
    - account-1 flags a customer concern at phase 3 -- ALWAYS escalates
      (both `governor/always-escalate-ops` and `phase/phases` agree,
      independently), a human approves and it commits;
    - account-2 tries a supply order at phase 1, where the op is not
      yet writable -- the phase gate holds it (`:phase-disabled`); this
      is a SOFT/rollout hold, not a governor violation;
    - four HARD holds, none of which ever reaches a human:
        account-99 (absent from the directory)      -> :account-unverified
        account-3  (registered, NOT verified)       -> :account-unverified
        account-2, advisor claims :effect :commit   -> :effect-not-propose
        account-1, advisor drifts into finalizing an age-rating
          admission override                        -> :scope-excluded
        account-1, advisor proposes an op outside the closed allowlist
                                                    -> :op-not-allowed

  Returns {:db store :steps [step ..]} -- every value the page renders
  is read back out of these, never re-typed."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        rogue   (op/build db {:advisor (direct-actuation-advisor)})
        charter (op/build db {:advisor (out-of-charter-advisor)})
        steps
        [(step! actor
                {:id "s01-checkout-phase1" :phase 1 :approver coordinator
                 :label "Checkout logged at phase 1 -- writable but not auto-eligible, human approves"
                 :request {:op :log-rental-record :account-id "account-1"
                           :patch {:item-id "DVD-00042" :movement "checkout"
                                   :condition "good"}}})
         (step! actor
                {:id "s02-return-phase3" :phase 3
                 :label "Return logged at phase 3 -- governor-clean, auto-commits"
                 :request {:op :log-rental-record :account-id "account-1"
                           :patch {:item-id "DVD-00099" :movement "return"
                                   :condition "good"}}})
         (step! actor
                {:id "s03-restock-phase3" :phase 3
                 :label "Restocking schedule at phase 3 -- auto-commits"
                 :request {:op :schedule-restocking-operation :account-id "account-2"
                           :patch {:title "New Release Batch W29" :quantity 25}}})
         (step! actor
                {:id "s04-supply-phase3" :phase 3
                 :label "Supply-order coordination at phase 3 -- auto-commits"
                 :request {:op :coordinate-supply-order :account-id "account-2"
                           :patch {:supplier "Kanda Media Distributors"
                                   :sku "BD-2026-NEWREL"}}})
         (step! actor
                {:id "s05-concern-phase3" :phase 3 :approver coordinator
                 :label "Customer concern at phase 3 -- ALWAYS escalates, human approves"
                 :request {:op :flag-customer-concern :account-id "account-1"
                           :patch {:concern "age-rating admission doubt raised at the desk"
                                   :confidence 0.9}}})
         (step! actor
                {:id "s06-supply-phase1" :phase 1
                 :label "Supply order attempted at phase 1 -- op not writable yet, rollout hold"
                 :request {:op :coordinate-supply-order :account-id "account-2"
                           :patch {:supplier "Kanda Media Distributors"
                                   :sku "BD-2026-BACKCAT"}}})
         (step! actor
                {:id "s07-absent-account" :phase 3
                 :label "Checkout for an account absent from the directory -- HARD hold"
                 :request {:op :log-rental-record :account-id "account-99"
                           :patch {:item-id "DVD-00007" :movement "checkout"}}})
         (step! actor
                {:id "s08-unverified-account" :phase 3
                 :label "Supply order for a registered-but-unverified account -- HARD hold"
                 :request {:op :coordinate-supply-order :account-id "account-3"
                           :patch {:supplier "Kanda Media Distributors"
                                   :sku "BD-2026-NEWREL"}}})
         (step! rogue
                {:id "s09-direct-actuation" :phase 3
                 :label "Advisor claims direct actuation (:effect :commit) -- HARD hold"
                 :request {:op :schedule-restocking-operation :account-id "account-2"
                           :patch {:title "New Release Batch W30" :quantity 10}}})
         (step! actor
                {:id "s10-scope-drift" :phase 3
                 :label "Advisor drifts into finalizing an age-rating admission override -- HARD hold, permanent"
                 :request {:op :log-rental-record :account-id "account-1"
                           :out-of-scope? true
                           :patch {:item-id "DVD-00123" :movement "checkout"}}})
         (step! charter
                {:id "s11-op-not-allowed" :phase 3
                 :label "Advisor proposes an op outside the closed allowlist -- HARD hold"
                 :request {:op :finalize-damage-liability :account-id "account-1"
                           :patch {:item-id "DVD-00123"}}})]]
    {:db db :steps steps}))

;; ----------------------------- derived facts -----------------------------

(defn- audit-trail
  "The full advisor/governor/approval audit trail this run produced,
  in scenario order. NOTE this is a superset of `store/ledger`: the
  actor only persists `:committed` and hold facts to the SSoT ledger,
  so `:approval-requested` / `:approval-granted` exist ONLY here.

  ⚠ Do NOT count outcomes from this trail. An auto-commit emits its
  `:committed` fact TWICE (once from the `:decide` node, once again
  from the `:commit` node, and the `:audit` channel reducer is `into`),
  so a naive count here over-reports commits. `store/ledger` holds
  exactly one fact per outcome and is the canonical source for counts;
  this trail is only for facts the SSoT never persists."
  [steps]
  (into [] (mapcat :audit) steps))

(defn- record-index
  "record -> the step that produced it. Joining the committed rental
  log back to its run by record identity, rather than by (op,
  account-id), matters: this scenario deliberately runs the SAME op on
  the SAME account both as a phase-1 human-approved escalation and as
  a phase-3 auto-commit, and a lossy join smears the human approver
  from the first onto the second -- i.e. claims a human approved
  something no human ever saw."
  [steps]
  (into {} (for [s steps :when (:record s)] [(:record s) s])))

(defn- hard-hold? [fact]
  (and (= :governor-hold (:t fact)) (boolean (seq (:basis fact)))))

(defn- phase-hold? [fact]
  (and (= :governor-hold (:t fact)) (empty? (:basis fact))))

(defn- first-writable-phase
  "The earliest rollout phase at which `op` may write at all, read out
  of `phase/phases` itself."
  [op]
  (first (for [p (sort (keys phase/phases))
               :when (contains? (:writes (get phase/phases p)) op)]
           p)))

(defn- gate-verdict
  "Describes what the CURRENT default phase does with `op`, derived
  from `governor/always-escalate-ops` and `phase/phases` -- never
  hand-written prose."
  [op]
  (let [ph (get phase/phases phase/default-phase)]
    (cond
      (contains? governor/always-escalate-ops op)
      [:warn "ALWAYS human approval · never auto-commits at any phase"]

      (not (contains? (:writes ph) op))
      [:critical "not writable at this phase"]

      (contains? (:auto ph) op)
      [:ok "auto-commits when governor-clean"]

      :else
      [:warn "human approval (writable, not auto-eligible)"])))

(defn- approver-attribution
  "MEASURED, not assumed. Reads the approver back out of the committed
  record exactly as `store/commit-record!` retained it in THIS repo,
  and, only when the record actually dropped it, falls back to the
  approval fact from THAT record's own run -- so the page never
  renders a blank (which would read as 'nobody approved') and never
  borrows another run's approver.

  Returns [class text]. Re-derived on every render, so if the store is
  later changed to retain (or to drop) the approver, this disclosure
  follows without editing the renderer."
  [step record]
  (let [retained (or (get-in record [:payload :approved-by])
                     (get-in record [:value :approved-by]))
        granted  (some #(when (= :approval-granted (:t %)) (:by %)) (:audit step))]
    (cond
      retained    [:ok (str retained " (retained in record)")]
      (nil? step) [:muted "unmatched record — provenance not derivable"]
      granted     [:warn (str granted " (audit trail only — not retained in record)")]
      :else       [:muted "auto-commit — no human approver"])))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))

(defn- td [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- span [class text] (str "<span class=\"" (name class) "\">" text "</span>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kv
  "Deterministic rendering of a payload map -- sorted by key so the
  page never depends on map iteration order."
  [m drop-keys]
  (->> (apply dissoc m drop-keys)
       (sort-by (comp str key))
       (map (fn [[k v]] (str (kw-name k) "=" (if (string? v) v (pr-str v)))))
       (str/join ", ")))

(defn- table [caption headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       (if caption (str "    <p class=\"muted\">" caption "</p>\n") "")))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; --- section 1: accounts ---

(defn- account-status
  "Counted from `store/ledger` (exactly one fact per outcome), never
  from the audit trail (which emits `:committed` twice per auto-commit)."
  [ledger {:keys [account-id]}]
  (let [mine (filter #(= account-id (:account-id %)) ledger)
        committed (count (filter #(= :committed (:t %)) mine))
        hard (count (filter hard-hold? mine))
        soft (count (filter phase-hold? mine))
        parts (cond-> []
                (pos? committed) (conj (span :ok (str committed " committed")))
                (pos? hard) (conj (span :critical (str hard " HARD held")))
                (pos? soft) (conj (span :warn (str soft " rollout held"))))]
    (if (seq parts)
      (str/join " · " parts)
      (span :muted "no activity in this run"))))

(defn- accounts-section [db ledger]
  (section
   "Rental accounts (SSoT directory)"
   (str "Read straight out of <code>vidrentalops.store/all-accounts</code> on the seeded store. "
        "A proposal for an account that is not <em>both</em> <code>:registered?</code> and "
        "<code>:verified?</code> in this directory can never commit and never even escalate — "
        "the governor re-derives that from the store record, never from the proposal's own claim.")
   (table nil
          ["Account" "Name" "Registered" "Verified" "This run"]
          (for [{:keys [account-id name registered? verified?] :as a} (store/all-accounts db)]
            (td (code account-id)
                (esc name)
                (if registered? (span :ok "yes") (span :critical "no"))
                (if verified? (span :ok "yes") (span :critical "no"))
                (account-status ledger a))))))

;; --- section 2: action gate ---

(defn- action-gate-section []
  (section
   "Action gate (VidRentalGovernor × rollout phase)"
   (str "Derived at build time from <code>governor/allowed-ops</code>, "
        "<code>governor/always-escalate-ops</code> and <code>phase/phases</code> — not a "
        "hand-maintained copy, so it cannot drift away from the code. Current default phase: "
        "<strong>" phase/default-phase " · "
        (esc (:label (get phase/phases phase/default-phase)))
        "</strong>. Confidence floor: <code>" governor/confidence-floor "</code>.")
   (table
    (str "Ops outside this closed allowlist are a scope violation by construction. "
         "Finalizing a content-rating admission override, or finalizing a damage-liability "
         "determination, is a HARD permanent block in every phase — this actor coordinates the "
         "back office around those decisions and never makes them.")
    ["Op" "First writable phase" (str "Phase " phase/default-phase " disposition")]
    (for [op (sort-by kw-name governor/allowed-ops)]
      (let [[class text] (gate-verdict op)]
        (td (code op)
            (if-let [p (first-writable-phase op)]
              (str "phase " p " · " (esc (:label (get phase/phases p))))
              (span :critical "never"))
            (span class (esc text))))))))

;; --- section 3: scenario run ---

(defn- disposition-cell [{:keys [disposition audit interrupted? approver]}]
  (let [hold (last (filter #(= :governor-hold (:t %)) audit))]
    (cond
      (and (= :hold disposition) (hard-hold? hold))
      (span :critical (str "HARD hold · " (esc (str/join ", " (map kw-name (:basis hold))))))

      (= :hold disposition)
      (span :warn (str "rollout hold · " (esc (kw-name (or (:phase-reason hold) :hold)))))

      (and (= :commit disposition) interrupted?)
      (span :ok (str "escalated → approved by " (esc approver) " → committed"))

      (= :commit disposition)
      (span :ok "auto-committed (no human)")

      :else (span :warn (esc (kw-name (or disposition :unknown)))))))

(defn- scenario-section [steps]
  (section
   "Scenario run (this build)"
   (str "Each row is one real <code>vidrentalops.operation</code> graph run: "
        "intake → advise → govern → decide → (approval) → commit | hold. "
        "Nothing here is a fixture — the dispositions are whatever the governor and the phase "
        "gate actually returned when this page was generated.")
   (table nil
          ["#" "Phase" "Op" "Account" "What happened" "Scenario"]
          (map-indexed
           (fn [i {:keys [phase op account-id label] :as s}]
             (td (inc i)
                 (esc phase)
                 (code op)
                 (code account-id)
                 (disposition-cell s)
                 (span :muted (esc label))))
           steps))))

;; --- section 4: hard holds ---

(defn- holds-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (section
     "Governor holds (why the actor refused)"
     (str "Every hold this run produced, with the governor's own rule keyword and its own "
          "<code>:detail</code> text. A <span class=\"critical\">HARD</span> hold is permanent and "
          "un-overridable: it never reaches a human at all, so no approval can rescue it. A "
          "<span class=\"warn\">rollout</span> hold is the staged-rollout phase gate declining an op "
          "that is not yet enabled — that one is a milestone, not a charter limit.")
     (table nil
            ["Kind" "Op" "Account" "Rule" "Governor's own detail"]
            (for [{:keys [op account-id violations phase-reason] :as f} holds]
              (if (hard-hold? f)
                (td (span :critical "HARD · permanent")
                    (code op)
                    (code account-id)
                    (code (str/join ", " (map (comp kw-name :rule) violations)))
                    (esc (str/join " / " (map :detail violations))))
                (td (span :warn "rollout · phase gate")
                    (code op)
                    (code account-id)
                    (code (kw-name (or phase-reason :phase-gate)))
                    (esc (str "この op はフェーズ " (:phase f) " ("
                              (:label (get phase/phases (:phase f))) ") ではまだ書き込みが有効化されていない")))))))))

;; --- section 5: committed rental log ---

(defn- rental-log-section [db steps]
  (section
   "Committed rental log (SSoT mutations)"
   (str "The append-only committed-proposal history from "
        "<code>vidrentalops.store/rental-log</code> — the only rows in this whole page that "
        "actually mutated the SSoT. The approver column is measured, not assumed: it is read back "
        "out of the record exactly as this repo's <code>commit-record!</code> retained it, and "
        "labels its own provenance.")
   (table nil
          ["Op" "Account" "Payload" "Approver"]
          (let [by-record (record-index steps)]
            (for [{:keys [op account-id payload value] :as r} (store/rental-log db)]
              (let [[class text] (approver-attribution (get by-record r) r)]
                (td (code op)
                    (code account-id)
                    (esc (kv (or payload value {}) [:account-id :approved-by]))
                    (span class (esc text)))))))))

;; --- section 6: audit ledger ---

(defn- ledger-row [{:keys [t op account-id disposition basis summary]}]
  (td (esc (kw-name t))
      (code op)
      (code account-id)
      (esc (or (when (seq basis) (str/join ", " (map kw-name basis)))
               (kw-name (or disposition ""))))
      (span :muted (esc (or summary "")))))

(defn- ledger-section [db]
  (section
   "Audit ledger (persisted to the SSoT)"
   (str "<code>vidrentalops.store/ledger</code> — the append-only immutable decision-fact log. "
        "Which account a proposal targeted, which op, on what basis, and whether it committed or "
        "was held, is always a query over this log.")
   (table nil ["Fact" "Op" "Account" "Basis / disposition" "Summary"]
          (map ledger-row (store/ledger db)))))

;; --- summary ---

(defn- summary-section [db steps ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        hard  (filter hard-hold? holds)
        rules (sort (distinct (map kw-name (mapcat :basis hard))))]
    (section
     "This build at a glance"
     (str "Counted from the run itself (outcomes from <code>store/ledger</code>, which holds "
          "exactly one fact per request), so these numbers cannot disagree with the tables below.")
     (table nil ["Measure" "Value"]
            [(td "Coordination requests executed" (esc (count steps)))
             (td "Auto-committed with no human"
                 (span :ok (esc (count (filter #(and (= :commit (:disposition %))
                                                     (not (:interrupted? %)))
                                               steps)))))
             (td "Escalated to a human and approved"
                 (span :warn (esc (count (filter #(and (= :commit (:disposition %))
                                                       (:interrupted? %))
                                                 steps)))))
             (td "HARD holds (never reached a human)"
                 (span :critical (esc (count hard))))
             (td "Distinct HARD hold rules exercised"
                 (span :critical (esc (str/join ", " rules))))
             (td "Rollout (phase-gate) holds"
                 (span :warn (esc (count (filter phase-hold? holds)))))
             (td "SSoT ledger facts" (esc (count (store/ledger db))))
             (td "Committed rental-log records" (esc (count (store/rental-log db))))]))))

(defn render
  "Renders the whole operator console from a `run-demo!` result. Every
  cell is read out of the store or the run's audit trail."
  [{:keys [db steps]}]
  (let [ledger (vec (store/ledger db))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-7722 &middot; video tape &amp; disc rental operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Renting of video tapes and disks (ISIC 7722) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · content-rating admission overrides and damage-liability determinations are permanently out of charter</span>\n"
     "</header>\n"
     "<main>\n"
     (summary-section db steps ledger)
     (accounts-section db ledger)
     (action-gate-section)
     (scenario-section steps)
     (holds-section ledger)
     (rental-log-section db steps)
     (ledger-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>vidrentalops.render-html</code> (<code>clojure -M:dev:render-html</code>) by executing the real actor stack — <code>vidrentalops.operation</code> → <code>vidrentalops.governor</code> → <code>vidrentalops.store</code>. Deterministic: no timestamps, no randomness, byte-identical across reruns. Styling is <a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-dds</a> (デジタル庁デザインシステム), inlined at build time.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn- assert-governed!
  "Build-time invariant. The whole point of this page is that an
  independent governor can and does REFUSE. If a refactor ever makes
  the governor silently stop holding, fail the build loudly rather
  than publish a console that quietly claims everything was fine."
  [ledger]
  (let [holds (filterv #(= :governor-hold (:t %)) ledger)
        hard  (filterv hard-hold? holds)]
    (when (empty? holds)
      (throw (ex-info (str "render-html invariant violated: the scenario produced 0 "
                           ":governor-hold records. The operator console must demonstrate "
                           "that the governor actually refuses proposals.")
                      {:governor-holds 0 :ledger-facts (count ledger)})))
    (when (empty? hard)
      (throw (ex-info (str "render-html invariant violated: the scenario produced "
                           (count holds) " :governor-hold record(s) but 0 HARD holds "
                           "(a hold with a non-empty :basis, i.e. an un-overridable "
                           "governor violation that never reaches a human).")
                      {:governor-holds (count holds) :hard-holds 0})))
    {:holds (count holds)
     :hard  (count hard)
     :rules (sort (distinct (map (comp name :rule) (mapcat :violations hard))))}))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (vec (store/ledger (:db result)))
        {:keys [holds hard rules]} (assert-governed! ledger)
        html   (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count (:steps result)) " requests, "
                  (count ledger) " ledger facts, "
                  (count (audit-trail (:steps result))) " audit facts, "
                  (count (store/rental-log (:db result))) " committed records, "
                  holds " governor holds of which " hard " HARD [" (str/join ", " rules) "])"))))
