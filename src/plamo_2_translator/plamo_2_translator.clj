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
    (println (str "Starting server on port " port "..."))
    (Thread/sleep 2000)
    (if (.isAlive process)
      (do
        (println (str "Server process started on port " port))
        (println (str "Waiting for server to be ready on port " port "..."))
        (if (wait-for-server (str "http://localhost:" port) 30)
          (println (str "Server ready on port " port))
          (throw (ex-info "Server failed to respond" {:port port}))))
      (throw (ex-info "Failed to start server" {:port port})))
    process))

(defn- stop-translation-servers []
  (doseq [[port process] @server-processes]
    (println (str "Stopping server on port " port "..."))
    (.destroy process)
    (.waitFor process))
  (reset! server-processes {}))

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
    (doseq [[idx chunk] indexed-chunks]
      (let [server-url (nth server-urls (mod idx (count server-urls)))]
        (async/go
          (try
            (let [result (translate-chunk server-url chunk)]
              (async/>! ch {:index idx :result result}))
            (catch Exception e
              (async/>! ch {:index idx :error e}))))))
    (let [results (atom {})]
      (dotimes [_ (count chunks)]
        (let [{:keys [index result error]} (async/<!! ch)]
          (if error
            (throw error)
            (swap! results assoc index result))))
      (map #(get @results %) (range (count chunks))))))

(defn- setup-shutdown-hook []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (stop-translation-servers)
                               (println "\nServers stopped.")))))

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
        (setup-shutdown-hook)
        (let [server-urls (cond
                            (:server-urls options)
                            (:server-urls options)

                            (:start-servers options)
                            (do
                              (println "Starting translation servers...")
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
              chunks (split-text input-text (:chunk-size options))
              results (if (= 1 (count server-urls))
                        (map #(translate-chunk (first server-urls) %) chunks)
                        (translate-parallel server-urls chunks))]
          (doseq [result results]
            (println result))
          (when (:start-servers options)
            (stop-translation-servers)))
        (catch Exception e
          (binding [*out* *err*]
            (println "Error:" (.getMessage e))
            (.printStackTrace e))
          (when (:start-servers options)
            (stop-translation-servers))
          (System/exit 1))))))
