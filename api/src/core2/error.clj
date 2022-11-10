(ns core2.error
  (:import java.io.Writer))

(defn illegal-arg
  ([k] (illegal-arg k {}))

  ([k data]
   (illegal-arg k data nil))

  ([k {::keys [^String message] :as data} cause]
   (let [message (or message (format "Illegal argument: '%s'" k))]
     (core2.IllegalArgumentException. message
                                      (merge {::error-type :illegal-argument
                                              ::error-key k
                                              ::message message}
                                             data)
                                      cause))))

(defmethod print-dup core2.IllegalArgumentException [e, ^Writer w]
  (.write w (str "#c2/illegal-arg " (ex-data e))))

(defmethod print-method core2.IllegalArgumentException [e, ^Writer w]
  (print-dup e w))

(defn -iae-reader [data]
  (illegal-arg (::error-key data) data))

(defn runtime-err
  ([k] (runtime-err k {}))

  ([k data]
   (runtime-err k data nil))

  ([k {::keys [^String message] :as data} cause]
   (let [message (or message (format "Runtime error: '%s'" k))]
     (core2.RuntimeException. message
                              (merge {::error-type :runtime-error
                                      ::error-key k
                                      ::message message}
                                     data)
                              cause))))

(defmethod print-dup core2.RuntimeException [e, ^Writer w]
  (.write w (str "#c2/runtime-err " (ex-data e))))

(defmethod print-method core2.RuntimeException [e, ^Writer w]
  (print-dup e w))

(defn -runtime-err-reader [data]
  (runtime-err (::error-key data) data))

(defn unsupported-op
  ([k] (unsupported-op k {}))

  ([k data]
   (unsupported-op  k data nil))

  ([k {::keys [^String message] :as data} cause]
   (let [message (or message (format "Unsupported operation: '%s'" k))]
     (core2.UnsupportedOperationException. message
                                      (merge {::error-type :unsupported-operation
                                              ::error-key k
                                              ::message message}
                                             data)
                                      cause))))

(defmethod print-dup core2.UnsupportedOperationException [e, ^Writer w]
  (.write w (str "#c2/unsupported-op " (ex-data e))))

(defmethod print-method core2.UnsupportedOperationException [e, ^Writer w]
  (print-dup e w))

(defn -uoe-reader [data]
  (unsupported-op (::error-key data) data))
