(ns cmr.common.rebalancing-collections
  "Functions used in multiple applications for working with collections that are in the process of
  moving their granules from the small collections index to a separate index or vice versa."
  (:require
   [cmr.common.services.errors :as errors]))

(def allowed-targets
  "The potential targets for a rebalancing collections operation."
  ["separate-index" "small-collections"])

(defn validate-target
  "Validates the target is set to one of the allowed values."
  [target concept-id]
  (if (nil? target)
    (errors/throw-service-errors
     :bad-request
     [(format "The index set does not contain the rebalancing collection [%s]"
              concept-id)])
    (when-not (some #{target} allowed-targets)
      (errors/throw-service-errors
       :bad-request
       [(format "Invalid target index [%s]. Only separate-index or small-collections are allowed."
                target)]))))
