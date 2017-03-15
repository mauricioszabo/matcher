(ns matcher.utils.match
  (:require [clojure.walk :as walk]
            [clojure.string :as str]))

(defn parse-args [args]
  (let [parsed (map (fn [a]
                     (cond
                       (and (symbol? a) (->> a str (re-find #"^\?"))) `(~'quote ~a)
                       (or (= a '_) (= a '&)) `(~'quote ~a)
                       (coll? a) (parse-args a)
                       :else a))
                   args)]
    (cond
      (seq? args) (apply list parsed)
      (map-entry? args) (vec parsed)
      :else (into (empty args) parsed))))

(declare apply-match)
(defn- recurse-into-result [possible-fn obj]
  (if (fn? possible-fn)
    (apply-match obj possible-fn)
    [possible-fn obj]))

(defn- combine-sides [acc-ls acc-rs inner-res]
  (let [concatv #(-> %1 (concat %2) vec)
        [ls rs] inner-res]
    (if (and (coll? ls) (coll? rs))
      [(concatv acc-ls ls) (concatv acc-rs rs)]
      [(conj acc-ls ls) (conj acc-rs rs)])))

(defn- apply-inner [[ls rs]]
  (if (and (coll? ls) (coll? rs) (= (count ls) (count rs)))
    (loop [[first-ls & rest-ls] (seq ls)
           [first-rs & rest-rs] (seq rs)
           [acc-ls acc-rs] [[] []]]

      (when-let [inner-res (recurse-into-result first-ls first-rs)]
        (let [acc (combine-sides acc-ls acc-rs inner-res)]
          (if (empty? rest-ls)
            acc
            (recur rest-ls rest-rs acc)))))
    [ls rs]))

(defn apply-match [obj match-fn]
  (if (fn? match-fn)
    (let [res (match-fn obj)]
      (cond
        (vector? res) (apply-inner res)
        (not res) nil
        :else [[] []]))
    [[match-fn] [obj]]))

(defn unify [[left right]]
  (when (= (count left) (count right))
    (let [un-symbol? #(and (symbol? %) (str/starts-with? (str %) "?"))
          un (fn [l r] (if (un-symbol? l) [l r] (= l r)))
          unify' (fn [map [left right]]
                   (if-let [value (get map left)]
                     (if (= value right) map nil)
                     (assoc map left right)))]
      (->> right
           (map un left)
           (reduce (fn [unifications pair]
                     (case pair
                       (false nil) nil
                       true unifications
                       (unify' unifications pair)))
                 {})))))

(defn match-and-unify [obj match-fn]
  (some->> (apply-match obj match-fn) unify))

(defn- create-let [unbound-vars sym then]
  (let [bindings (->> unbound-vars
                      set
                      (mapcat (fn [v] [(-> v name (str/replace #"^\?" "") symbol)
                                       `('~v ~sym)]))
                      vec)]
    `(let ~bindings ~then)))

(defn wrap-let [obj match-fn then else]
  (let [var (gensym)
        norm-fn (cond-> match-fn (list? match-fn) parse-args)
        unbound-vars (->> match-fn
                          (walk/prewalk #(if (coll? %) (seq %) %))
                          flatten
                          (filter #(if (symbol? %) (-> % name (.startsWith "?")))))
        let-clause (if (empty? unbound-vars)
                     then
                     (create-let unbound-vars var then))]
    `(if-let [~var (match-and-unify ~obj ~norm-fn)]
       ~let-clause
       ~else)))

(defn match* [obj & matches]
  (assert (even? (count matches)) "Matches must be even")
  (let [[match-fn then & rest] matches
        else (cond
               (= match-fn '_) then
               (empty? rest) `(throw (IllegalArgumentException. "No match"))
               :else (apply match* obj rest))]
    (if (= match-fn '_)
      else
      (wrap-let obj match-fn then else))))
