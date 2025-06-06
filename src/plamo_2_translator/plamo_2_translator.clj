(ns plamo-2-translator.plamo-2-translator
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn- split-text [text max-chars]
  (let [sentences (str/split text #"(?<=\.)(?:\s+|\n)")
        chunks (reduce
                (fn [acc sentence]
                  (let [current (peek acc)]
                    (if (< (+ (count current) (count sentence)) max-chars)
                      (conj (pop acc) (str current " " sentence))
                      (conj acc sentence))))
                [""]
                sentences)]
    (filter #(not (str/blank? %)) chunks)))

(defn- translate-chunk [server-url text]
  (try
    (let [response (http/post (str server-url "/v1/chat/completions")
                              {:body (json/write-str
                                      {:stop ["<|plamo:op|>"]
                                       :messages [{:role "user"
                                                   :content text}]})
                               :headers {"Content-Type" "application/json"}
                               :throw-exceptions false})]
      (if (= 200 (:status response))
        (-> response
            :body
            json/read-str
            (get-in ["choices" 0 "message" "content"])
            str/trim)
        (throw (ex-info "Translation failed" {:status (:status response)
                                               :body (:body response)}))))
    (catch Exception e
      (throw (ex-info "Translation error" {:error (.getMessage e)})))))

(defn- translate-parallel [server-urls chunks]
  (let [ch (async/chan)
        indexed-chunks (map-indexed vector chunks)]
    (doseq [[idx chunk] indexed-chunks
            server-url (cycle server-urls)]
      (async/go
        (try
          (let [result (translate-chunk server-url chunk)]
            (async/>! ch {:index idx :result result}))
          (catch Exception e
            (async/>! ch {:index idx :error e})))))
    (let [results (atom {})]
      (dotimes [_ (count chunks)]
        (let [{:keys [index result error]} (async/<!! ch)]
          (if error
            (throw error)
            (swap! results assoc index result))))
      (map #(get @results %) (range (count chunks))))))

(def cli-options
  [["-s" "--server URL" "Translation server URL"
    :default "http://localhost:8080"
    :parse-fn identity
    :validate [#(re-matches #"https?://.*" %) "Must be a valid HTTP(S) URL"]]
   ["-p" "--parallel N" "Number of parallel servers"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 10) "Must be between 1 and 9"]]
   ["-c" "--chunk-size SIZE" "Maximum chunk size in characters"
    :default 1000
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 10000) "Must be between 1 and 9999"]]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do
        (println "Usage: plamo-2-translator [options]")
        (println summary)
        (System/exit 0))

      errors
      (do
        (doseq [err errors]
          (println err))
        (System/exit 1))

      :else
      (try
        (let [input-text (slurp *in*)
              server-urls (if (= 1 (:parallel options))
                            [(:server options)]
                            (map #(str (:server options) ":" (+ 8080 %))
                                 (range (:parallel options))))
              chunks (split-text input-text (:chunk-size options))
              results (if (= 1 (count server-urls))
                        (map #(translate-chunk (first server-urls) %) chunks)
                        (translate-parallel server-urls chunks))]
          (doseq [result results]
            (println result)))
        (catch Exception e
          (binding [*out* *err*]
            (println "Error:" (.getMessage e)))
          (System/exit 1))))))
