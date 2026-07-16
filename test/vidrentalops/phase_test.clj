(ns vidrentalops.phase-test
  "Unit tests of `vidrentalops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [vidrentalops.phase :as phase]))

(def clean-verdict {:hard? false :escalate? false})
(def low-conf-verdict {:hard? false :escalate? true})
(def hard-verdict {:hard? true :escalate? false})

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-rental-record :schedule-restocking-operation
                :flag-customer-concern :coordinate-supply-order]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-rental-record-only
  (testing "phase 1 allows only rental-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-rental-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-restocking-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-rental-record :schedule-restocking-operation :coordinate-supply-order]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-customer-concern ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-rental-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-restocking-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :commit)]
      (is (= :commit disposition)))))

(deftest customer-concern-holds-when-not-enabled
  (testing ":flag-customer-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-customer-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-customer-concern yet"))))))

(deftest customer-concern-escalates-when-enabled
  (testing ":flag-customer-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-customer-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate customer concerns regardless of governor disposition"))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-rental-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
