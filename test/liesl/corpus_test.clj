;; Copyright (c) 2026 Junzhe Wang, licensed under the MIT License.

(ns liesl.corpus-test
  "The fixtures live at the test-path root, because a corpus is addressed by
  its directory name on the classpath."
  (:require [clojure.test :refer [deftest is]]
            [liesl.corpus :as corpus]))

(defn- succeed?
  "Invoke f and report whether it returned."
  [f]
  (try (f) true (catch clojure.lang.ExceptionInfo _ false)))

(deftest loads-the-fhir-corpus
  (let [loaded (corpus/load "fhir")]
    (is (= "fhir" (:corpus loaded))
        "the declared name must match the directory it was loaded from")
    (is (= ["STU3" "R4" "R4B" "R5"] (:versions loaded))
        "the list carries version order, because these strings do not sort correctly")))

(deftest missing-corpus-names-what-it-looked-for
  (let [info (try (corpus/load "no-such-corpus") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= {:corpus "no-such-corpus" :resource "no-such-corpus/corpus.edn"} info))))

(deftest unreadable-edn-is-reported-as-such
  (is (not (succeed? #(corpus/load "broken-edn")))))

(deftest a-missing-required-key-names-it
  (let [info (try (corpus/load "missing-keys") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= [:fetch] (:missing-keys info)))
    (is (= [:corpus :sources] (:found-keys info)))))

(deftest a-source-missing-a-key-names-the-source
  (let [info (try (corpus/load "bad-source") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= "spec" (:name (:source info))))
    (is (= [:base-url] (:missing-keys info)))))

(deftest a-corpus-cannot-execute-code
  ;; The fixture is valid apart from #=(+ 1 1), which asks the reader to
  ;; evaluate. Were it evaluated, :delay-ms would be 2 and the load would
  ;; succeed -- so a failure here is the proof that it was not.
  (is (not (succeed? #(corpus/load "eval-attempt")))))

(deftest a-corpus-may-not-answer-to-two-names
  (let [info (try (corpus/load "mislabelled") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (some? info) "the load must fail")
    (is (= "wrong-name" (:declared info)))))
