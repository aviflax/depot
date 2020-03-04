(ns depot.outdated.main
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [depot.outdated :as depot]
            [depot.outdated.update :as update]
            [depot.outdated.resolve-virtual :as resolve-virtual]))

(defn comma-str->keywords-set [comma-str]
  (into #{} (map keyword) (str/split comma-str #",")))

(defn keywords-set->comma-str [kws]
  (str/join "," (map name kws)))

(def version-types-str (keywords-set->comma-str depot/version-types))

(def cli-options
  [["-a" "--aliases ALIASES" "Comma list of aliases to use when reading deps.edn"
    :parse-fn comma-str->keywords-set]
   ["-t" "--consider-types TYPES" (str "Comma list of version types to consider out of " version-types-str)
    :default #{:release}
    :default-desc "release"
    :parse-fn comma-str->keywords-set
    ;; TODO: check the :errors after parsing for this error
    :validate [#(set/subset? % depot/version-types) (str "Must be subset of " depot/version-types)]]
   ["-e" "--every" "Expand search to all aliases."]
   ["-w" "--write" "Instead of just printing changes, write them back to the file."]
   ["-r" "--resolve-virtual" "Convert -SNAPSHOT/RELEASE/LATEST versions into immutable references."]
   ;; Not assigning -f to the following option because -f is an extremely common shorthand for a
   ;; --force option and I don’t want to risk anyone getting confused.
   [nil  "--fail" "If any old versions are found, exits with a non-zero status code."]
   ["-h" "--help"]])

(def ^:private messages
  {:resolve-virtual {:start-read-only "Checking virtual versions in: %s"
                     :start-write "Resolving virtual versions in: %s"
                     :no-changes "  No virtual versions found"}
   :update-old {:start-read-only "Checking for old versions in: %s"
                :start-write "Updating old versions in: %s"
                :no-changes "  All up to date!"}})

(defn exit-with [exit-code & vs]
  (when vs (apply println vs))
  (System/exit exit-code))

(defn -main [& args]
  (let [{{:keys [aliases consider-types every fail help write resolve-virtual]} :options
         files :arguments
         summary :summary} (cli/parse-opts args cli-options)
         usage (str "USAGE: clojure -m depot.outdated.main [OPTIONS] [FILES]\n\n"
                    " If no files are given, defaults to using \"deps.edn\".\n\n"
                    summary)]
    (cond
      help
      (exit-with 0 usage)

      (and every aliases)
      (exit-with 1 usage "\n\nERROR: --every and --aliases are mutually exclusive.")

      :else
      (let [files (if (seq files) files ["deps.edn"])
            check-alias? (if every (constantly true) (set aliases))
            messages (if resolve-virtual
                       (:resolve-virtual messages)
                       (:update-old messages))
            new-versions (if resolve-virtual
                           resolve-virtual/pinned-versions
                           depot/newer-versions)
            exit-code (if fail (count new-versions) 0)]
        (run! #(update/apply-new-versions % consider-types check-alias? write messages new-versions)
              files)
        (exit-with exit-code))))
  ;; There might be some background agents running, so we call System/exit here to exit immediately;
  ;; if we were to simply let this function exit then the program might seem to hang for a minute or
  ;; so before it exits. We could have used shutdown-agents to account for this (in fact, we used
  ;; to) but we realized that System/exit is a clearer, more explicit way to indicate that we want
  ;; the program to exit immediately at this point.
  (exit-with 0))
