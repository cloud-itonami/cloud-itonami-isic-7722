(ns vidrentalops.advisor
  "VidRentalAdvisor -- the *contained intelligence node* for the
  ISIC-7722 RENTING OF VIDEO TAPES AND DISKS operations-coordination
  actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: rental-record (checkout/return/inventory-condition)
  logging, new-release/inventory-restock scheduling, customer-concern
  flagging, and supplier inventory-procurement coordination. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `vidrentalops.governor` before anything touches the SSoT.

  This advisor NEVER finalizes a content-rating admission override
  (e.g. waiving an age-rating check to admit a rental) and NEVER
  finalizes a damage-liability determination (who owes what for lost/
  damaged inventory) -- those are permanently out of scope for this
  actor, not merely un-implemented, per this fleet's Wave 4 person-
  facing-service safety guardrail (ADR-2607152500).
  `vidrentalops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :account-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-rental-record
  "Draft a checkout/return/inventory-condition rental-record log entry.
  Pure transaction/condition metadata logging -- never an age-rating
  admission decision or a damage-liability determination."
  [_db {:keys [account-id patch]}]
  {:op         :log-rental-record
   :account-id account-id
   :summary    (str account-id " のレンタル記録(チェックアウト/返却/在庫コンディション)を記録: " (pr-str (keys patch)))
   :rationale  "チェックアウト・返却・在庫コンディションのメタデータ記録のみ。年齢確認(コンテンツレーティング)の入場可否や損害賠償責任の判断とは無関係。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.93})

(defn- propose-restocking-operation
  "Draft a new-release/inventory-restock scheduling proposal (an
  internal ops calendar entry, never a binding procurement commitment)."
  [_db {:keys [account-id patch]}]
  {:op         :schedule-restocking-operation
   :account-id account-id
   :summary    (str account-id " の新作/在庫補充スケジュール調整を提案: " (pr-str (keys patch)))
   :rationale  "新作・在庫補充の社内スケジュール調整提案のみ。仕入れの可否や条件を決めるものではない。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.88})

(defn- propose-customer-concern
  "Surface an age-rating admission doubt, damage/loss dispute, or
  fraud suspicion for HUMAN triage. This op ALWAYS escalates in
  `vidrentalops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [account-id patch]}]
  {:op         :flag-customer-concern
   :account-id account-id
   :summary    (str account-id " の顧客懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "年齢確認(コンテンツレーティング)の入場可否に関する懸念・破損/紛失トラブル・不正利用の疑いに関する観察事実の報告。可否判断は常に人間が行う。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence (or (:confidence patch) 0.85)})

(defn- propose-supply-order
  "Draft a supplier inventory-procurement coordination proposal
  (logistics/ordering coordination only, never a binding purchase
  commitment or payment approval)."
  [_db {:keys [account-id patch]}]
  {:op         :coordinate-supply-order
   :account-id account-id
   :summary    (str account-id " の仕入れ先在庫調達コーディネートを提案: " (pr-str (keys patch)))
   :rationale  "仕入れ先への在庫調達コーディネート提案のみ。契約締結や支払承認の権限は持たない。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.90})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-rental-record (propose-rental-record _db request)
                   :schedule-restocking-operation (propose-restocking-operation _db request)
                   :flag-customer-concern (propose-customer-concern _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually decided to finalize the age-rating admission override and grant admission despite the rating")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :account-id (:account-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
