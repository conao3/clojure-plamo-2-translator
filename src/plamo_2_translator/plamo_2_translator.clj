(ns plamo-2-translator.plamo-2-translator
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder$Redirect])
  (:gen-class))

(def ^:private server-processes (atom {}))

(defn- log-stderr [& messages]
  (binding [*out* *err*]
    (println (apply str messages))))

(defn- wait-for-server [url max-attempts]
  (loop [attempts 0]
    (if (>= attempts max-attempts)
      false
      (if (try
            (http/post (str url "/v1/chat/completions")
                      {:body (json/write-str
                              {:stop ["<|plamo:op|>"]
                               :messages [{:role "user"
                                           :content "test"}]})
                       :headers {"Content-Type" "application/json"}
                       :throw-exceptions false
                       :conn-timeout 1000
                       :socket-timeout 1000})
            true
            (catch Exception _
              false))
        true
        (do
          (Thread/sleep 1000)
          (recur (inc attempts)))))))

(defn- start-translation-server [port model]
  (let [pb (ProcessBuilder.
            ["uvx" "-p" "python3.12" "--with" "numba" "--from" "mlx-lm"
             "mlx_lm.server" "--model" model "--trust-remote-code"
             "--port" (str port)])
        log-file (io/file (str "server_" port ".log"))
        _ (.redirectErrorStream pb true)
        _ (.redirectOutput pb (ProcessBuilder$Redirect/to log-file))
        process (.start pb)]
    (swap! server-processes assoc port process)
    (log-stderr "Starting server on port " port "...")
    (Thread/sleep 2000)
    (if (.isAlive process)
      (do
        (log-stderr "Server process started on port " port)
        (log-stderr "Waiting for server to be ready on port " port "...")
        (if (wait-for-server (str "http://localhost:" port) 30)
          (log-stderr "Server ready on port " port)
          (throw (ex-info "Server failed to respond" {:port port}))))
      (throw (ex-info "Failed to start server" {:port port})))
    process))

(defn- stop-translation-servers []
  (doseq [[port process] @server-processes]
    (log-stderr "Stopping server on port " port "...")
    (.destroy process)
    (.waitFor process))
  (reset! server-processes {}))

(defn- split-text-into-chunks [text max-chars num-servers]
  (let [total-length (count text)
        target-chunk-size (min max-chars (quot total-length num-servers))
        sentences (str/split text #"(?<=\.)(?:\s+|\n)")
        chunks (atom [])
        current-chunk (atom "")]
    (doseq [sentence sentences]
      (let [current-length (count @current-chunk)
            sentence-length (count sentence)]
        (cond
          (and (> current-length 0)
               (> (+ current-length sentence-length 1) max-chars))
          (do
            (swap! chunks conj @current-chunk)
            (reset! current-chunk sentence))
          
          (and (> current-length 0)
               (>= (+ current-length sentence-length 1) target-chunk-size)
               (< (count @chunks) (dec num-servers)))
          (do
            (swap! chunks conj (str @current-chunk " " sentence))
            (reset! current-chunk ""))
          
          :else
          (swap! current-chunk #(if (str/blank? %) sentence (str % " " sentence))))))
    (when (not (str/blank? @current-chunk))
      (swap! chunks conj @current-chunk))
    (vec @chunks)))

(defn- translate-chunk [server-url text idx total]
  (log-stderr "[" (inc idx) "/" total "] Translating " (count text) " characters with " server-url)
  (try
    (let [start-time (System/currentTimeMillis)
          response (http/post (str server-url "/v1/chat/completions")
                              {:body (json/write-str
                                      {:stop ["<|plamo:op|>"]
                                       :messages [{:role "user"
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

(defn- translate-parallel [server-urls chunks]
  (log-stderr "Starting parallel translation with " (count server-urls) " servers...")
  (let [ch (async/chan)
        indexed-chunks (map-indexed vector chunks)]
    (doseq [[idx chunk] indexed-chunks]
      (let [server-url (nth server-urls (mod idx (count server-urls)))]
        (async/go
          (try
            (let [result (translate-chunk server-url chunk idx (count chunks))]
              (async/>! ch {:index idx :result result :server server-url :length (count chunk)}))
            (catch Exception e
              (async/>! ch {:index idx :error e}))))))
    (let [results (atom {})
          server-stats (atom {})]
      (dotimes [_ (count chunks)]
        (let [{:keys [index result error server length]} (async/<!! ch)]
          (if error
            (throw error)
            (do
              (swap! results assoc index result)
              (swap! server-stats update server (fnil + 0) length)))))
      (log-stderr "\nTranslation summary:")
      (doseq [[server chars] @server-stats]
        (log-stderr "  " server ": " chars " characters"))
      (log-stderr "")
      (map #(get @results %) (range (count chunks))))))

(defn- setup-shutdown-hook []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (stop-translation-servers)
                               (log-stderr "\nServers stopped.")))))

(def cli-options
  [["-s" "--server URL" "Translation server URL"
    :default "http://localhost:8080"
    :parse-fn identity
    :validate [#(re-matches #"https?://.*" %) "Must be a valid HTTP(S) URL"]]
   ["-p" "--parallel N" "Number of parallel servers to start"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 10) "Must be between 1 and 9"]]
   ["-b" "--base-port PORT" "Base port for servers"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 1024 % 65536) "Must be between 1025 and 65535"]]
   ["-m" "--model MODEL" "Model name"
    :default "mlx-community/plamo-2-translate"
    :parse-fn identity]
   [nil "--start-servers" "Start translation servers automatically"]
   [nil "--server-urls URLS" "Comma-separated server URLs (when not starting servers)"
    :parse-fn #(str/split % #",")]
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
        (setup-shutdown-hook)
        (let [server-urls (cond
                            (:server-urls options)
                            (:server-urls options)

                            (:start-servers options)
                            (do
                              (log-stderr "Starting translation servers...")
                              (let [ports (map #(+ (:base-port options) %)
                                               (range (:parallel options)))]
                                (doseq [port ports]
                                  (start-translation-server port (:model options)))
                                (map #(str "http://localhost:" %) ports)))

                            :else
                            (if (= 1 (:parallel options))
                              [(:server options)]
                              (throw (ex-info "Multiple servers require --start-servers or --server-urls"
                                              {:options options}))))
              input-text (slurp *in*)
              chunks (split-text-into-chunks input-text 5000 (count server-urls))
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
              results (if (= 1 (count server-urls))
                        (do
                          (log-stderr "Using single server: " (first server-urls))
                          (map-indexed #(translate-chunk (first server-urls) %2 %1 (count chunks)) chunks))
                        (translate-parallel server-urls chunks))]
          (doseq [result results]
            (println result))
          (when (:start-servers options)
            (stop-translation-servers)))
        (catch Exception e
          (log-stderr "Error:" (.getMessage e))
          (binding [*out* *err*]
            (.printStackTrace e))
          (when (:start-servers options)
            (stop-translation-servers))
          (System/exit 1))))))