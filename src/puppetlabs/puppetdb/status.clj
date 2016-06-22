(ns puppetlabs.puppetdb.status
  (:require [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [trptcolin.versioneer.core :as versioneer]))

(def status-details-schema {:maintenance_mode? s/Bool
                            :queue_depth (s/maybe s/Int)
                            :read_db_up? s/Bool
                            :write_db_up? s/Bool})

;; This is vendored from the tk-status-service because version checking fails
;; semver validation on PDB snapshots. When we address this upstream we can put
;; the tk version back in.
(defn get-artifact-version
  [group-id artifact-id]
  (let [version (versioneer/get-version group-id artifact-id)]
    (when (empty? version)
      (throw (IllegalStateException.
               (format "Unable to find version number for '%s/%s'"
                 group-id
                 artifact-id))))
    version))

(pls/defn-validated status-details :- status-details-schema
  "Returns a map containing status information on the various parts of
  a running PuppetDB system. This data can be interpreted to determine
  whether the system is considered up"
  [config :- {s/Any s/Any}
   shared-globals-fn :- pls/Function
   maint-mode-fn? :- pls/Function]
  (let [globals (shared-globals-fn)]
    {:maintenance_mode? (maint-mode-fn?)
     :queue_depth (utils/nil-on-failure
                   (->> (get-in config [:command-processing :mq :endpoint])
                        (mq/queue-size "localhost")))
     :read_db_up? (sutils/db-up? (:scf-read-db globals))
     :write_db_up? (sutils/db-up? (:scf-write-db globals))}))

(pls/defn-validated create-status-map :- status-core/StatusCallbackResponse
  "Returns a status map containing the state of the currently running
  system (starting/running/error etc)"
  [{:keys [maintenance_mode? read_db_up? write_db_up?]
    :as status-details} :- status-details-schema]
  (let [state (cond
                maintenance_mode? :starting
                (and read_db_up? write_db_up?) :running
                :else :error)]
    {:state state
     :status status-details}))

(defn register-pdb-status
  "Registers the PuppetDB instance in TK status using `register-fn`
  with the associated `status-callback-fn`"
  [register-fn status-callback-fn]
  (register-fn "puppetdb-status"
               (get-artifact-version "puppetlabs" "puppetdb")
               1
               status-callback-fn))