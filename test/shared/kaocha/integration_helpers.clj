(ns kaocha.integration-helpers
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [kaocha.platform :as platform])
  (:import java.io.File
           [java.nio.file Files OpenOption Path Paths]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(set! *warn-on-reflection* true)

(require 'kaocha.assertions)

(defprotocol Joinable
  (join [this that]))

(extend-protocol Joinable
  String
  (join [this that] (join (Paths/get this (make-array String 0)) that))
  Path
  (join [this that] (.resolve this (str that)))
  File
  (join [this that] (.toPath (io/file this (str that)))))

(extend-protocol io/IOFactory
  Path
  (make-output-stream [this opts]
    (Files/newOutputStream this (into-array OpenOption [])))
  (make-input-stream [this opts]
    (Files/newInputStream this (into-array OpenOption [])))
  (make-writer [this opts]
    (io/make-writer (io/make-output-stream this opts) opts))
  (make-reader [this opts]
    (io/make-reader (io/make-input-stream this opts) opts)))

(extend-protocol io/Coercions
  Path
  (as-file [path] (.toFile path))
  (as-url [path] (.toURL (.toFile path))))

(def default-attributes (into-array FileAttribute []))

(defn temp-dir
  ([]
   (temp-dir "kaocha_integration"))
  ([path]
   (Files/createTempDirectory path default-attributes)))

(defonce clj-cpcache-dir (temp-dir "kaocha_cpcache"))

(defn mkdir [path]
  (Files/createDirectories path default-attributes))

(defn codecov? []
  (= (System/getenv "CI") "true"))

(defn project-dir-path [& paths]
  (str (reduce join (.getAbsolutePath (io/file "")) paths)))

(defmacro with-print-namespace-maps [bool & body]
  (if (find-var 'clojure.core/*print-namespace-maps*)
    `(binding [*print-namespace-maps* ~bool]
       ~@body)
    ;; pre Clojure 1.9
    `(do ~@body)))

(defn write-deps-edn [path]
  (with-open [deps-out (io/writer path)]
    (binding [*out* deps-out]
      (with-print-namespace-maps false
        (clojure.pprint/pprint {:deps {'lambdaisland/kaocha           {:local/root (project-dir-path)}
                                       'lambdaisland/kaocha-cloverage {:mvn/version "RELEASE"}
                                       'org.clojure/test.check        {:mvn/version "0.10.0-alpha3"}
                                       'orchestra/orchestra           {:mvn/version "2020.07.12-1"}}})))))

(defn test-dir-setup [m]
  (if (:dir m)
    m
    (let [dir         (temp-dir)
          test-dir    (join dir "test")
          bin-dir     (join dir "bin")
          config-file (join dir "tests.edn")
          deps-edn    (join dir "deps.edn")
          runner      (join dir "bin/kaocha")]
      (mkdir test-dir)
      (mkdir bin-dir)
      (spit (str config-file)
            (str "#kaocha/v1\n"
                 "{:color? false\n"
                 " :randomize? false}"))
      (spit (str runner)
            (str/join " "
                      (cond-> ["clojure"
                               "-m" "kaocha.runner"]
                        (codecov?)
                        (into ["--plugin" "cloverage"
                               "--cov-output" (project-dir-path "target/coverage" (str (gensym "integration")))
                               "--cov-src-ns-path" (project-dir-path "src")
                               "--codecov"])
                        :always
                        (conj "\"$@\""))))
      (write-deps-edn deps-edn)
      (if (platform/on-posix?)
        (Files/setPosixFilePermissions runner (PosixFilePermissions/fromString "rwxr--r--"));
        (doto (io/file runner)
          (.setReadable true true) ; make it readable for everyone
          (.setWritable true false) ; make it writeable only for the owner
          (.setExecutable true false)) ; make it executable only for the owner
        )
      (assoc m
             :dir dir
             :test-dir test-dir
             :config-file config-file
             :runner runner))))

(defn ns->fname [ns]
  (-> ns
      str
      (str/replace #"\." "/")
      (str/replace #"-" "_")
      (str ".clj")))

(defn spit-dir [m path]
  (let [{:keys [dir] :as m} (test-dir-setup m)
        path (join dir path)]
    (mkdir path)
    m))

(defn spit-file [m path contents]
  (let [{:keys [dir] :as m} (test-dir-setup m)
        path (join dir path)]
    (mkdir (.getParent ^Path path))
    (spit path contents)
    m))

(def ^:dynamic ^Process *process* nil)

(defn interactive-process* ^Process
  [{:keys [dir runner] :as _m} args f]
  (let [p (-> (doto (ProcessBuilder. ^java.util.List (cons (str runner) args))
                (.directory (io/file dir)))
              .start)
        kill (delay (.destroy p))]
    (binding [*process* p]
      (try
        (let [input (.getOutputStream p)
              output (.getInputStream p)]
          (with-open [w (io/writer input)]
            (with-open [r (io/reader output)]
              (future
                ;; unblock read-line calls after 10 seconds and abandon test
                (Thread/sleep (cond-> 10000
                                (System/getenv "CI") (* 5)))
                @kill)
              (binding [*in* r
                        *out* w]
                (f)))))
        (assert (.waitFor p 10 (java.util.concurrent.TimeUnit/SECONDS))
                "Process failed to stop!")
        (.exitValue p)
        (finally
          (is (not (realized? kill)) "Process was killed after 10s timeout!!")
          (try (.exitValue p)
               (catch IllegalThreadStateException _
                 (is nil "Process was killed after executing entire test suite!!")
                 @kill)))))))

(defmacro interactive-process
  "Simulate a test run of a bin/kaocha integration test
  using (read-line) and (println).

  m is the output from `test-dir-setup`
  args is a collection of arguments after ./bin/kaocha.
  eg., [\"--watch\" \"--focus\" \"foo\"]
  
  Kills the process after 10 seconds if it has not already
  been exited. Ensures the process is killed immediately after
  body is executed. Returns the exit code.
  
  Executes body in the following environment:
  *process* is the running Process executing `./bin/kaocha ~@args`
  *in* is bound to the output stream of this Process
  - eg., use (read-line) to read the output of ./bin/kaocha
  *out* is bound to the input stream of this Process
  - eg., use (println) to send input to ./bin/kaocha"
  [m args & body]
  `(interactive-process* ~m ~args #(do ~@body)))

(comment
  (zero?
    (interactive-process {:dir "." :runner (io/file "echo")} ["hello"]
                         (let [l1 (read-line)
                               _ (assert (= "hello" l1) (pr-str l1))])))
  )
