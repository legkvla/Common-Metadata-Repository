(ns cmr.search.site.data
  "The functions of this namespace are specifically responsible for generating
  data structures to be consumed by site page templates.

  Of special note: this namespace and its sibling `page` namespace are only
  ever meant to be used in the `cmr.search.site` namespace, particularly in
  support of creating site routes for access in a browser.

  Under no circumstances should `cmr.search.site.data` be accessed from outside
  this context; the data functions defined herein are specifically for use
  in page templates, structured explicitly for their needs."
  (:require
   [clj-time.core :as clj-time]
   [clojure.string :as string]
   [cmr.common-app.config :as common-config]
   [cmr.common-app.services.search.group-query-conditions :as gc]
   [cmr.common-app.services.search.query-execution :as query-exec]
   [cmr.common-app.services.search.query-model :as query-model]
   [cmr.common-app.site.data :as common-data]
   [cmr.common.doi :as doi]
   [cmr.common.log :refer :all]
   [cmr.common.mime-types :as mt]
   [cmr.common.util :refer [defn-timed]]
   [cmr.search.services.query-service :as query-svc]
   [cmr.search.site.util :as util]
   [cmr.transmit.config :as config]
   [cmr.transmit.metadata-db :as mdb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Data utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti get-providers
  "Get the providers, based on contextual data.

  The `:cli` variant uses a special constructed context (see
  `static.StaticContext`).

  The default variant is the original, designed to work with the regular
  request context which contains the state of a running CMR."
  :execution-context)

(defmethod get-providers :cli
  [context]
  (let [providers-url (format "%sproviders"
                              (config/application-public-root-url :ingest))]
    (util/endpoint-get providers-url {:accept mt/json})))

(defmethod get-providers :default
  [context]
  (mdb/get-providers context))

(defmulti collection-data
  "Get the collection data associated with a provider and tag.

  The `:cli` variant uses a special constructed context (see
  `static.StaticContext`).

  The default variant is the original, designed to work with the regular
  request context which contains the state of a running CMR."
  (fn [context & args] (:execution-context context)))

(defmethod collection-data :cli
  [context tag provider-id]
  (as-> (config/application-public-root-url :search) data
        (format "%scollections" data)
        (util/endpoint-get data {:accept mt/umm-json-results
                                 :query-params {:provider provider-id
                                                :tag-key tag
                                                :page-size 2000}})
        (:items data)
        (sort-by #(get-in % [:umm :EntryTitle]) data)))

(defn-timed get-collection-data
  "Get the collection data from elastic by provider id and tag. Sort results
  by entry title"
  [context tag provider-id]
  (let [conditions (query-svc/generate-query-conditions-for-parameters
                    context
                    :collection
                    {:tag-key tag
                     :provider provider-id})
        query (query-model/query {:concept-type :collection
                                  :condition (gc/and-conds conditions)
                                  :skip-acls? false
                                  :page-size :unlimited
                                  :result-format :query-specified
                                  :result-fields [:concept-id
                                                  :doi
                                                  :entry-title
                                                  :short-name
                                                  :version-id]})
        result (query-exec/execute-query context query)]
    (sort-by :entry-title (:items result))))

(defmethod collection-data :default
  [context tag provider-id]
  (get-collection-data context tag provider-id))

(defn-timed provider-data
  "Create a provider data structure suitable for template iteration to
  generate links.

  Note that the given tag will be used to filter provider collections data
  that is used on the destination page."
  [context tag data]
  (let [provider-id (:provider-id data)
        collections (collection-data context tag provider-id)]
    {:id provider-id
     :tag tag
     :collections collections
     :collections-count (count collections)}))

(defn-timed providers-data
  "Given a list of provider maps, create the nested data structure needed
  for rendering providers in a template."
  [context tag providers]
  (debug "Using providers:" providers)
  (->> providers
       (pmap (partial provider-data context tag))
       ;; Only want to include providers with EOSDIS collections
       (remove #(zero? (get % :collections-count 0)))
       (sort-by :id)))

(defn make-holding-data
  "Given a single item from a query's collections, update the item with data
  for linking to its landing page."
  [cmr-base-url item]
  (assoc item :link-href (doi/get-landing-page cmr-base-url item)))

(defn make-holdings-data
  "Given a collection from an elastic search query, generate landing page
  links appropriate for the collection."
  [cmr-base-url coll]
  (map (partial make-holding-data cmr-base-url) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Page data functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti base-page
  "Data that all app pages have in common.

  The `:cli` variant uses a special constructed context (see
  `static.StaticContext`).

  The default variant is the original, designed to work with the regular
  request context which contains the state of a running CMR."
  :execution-context)

(defmethod base-page :cli
  [context]
  (assoc (common-data/base-static) :app-title "CMR Search"
                                   :release-version (str "v " (common-config/release-version))))

(defmethod base-page :default
  [context]
  (assoc (common-data/base-page context) :app-title "CMR Search"
                                         :release-version (str "v " (common-config/release-version))))

(defn get-directory-links
  "Provide the list of links that will be rendered on the top-level directory
  page."
  [context]
  (merge
   (base-page context)
   {:links [{:href "site/collections/directory/eosdis"
             :text "EOSDIS Collections"}]}))

(defn get-eosdis-directory-links
  [context]
  (->> context
       (get-providers)
       (providers-data context "gov.nasa.eosdis")
       (hash-map :providers)
       (merge (base-page context))))

(defn get-provider-tag-landing-links
  "Generate the data necessary to render EOSDIS landing page links."
  ([context provider-id tag]
   (get-provider-tag-landing-links context provider-id tag (constantly true)))
  ([context provider-id tag filter-fn]
   (merge
    (base-page context)
    {:provider-id provider-id
     :tag-name (util/supported-directory-tags tag)
     :holdings (filter filter-fn
                       (make-holdings-data
                        (util/get-app-url context)
                        (collection-data context tag provider-id)))})))

(defn get-provider-tag-sitemap-landing-links
  "Generate the data necessary to render EOSDIS landing page links that will
  be included in a sitemap.xml file.

  Note that generally the sitemap spec does not support cross-site inclusions,
  thus the filtering-out of non-CMR links."
  [context provider-id tag]
  (get-provider-tag-landing-links
   context
   provider-id
   tag
   #(string/includes?
     (str %)
     (util/get-app-url context))))
