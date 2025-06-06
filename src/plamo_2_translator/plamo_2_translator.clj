(ns plamo-2-translator.plamo-2-translator
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn- log-stderr [& messages]
  (binding [*out* *err*]
    (println (apply str messages))))

(defn- split-text-into-chunks [text max-chars]
  (let [lines (str/split-lines text)
        chunks (atom [])
        current-chunk (atom "")]
    (doseq [line lines]
      (let [current-length (count @current-chunk)
            line-length (count line)
            separator (if (str/blank? @current-chunk) "" "\n")]
        (if (and (> current-length 0)
                 (> (+ current-length line-length 1) max-chars))
          (do
            (swap! chunks conj @current-chunk)
            (reset! current-chunk line))
          (swap! current-chunk #(str % separator line)))))
    (when (not (str/blank? @current-chunk))
      (swap! chunks conj @current-chunk))
    (vec @chunks)))

(defn- translate-chunk [server-url text idx total]
  (log-stderr "[" (inc idx) "/" total "] Translating " (count text) " characters")
  (try
    (let [start-time (System/currentTimeMillis)
          response (http/post (str server-url "/v1/chat/completions")
                              {:body (json/write-str
                                      {:stop ["<|plamo:op|>"]
                                       :messages [{:role "system"
                                                   :content "英語を入力とするので、「ですます」調で日本語に翻訳してください。"}
                                                  {:role "user"
                                                   :content text}]})
                               :headers {"Content-Type" "application/json"}
                               :throw-exceptions false})]
      (if (= 200 (:status response))
        (let [result (-> response
                         :body
                         json/read-str
                         (get-in ["choices" 0 "message" "content"])
                         str/trim)
              elapsed (- (System/currentTimeMillis) start-time)]
          (log-stderr "[" (inc idx) "/" total "] Completed in " elapsed "ms")
          result)
        (throw (ex-info "Translation failed" {:status (:status response)
                                               :body (:body response)}))))
    (catch Exception e
      (throw (ex-info "Translation error" {:error (.getMessage e)})))))

(def cli-options
  [["-s" "--server URL" "Translation server URL"
    :default "http://localhost:8080"
    :parse-fn identity
    :validate [#(re-matches #"https?://.*" %) "Must be a valid HTTP(S) URL"]]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do
        (log-stderr "Usage: plamo-2-translator [options]")
        (log-stderr summary)
        (System/exit 0))

      errors
      (do
        (doseq [err errors]
          (log-stderr err))
        (System/exit 1))

      :else
      (try
        (let [server-url (:server options)
              input-text (slurp *in*)
              chunks (split-text-into-chunks input-text 5000)
              _ (log-stderr "Total text length: " (count input-text) " characters")
              _ (log-stderr "Split into " (count chunks) " chunks")
              _ (doseq [[idx chunk] (map-indexed vector chunks)]
                  (log-stderr "  Chunk " (inc idx) ": " (count chunk) " characters"))
              _ (log-stderr "\nChunk preview:")
              _ (doseq [[idx chunk] (map-indexed vector chunks)]
                  (let [preview (subs chunk 0 (min 100 (count chunk)))
                        suffix (if (> (count chunk) 100) "..." "")]
                    (log-stderr "--- Chunk " (inc idx) " ---")
                    (log-stderr preview suffix)
                    (log-stderr "")))
              _ (log-stderr "Using server: " server-url)
              results (map-indexed #(translate-chunk server-url %2 %1 (count chunks)) chunks)]
          (doseq [result results]
            (println result))
          (log-stderr "\nTranslation completed: " (count input-text) " characters processed"))
        (catch Exception e
          (log-stderr "Error:" (.getMessage e))
          (binding [*out* *err*]
            (.printStackTrace e))
          (System/exit 1))))))