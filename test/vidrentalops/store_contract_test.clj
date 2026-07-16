(ns vidrentalops.store-contract-test
  "Contract tests for `vidrentalops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [vidrentalops.store :as store]))

(deftest mem-store-account-lookup
  (testing "MemStore can store and retrieve accounts by ID (string keys)"
    (let [accounts {"a1" {:account-id "a1" :name "Alice's Branch" :registered? true :verified? true}}
          s (store/mem-store accounts)]
      (is (some? (store/account s "a1")))
      (is (nil? (store/account s "a99"))))))

(deftest mem-store-all-accounts
  (testing "MemStore returns all accounts in sorted order"
    (let [accounts {"a2" {:account-id "a2" :name "Bob's Branch"}
                    "a1" {:account-id "a1" :name "Alice's Branch"}
                    "a3" {:account-id "a3" :name "Carol's Branch"}}
          s (store/mem-store accounts)
          all-a (store/all-accounts s)]
      (is (= 3 (count all-a)))
      (is (= "a1" (:account-id (first all-a))))
      (is (= "a3" (:account-id (last all-a)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-rental-log
  (testing "MemStore commit-record! appends to rental-log"
    (let [s (store/mem-store {})
          record {:op :log-rental-record :account-id "a1" :value {:item-id "DVD-1"}}]
      (is (= 0 (count (store/rental-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/rental-log s))))
      (is (= record (first (store/rental-log s)))))))

(deftest mem-store-with-accounts
  (testing "MemStore with-accounts replaces the account directory"
    (let [s (store/mem-store {})
          new-accounts {"a1" {:account-id "a1" :name "Alice's Branch"}}]
      (is (= 0 (count (store/all-accounts s))))
      (store/with-accounts s new-accounts)
      (is (= 1 (count (store/all-accounts s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo accounts"
    (let [s (store/seed-db)]
      (is (> (count (store/all-accounts s)) 0))
      (is (some? (store/account s "account-1")))
      (is (some? (store/account s "account-2")))
      (is (some? (store/account s "account-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for account-id"
    (let [demo (store/demo-data)
          accounts (:accounts demo)]
      (doseq [[k v] accounts]
        (is (string? k) "keys must be strings")
        (is (string? (:account-id v)) "account-id must be string")
        (is (= k (:account-id v)) "key must match account-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
