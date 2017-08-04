(ns puppetlabs.puppetdb.cli.micro-bench-storage
  (:require [puppetlabs.puppetdb.client :as client]
            [clj-time.core :as t]
            [puppetlabs.puppetdb.utils :as utils :refer [println-err]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.core.async :as async]))


;; def sleep_until_queue_empty(host, timeout=60)
;; metric = ""
;; queue_size = nil

;; begin
;; Timeout.timeout(timeout) do
;; until queue_size == 0
;; result = on host, %Q(curl http://localhost:8080/metrics/v1/mbeans/#{CGI.escape(metric)} 2> /dev/null | python -c "import sys, json; print json.load(sys.stdin)['Count']")
;; queue_size = Integer(result.stdout.chomp)
;; end
;; end
;; rescue Timeout::Error => e
;; raise "Queue took longer than allowed #{timeout} seconds to empty"
;; end
;; end

(defn sleep-until-queue-empty [base-url]
  (let [queue-depth-metrics (->
                             (client/get-metric base-url "puppetlabs.puppetdb.mq:name=global.depth")
                             (json/parse-string true))]
    (prn queue-depth-metrics)
    (when-not (zero? (:Count queue-depth-metrics))
      (Thread/sleep 50)
      (recur base-url))))

(defn gen-facts [certname
                 {:keys [shared-static unique-static unique-changing] :as fact-counts}
                 generation-num]
  (let [facts (concat
               (for [n (range shared-static)]
                 [(str "shared-static-" n) (str "shared-static-" n)])
               (for [n (range unique-static)]
                 [(str "unique-static-" n) (str "unique-static-" certname "-" n)])
               (for [n (range unique-changing)]
                 [(str "changing-" n) (str "changing-static-" certname "-" n "-" generation-num)]))]
    {:certname certname
     :environment "production"
     :producer_timestamp (t/now)
     :producer "micro-bench"
     :values (into {} facts)}))

(defn run-fact-bench [pdb-hostname prefix num-generations num-nodes fact-params]
  (do
    (doseq [generation-num (range num-generations)]
      (println "Submitting facts for generation " generation-num "...")
      (doseq [certname (map (partial str "group-" prefix "-host-")
                            (range 1000))]
        (let [facts (gen-facts certname {:shared-static 100
                                         :unique-static 100
                                         :unique-changing 50}
                               generation-num)]
          (client/submit-facts (utils/pdb-cmd-base-url pdb-hostname 8080 :v1)
                               certname
                               5
                               facts))))))

(defn -main [pdb-hostname & args]
  (time
   (let [num-threads 10
         num-nodes 1000
         num-generations 10
         fact-params {:shared-static 100
                      :unique-static 100
                      :unique-changing 50}
         threads (->> (range num-threads)
                      (map (fn [thread-num]
                             (async/thread
                               (run-fact-bench pdb-hostname
                                               (str thread-num)
                                               num-generations
                                               (/ num-nodes num-threads)
                                               fact-params))))
                      doall)]
     (doseq [t threads]
       (async/<!! t))

     (println "Done submitting, waiting for processing...")
     (sleep-until-queue-empty (utils/metrics-base-url pdb-hostname 8080 :v1))
     (println "done"))))

(comment
  (-main "localhost")

  )


