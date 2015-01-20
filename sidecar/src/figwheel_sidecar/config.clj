(ns figwheel-sidecar.config
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.walk :as walk]))

(defn optimizations-none?
  "returns true if a build has :optimizations set to :none"
  [build]
  (= :none (get-in build [:compiler :optimizations])))

;; checking to see if output dir is in right directory
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn relativize-resource-paths
  "Relativize to the local root just in case we have an absolute path"
  [resource-paths]
  (mapv #(string/replace-first (norm-path %)
                               (str (norm-path (.getCanonicalPath (io/file ".")))
                                    "/") "") resource-paths))

(defn make-serve-from-display [{:keys [http-server-root resource-paths] :as opts}]
  (let [paths (relativize-resource-paths resource-paths)]
    (str "(" (string/join "|" paths) ")/" http-server-root)))

(defn output-dir-in-resources-root?
  "Check if the build output directory is in or below any of the configured resources directories."
  [{:keys [output-dir] :as build-options}
   {:keys [resource-paths http-server-root] :as opts}]
  (and output-dir
       (first (filter (fn [x] (.startsWith output-dir (str x "/" http-server-root)))
                      (relativize-resource-paths resource-paths)))))

(defn map-to-vec-builds
  "Cljsbuild allows a builds to be specified as maps. We acommodate that with this function
   to normalize the map back to the standard vector specification. The key is placed into the
   build under the :id key."
  [builds]
  (if (map? builds)
    (vec (map (fn [[k v]] (assoc v :id (name k))) builds))
    builds))

(defn narrow-builds* 
  "Filters builds to the chosen build-ids or if no build-ids specified returns the first
   build with optimizations set to none."
  [builds build-ids]
  (let [builds (map-to-vec-builds builds)
        ;; ensure string ids
        builds (map #(update-in % [:id] name) builds)]
    (vec
     (keep identity
           (if-not (empty? build-ids)
             (keep (fn [bid] (first (filter #(= bid (:id %)) builds))) build-ids)
             [(first (filter optimizations-none? builds))])))))

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-builds
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [project build-ids]
  (update-in project [:cljsbuild :builds] narrow-builds* build-ids))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [http-server-root] :as opts} build']
  (let [build-options (:compiler build')
        opts? (and (not (nil? build-options)) (optimizations-none? build'))
        out-dir? (output-dir-in-resources-root? build-options opts)]
    (map
     #(str "Figwheel Config Error (in project.clj) - " %)
     (filter identity
             (list
              (when-not opts?
                "you have build :optimizations set to something other than :none")
              (when-not out-dir?
                (str
                 (if (:output-dir build-options)
                   "your build :output-dir is not in a resources directory."
                   "you have not configured an :output-dir in your build")
                 (str "\nIt should match this pattern: " (make-serve-from-display opts)))))))))

(defn check-config [figwheel-options builds]
  (if (empty? builds)
    (list
     (str "Figwheel: "
          "No cljsbuild specified. You may have mistyped the build "
          "id on the command line or failed to specify a build in "
          "the :cljsbuild section of your project.clj. You need to have "
          "at least one build with :optimizations set to :none."))
    (mapcat (partial check-for-valid-options figwheel-options) builds)))

(defn normalize-dir
  "If directory ends with '/' then truncate the trailing forward slash."
  [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir)) 
    (subs dir 0 (dec (count dir)))
    dir))

(defn normalize-output-dir [opts]
  (update-in opts [(if (:build-options opts) :build-options :compiler)  :output-dir] normalize-dir))

(defn normalize-output-dirs [builds]
  (mapv normalize-output-dir builds))

(defn no-seqs [b]
  (walk/postwalk #(if (seq? %) (vec %) %) b))

(defn prep-builds [builds]
  (-> builds
      map-to-vec-builds
      normalize-output-dirs
      no-seqs))

(defn apply-to-key
  "applies a function to a key, if key is defined."
  [f k opts]
  (if (k opts) (update-in opts [k] f) opts))

(defn prep-options
  "Normalize various configuration input."
  [opts]
  (->> opts
       no-seqs
       (apply-to-key str :ring-handler)
       (apply-to-key str :http-server-root)       
       (apply-to-key str :open-file-command)
       (apply-to-key str :server-logfile)))

