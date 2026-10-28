(ns plamo-2-translator.plamo-2-translator
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io])
  (:gen-class))

(defn- log-stderr [& messages]
  (binding [*out* *err*]
    (println (apply str messages))))

(defn- translate-chunk [server-url text]
  (log-stderr "Translating " (count text) " characters...")
  (try
    (let [start-time (System/currentTimeMillis)
          response (http/post (str server-url "/v1/chat/completions")
                              {:body (json/write-str
                                      {:stop ["<|plamo:op|>"]
                                       :messages [{:role "system"
                                                   :content "Input English, translate into Japanese using ですます-style."}
                                                  {:role "user"
                                                   :content text}]})
                               :headers {"Content-Type" "application/json"}
                               :throw-exceptions false})]
      (if (= 200 (:status response))
        (let [elapsed (- (System/currentTimeMillis) start-time)]
          (log-stderr "Completed in " elapsed "ms")
          (print (-> response
                     :body
                     json/read-str
                     (get-in ["choices" 0 "message" "content"])
                     str/trim))
          (flush))
        (throw (ex-info "Translation failed" {:status (:status response)
                                              :body (:body response)}))))
    (catch Exception e
      (throw (ex-info "Translation error" {:error (.getMessage e)})))))

(defn- process-stream [server-url]
  (let [buffer (atom "")
        total-chars (atom 0)]
    (with-open [reader (io/reader *in*)]
      (doseq [line (line-seq reader)]
        (let [current-buffer @buffer
              new-buffer (if (str/blank? current-buffer)
                           line
                           (str current-buffer "\n" line))]
          (if (> (count new-buffer) 2000)
            (when (not (str/blank? current-buffer))
              (translate-chunk server-url current-buffer)
              (swap! total-chars + (count current-buffer))
              (reset! buffer line))
            (reset! buffer new-buffer))))
      (when (not (str/blank? @buffer))
        (translate-chunk server-url @buffer)
        (swap! total-chars + (count @buffer)))
      (log-stderr "\nTranslation completed: " @total-chars " characters processed"))))

(def cli-options
  [["-s" "--server URL" "Translation server URL"
    :default "http://localhost:8080"
    :parse-fn identity
    :validate [#(re-matches #"https?://.*" %) "Must be a valid HTTP(S) URL"]]
   ["-h" "--help"]])

#_(defn -main [& args]
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
          (let [server-url (:server options)]
            (log-stderr "Using server: " server-url)
            (log-stderr "Starting stream processing...")
            (process-stream server-url))
          (catch Exception e
            (log-stderr "Error:" (.getMessage e))
            (binding [*out* *err*]
              (.printStackTrace e))
            (System/exit 1))))))

(defn -main [& [server-url]]
  (let [res (http/post (str server-url "/v1/chat/completions")
                       {:as :reader
                        :body (json/write-str
                               {:stop ["<|plamo:op|>"]
                                :stream true
                                :messages [{:role "system"
                                            :content "Input English, translate into Japanese using ですます-style."}
                                           {:role "user"
                                            :content (slurp *in*)}]})
                        :headers {"Content-Type" "application/json"}
                        :throw-exceptions false})]
    (when-not (= 200 (:status res))
      (throw (ex-info "Return code is not 200" {:res res})))
    (->> (line-seq (:body res))
         (filter #(str/starts-with? % "data: "))
         (remove #{"data: [DONE]"})
         (run! #(do
                  (-> %
                      (subs 6)
                      (json/read-str :key-fn keyword)
                      :choices
                      first
                      :delta
                      :content
                      print)
                  (flush))))))
