(ns vidrentalops.advisor-test
  "Unit tests of `vidrentalops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [vidrentalops.advisor :as adv]
            [vidrentalops.store :as store]))

(def db (store/seed-db))

(deftest propose-rental-record-shape
  (testing "rental-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-rental-record
                           :account-id "account-1"
                           :patch {:item-id "DVD-00042" :condition "good"}})]
      (is (= :log-rental-record (:op p)))
      (is (= "account-1" (:account-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :account-id)))))

(deftest propose-restocking-operation-shape
  (testing "restocking-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-restocking-operation
                           :account-id "account-2"
                           :patch {:title "New Release Batch" :quantity 10}})]
      (is (= :schedule-restocking-operation (:op p)))
      (is (= "account-2" (:account-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-customer-concern-shape
  (testing "customer-concern proposal has correct shape"
    (let [p (adv/infer db {:op :flag-customer-concern
                           :account-id "account-1"
                           :patch {:concern "possible age-rating admission doubt"}})]
      (is (= :flag-customer-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :account-id "account-1"
                           :patch {:supplier "Kanda Media Distributors" :sku "BD-2026-NEWREL"}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-rental-record :schedule-restocking-operation
                :flag-customer-concern :coordinate-supply-order]]
      (let [p (adv/infer db {:op op :account-id "account-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-rental-record :schedule-restocking-operation
                :flag-customer-concern :coordinate-supply-order]]
      (let [p (adv/infer db {:op op :account-id "account-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
