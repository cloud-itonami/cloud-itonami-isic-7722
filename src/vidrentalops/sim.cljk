(ns vidrentalops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean rental-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a restocking-scheduling request, supply-order
  coordination request (both auto-commit clean at phase 3), then a
  customer-concern flag (ALWAYS escalates, at any phase -- approve,
  then commit), then HARD-hold scenarios: an unregistered account, an
  account registered but not yet verified, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into
  the permanently-excluded content-rating-admission-override-
  finalization/damage-liability-determination-finalization scope."
  (:require [langgraph.graph :as g]
            [vidrentalops.advisor :as advisor]
            [vidrentalops.store :as store]
            [vidrentalops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "rental-desk-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :rental-desk-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :rental-desk-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-rental-record account-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-rental-record :account-id "account-1"
                                  :patch {:item-id "DVD-00042" :checkout-date "2026-07-16" :condition "good"}} coordinator-phase-1)]
      (println r)
      (println "-- human rental-desk coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-rental-record account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-rental-record :account-id "account-1"
                                  :patch {:item-id "DVD-00099" :return-date "2026-07-18" :condition "good"}} coordinator-phase-3))

    (println "\n== schedule-restocking-operation account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-restocking-operation :account-id "account-1"
                                  :patch {:title "New Release Batch W29" :quantity 25}} coordinator-phase-3))

    (println "\n== coordinate-supply-order account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :account-id "account-1"
                                  :patch {:supplier "Kanda Media Distributors" :sku "BD-2026-NEWREL" :due "2026-08-01"}} coordinator-phase-3))

    (println "\n== flag-customer-concern account-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-customer-concern :account-id "account-1"
                                 :patch {:concern "customer requests admission to an 18+ rated title without ID on file" :confidence 0.9}} coordinator-phase-3)]
      (println r)
      (println "-- human rental-desk coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-rental-record account-99 (unregistered account -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-rental-record :account-id "account-99"
                                  :patch {:item-id "DVD-00007"}} coordinator-phase-3))

    (println "\n== log-rental-record account-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-rental-record :account-id "account-3"
                                  :patch {:item-id "DVD-00012"}} coordinator-phase-3))

    (println "\n== schedule-restocking-operation account-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-restocking-operation :account-id "account-1"
                                           :patch {:title "New Release Batch W30"}} coordinator-phase-3)))

    (println "\n== log-rental-record account-1, advisor drifts into age-rating-admission-override/damage-liability-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-rental-record :account-id "account-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed rental log ==")
    (doseq [r (store/rental-log db)] (println r))))
