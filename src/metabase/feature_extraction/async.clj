(ns metabase.feature-extraction.async
  (:require [cheshire.core :as json]
            [metabase.api.common :as api]
            [metabase.public-settings :as public-settings]
            [metabase.models
             [computation-job :refer [ComputationJob]]
             [computation-job-result :refer [ComputationJobResult]]]
            [toucan.db :as db]))

(defonce ^:private running-jobs (atom {}))

(def ^{:arglists '([job])} done?
  "Is the computation job done?"
  (comp some? #{:done :error} :status))

(def ^{:arglists '([job])} running?
  "Is the computation job still running?"
  (comp some? #{:running} :status))

(defn- save-result
  [{:keys [id]} payload]
  (db/transaction
    (db/insert! ComputationJobResult
      :job_id     id
      :permanence :temporary
      :payload    payload)
    (db/update! ComputationJob id :status :done))
  (swap! running-jobs dissoc id))

(defn- save-error
  [{:keys [id]} error]
  (db/transaction
    (db/insert! ComputationJobResult
      :job_id     id
      :permanence :temporary
      :payload    (Throwable->map error))
    (db/update! ComputationJob id :status :error))
  (swap! running-jobs dissoc id))

(defn cancel
  "Cancel computation job (if still running)."
  [{:keys [id] :as job}]
  (when (running? job)
    (future-cancel (@running-jobs id))
    (swap! running-jobs dissoc id)
    (db/update! ComputationJob id :status :canceled)))

(defn- time-delta-seconds
  [a b]
  (Math/round (/ (- (.getTime b) (.getTime a)) 1000.0)))

(defn- fresh?
  [{:keys [created_at updated_at]}]
  (let [duration (time-delta-seconds created_at updated_at)
        ttl     (* duration (public-settings/query-caching-ttl-ratio))
        age     (time-delta-seconds updated_at (java.util.Date.))]
    (<= age ttl)))

(defn- cached-job
  [ctx]
  (when (public-settings/enable-query-caching)
    (let [job (db/select-one ComputationJob
                :context (json/encode ctx)
                :status  [:not= "error"]
                {:order-by [[:updated_at :desc]]})]
      (when (some-> job fresh?)
        job))))

(defn compute
  "Compute closure `f` in context `ctx` asynchronously. Returns id of the
   associated computation job.

   Will return cached result if query caching is enabled and a job with identical
   context has successfully run within TTL."
  [ctx f]
  (or (-> ctx cached-job :id)
      (let [{:keys [id] :as job} (db/insert! ComputationJob
                                   :creator_id api/*current-user-id*
                                   :status     :running
                                   :type       :simple-job
                                   :context    ctx)]
        (swap! running-jobs assoc id (future
                                       (try
                                         (save-result job (f))
                                         (catch Throwable e
                                           (save-error job e)))))
        id)))

(defmacro with-async
  "Asynchronously evaluate expressions in lexial contexet of `bindings`.

   Note: when caching is enabled `bindings` (both their shape and values) are
   used to determine cache hits and should be used for all parameters that
   disambiguate the call."
  [bindings & body]
  (let [binding-vars (vec (take-nth 2 bindings))]
    `(let ~bindings
       (compute {:source   (quote ~body)
                 :bindings (quote ~bindings)
                 :closure  (zipmap (quote ~binding-vars) ~binding-vars)}
                (fn [] ~@body)))))

(defn result
  "Get result of an asynchronous computation job."
  [job]
  (if (done? job)
    (if-let [result (db/select-one ComputationJobResult :job_id (:id job))]
      {:status     (:status job)
       :result     (:payload result)
       :created-at (:created_at result)}
      {:status :result-not-available})
    {:status (:status job)}))

(defn running-jobs-user
  "Get all running jobs for a given user."
  ([] (running-jobs-user api/*current-user-id*))
  ([uid]
   (db/select ComputationJob
     :creator_id uid
     :status     "running")))
