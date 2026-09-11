(ns vidrentalops.governor-test
  "Pure unit tests of `vidrentalops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [vidrentalops.advisor :as advisor]
            [vidrentalops.governor :as gov]
            [vidrentalops.store :as store]))

(def account-1 {:account-id "account-1" :name "Kanda Video Rental -- Branch 1" :registered? true :verified? true})
(def account-3 {:account-id "account-3" :name "Ikebukuro Branch -- pending verification" :registered? true :verified? false})

(defn- clean-proposal [op account-id]
  {:op op :account-id account-id :summary "s" :rationale "routine rental operations coordination"
   :cites [account-id] :effect :propose :value {} :confidence 0.85})

(deftest account-unregistered-is-hard
  (testing "no account record at all -> HARD hold"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "unknown-account") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:account-unverified} (map :rule (:violations verdict)))))))

(deftest account-unverified-is-hard
  (testing "account registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"account-3" account-3})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "account-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:account-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-restocking-operation "account-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (clean-proposal :override-age-rating-admission "account-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest age-rating-admission-override-content-is-hard-and-permanent
  (testing "a proposal whose rationale finalizes a content-rating admission override is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :log-rental-record "account-1")
                          :rationale "decided to finalize the age-rating admission override and grant admission despite the rating"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest damage-liability-determination-content-is-hard
  (testing "a proposal touching finalizing a damage-liability determination is HARD-blocked, same as age-rating"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :flag-customer-concern "account-1")
                          :rationale "decided to finalize the liability determination and assess final liability for the damaged disk"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest liability-charge-finalization-in-summary-is-hard
  (testing "a proposal touching finalizing the liability charge in the summary is HARD-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "account-1")
                          :summary "finalize the liability charge ahead of the supplier handoff")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest admission-override-content-in-value-is-hard
  (testing "a proposal whose draft value grants the rating admission exception is HARD-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :log-rental-record "account-1")
                          :value {:decision "grant the rating admission exception for this customer"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-customer-concern-is-not-scope-excluded
  (testing "flagging a possible age-rating/damage/fraud concern (not a finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          concern (assoc (clean-proposal :flag-customer-concern "account-1")
                         :value {:concern "possible age-rating admission doubt and a disputed damage/liability claim on a returned disk"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (age-rating/damage/liability doubts) is exactly what this op exists to surface"))))

;; ----------------------------- self-trip regression (mandatory) -----------------------------
;;
;; A known bug class in this exact codebase family: a governor's own
;; scope-exclusion term list phrased as a bare noun can accidentally
;; match inside the mock advisor's own DEFAULT rationale/disclaimer
;; text for a legitimate, allowed proposal -- causing the actor to
;; self-block on its own happy path. This actor's `scope-excluded-terms`
;; are deliberately phrased as the finalization/execution ACTION
;; ('finalize the age-rating admission override', not bare 'rating' or
;; 'age'; 'finalize the liability determination', not bare
;; 'liability'). This test asserts the default mock advisor's own
;; proposals for all four allowed ops, for a clean registered+verified
;; account, NEVER trip scope-exclusion -- i.e. the actor never
;; self-blocks on its own happy path.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "none of the four default proposal generators' own rationale/summary/value text self-trips scope-exclusion"
    (let [s (store/mem-store {"account-1" account-1})]
      (doseq [op [:log-rental-record :schedule-restocking-operation
                  :flag-customer-concern :coordinate-supply-order]]
        (let [proposal (advisor/infer nil {:op op :account-id "account-1"
                                            :patch {:item-id "DVD-00042" :condition "good"
                                                    :title "New Release Batch" :quantity 25
                                                    :supplier "Kanda Media Distributors" :sku "BD-2026-NEWREL"
                                                    :concern "possible age-rating admission doubt and damage dispute"}})
              verdict (gov/check {:account-id "account-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default proposal for op " op " must never self-trip scope-exclusion; got violations: "
                   (:violations verdict)))
          (is (not (:hard? verdict))
              (str "default proposal for op " op " (clean, registered+verified account) must never HARD hold")))))))
