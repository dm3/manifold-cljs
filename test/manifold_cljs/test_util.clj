(ns manifold-cljs.test-util)

(defmacro later [& body]
  `(later* (fn [] ~@body)))
