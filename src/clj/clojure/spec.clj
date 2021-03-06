;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.spec
  (:refer-clojure :exclude [+ * and or cat def keys merge])
  (:require [clojure.walk :as walk]
            [clojure.spec.gen :as gen]
            [clojure.string :as str]))

(alias 'c 'clojure.core)

(set! *warn-on-reflection* true)

(def ^:dynamic *recursion-limit*
  "A soft limit on how many times a branching spec (or/alt/*/opt-keys/multi-spec)
  can be recursed through during generation. After this a
  non-recursive branch will be chosen."
  4)

(def ^:dynamic *fspec-iterations*
  "The number of times an anonymous fn specified by fspec will be (generatively) tested during conform"
  21)

(def ^:dynamic *coll-check-limit*
  "The number of elements validated in a collection spec'ed with 'every'"
  101)

(def ^:dynamic *coll-error-limit*
  "The number of errors reported by explain in a collection spec'ed with 'every'"
  20)

(def ^:private ^:dynamic *instrument-enabled*
  "if false, instrumented fns call straight through"
  true)

(defprotocol Spec
  (conform* [spec x])
  (unform* [spec y])
  (explain* [spec path via in x])
  (gen* [spec overrides path rmap])
  (with-gen* [spec gfn])
  (describe* [spec]))

(defonce ^:private registry-ref (atom {}))

(defn- named? [x] (instance? clojure.lang.Named x))

(defn- with-name [spec name]
  (with-meta spec (assoc (meta spec) ::name name)))

(defn- spec-name [spec]
  (cond
   (keyword? spec) spec

   (instance? clojure.lang.IObj spec)
   (-> (meta spec) ::name)))

(defn- reg-resolve
  "returns the spec/regex at end of alias chain starting with k, nil if not found, k if k not Named"
  [k]
  (if (named? k)
    (let [reg @registry-ref]
      (loop [spec k]
        (if (named? spec)
          (recur (get reg spec))
          (when spec
            (with-name spec k)))))
    k))

(defn- reg-resolve!
  "returns the spec/regex at end of alias chain starting with k, throws if not found, k if k not ident"
  [k]
  (if (ident? k)
    (c/or (reg-resolve k)
          (throw (Exception. (str "Unable to resolve spec: " k))))
    k))

(defn spec?
  "returns x if x is a spec object, else logical false"
  [x]
  (c/and (extends? Spec (class x)) x))

(defn regex?
  "returns x if x is a (clojure.spec) regex op, else logical false"
  [x]
  (c/and (::op x) x))

(declare spec-impl)
(declare regex-spec-impl)

(defn- maybe-spec
  "spec-or-k must be a spec, regex or resolvable kw/sym, else returns nil."
  [spec-or-k]
  (let [s (c/or (spec? spec-or-k)
                (regex? spec-or-k)
                (c/and (named? spec-or-k) (reg-resolve spec-or-k))
                nil)]
    (if (regex? s)
      (with-name (regex-spec-impl s nil) (spec-name s))
      s)))

(defn- the-spec
  "spec-or-k must be a spec, regex or kw/sym, else returns nil. Throws if unresolvable kw/sym"
  [spec-or-k]
  (c/or (maybe-spec spec-or-k)
        (when (named? spec-or-k)
          (throw (Exception. (str "Unable to resolve spec: " spec-or-k))))))

(defn- specize [s]
  (c/or (the-spec s) (spec-impl ::unknown s nil nil)))

(defn conform
  "Given a spec and a value, returns :clojure.spec/invalid if value does not match spec,
  else the (possibly destructured) value."
  [spec x]
  (conform* (specize spec) x))

(defn unform
  "Given a spec and a value created by or compliant with a call to
  'conform' with the same spec, returns a value with all conform
  destructuring undone."
  [spec x]
  (unform* (specize spec) x))

(defn form
  "returns the spec as data"
  [spec]
  ;;TODO - incorporate gens
  (describe* (specize spec)))

(defn abbrev [form]
  (cond
   (seq? form)
   (walk/postwalk (fn [form]
                    (cond
                     (c/and (symbol? form) (namespace form))
                     (-> form name symbol)

                     (c/and (seq? form) (= 'fn (first form)) (= '[%] (second form)))
                     (last form)
                     
                     :else form))
                  form)

   (c/and (symbol? form) (namespace form))
   (-> form name symbol)

   :else form))

(defn describe
  "returns an abbreviated description of the spec as data"
  [spec]
  (abbrev (form spec)))

(defn with-gen
  "Takes a spec and a no-arg, generator-returning fn and returns a version of that spec that uses that generator"
  [spec gen-fn]
  (with-gen* (specize spec) gen-fn))

(defn explain-data* [spec path via in x]
  (when-let [probs (explain* (specize spec) path via in x)]
    {::problems probs}))

(defn explain-data
  "Given a spec and a value x which ought to conform, returns nil if x
  conforms, else a map with at least the key ::problems whose value is
  a collection of problem-maps, where problem-map has at least :path :pred and :val
  keys describing the predicate and the value that failed at that
  path."
  [spec x]
  (explain-data* spec [] (if-let [name (spec-name spec)] [name] []) [] x))

(defn explain-out
  "prints explanation data (per 'explain-data') to *out*."
  [ed]
  (if ed
    (do
      ;;(prn {:ed ed})
      (doseq [{:keys [path pred val reason via in] :as prob}  (::problems ed)]
        (when-not (empty? in)
          (print "In:" (pr-str in) ""))
        (print "val: ")
        (pr val)
        (print " fails")
        (when-not (empty? via)
          (print " spec:" (pr-str (last via))))
        (when-not (empty? path)
          (print " at:" (pr-str path)))
        (print " predicate: ")
        (pr pred)
        (when reason (print ", " reason))
        (doseq [[k v] prob]
          (when-not (#{:path :pred :val :reason :via :in} k)
            (print "\n\t" (pr-str k) " ")
            (pr v)))
        (newline))
      (doseq [[k v] ed]
        (when-not (#{::problems} k)
          (print (pr-str k) " ")
          (pr v)
          (newline))))
    (println "Success!")))

(defn explain
  "Given a spec and a value that fails to conform, prints an explanation to *out*."
  [spec x]
  (explain-out (explain-data spec x)))

(defn explain-str
  "Given a spec and a value that fails to conform, returns an explanation as a string."
  [spec x]
  (with-out-str (explain spec x)))

(declare valid?)

(defn- gensub
  [spec overrides path rmap form]
  ;;(prn {:spec spec :over overrides :path path :form form})
  (let [spec (specize spec)]
    (if-let [g (c/or (get overrides (c/or (spec-name spec) spec))
                     (get overrides path)
                     (gen* spec overrides path rmap))]
      (gen/such-that #(valid? spec %) g 100)
      (let [abbr (abbrev form)]
        (throw (ex-info (str "Unable to construct gen at: " path " for: " abbr)
                        {::path path ::no-gen-for form}))))))

(defn gen
  "Given a spec, returns the generator for it, or throws if none can
  be constructed. Optionally an overrides map can be provided which
  should map spec names or paths (vectors of keywords) to
  generators. These will be used instead of the generators at those
  names/paths. Note that parent generator (in the spec or overrides
  map) will supersede those of any subtrees. A generator for a regex
  op must always return a sequential collection (i.e. a generator for
  s/? should return either an empty sequence/vector or a
  sequence/vector with one item in it)"
  ([spec] (gen spec nil))
  ([spec overrides] (gensub spec overrides [] {::recursion-limit *recursion-limit*} spec)))

(defn- ->sym
  "Returns a symbol from a symbol or var"
  [x]
  (if (var? x)
    (let [^clojure.lang.Var v x]
      (symbol (str (.name (.ns v)))
              (str (.sym v))))
    x))

(defn- unfn [expr]
  (if (c/and (seq? expr)
             (symbol? (first expr))
             (= "fn*" (name (first expr))))
    (let [[[s] & form] (rest expr)]
      (conj (walk/postwalk-replace {s '%} form) '[%] 'fn))
    expr))

(defn- res [form]
  (cond
   (keyword? form) form
   (symbol? form) (c/or (-> form resolve ->sym) form)   
   (sequential? form) (walk/postwalk #(if (symbol? %) (res %) %) (unfn form))
   :else form))

(defn ^:skip-wiki def-impl
  "Do not call this directly, use 'def'"
  [k form spec]
  (assert (c/and (named? k) (namespace k)) "k must be namespaced keyword or resolvable symbol")
  (let [spec (if (c/or (spec? spec) (regex? spec) (get @registry-ref spec))
               spec
               (spec-impl form spec nil nil))]
    (swap! registry-ref assoc k spec)
    k))

(defn- ns-qualify
  "Qualify symbol s by resolving it or using the current *ns*."
  [s]
  (if-let [ns-sym (some-> s namespace symbol)]
    (c/or (some-> (get (ns-aliases *ns*) ns-sym) str (symbol (name s)))
          s)
    (symbol (str (.name *ns*)) (str s))))

(defmacro def
  "Given a namespace-qualified keyword or resolvable symbol k, and a
  spec, spec-name, predicate or regex-op makes an entry in the
  registry mapping k to the spec"
  [k spec-form]
  (let [k (if (symbol? k) (ns-qualify k) k)]
    `(def-impl '~k '~(res spec-form) ~spec-form)))

(defn registry
  "returns the registry map, prefer 'get-spec' to lookup a spec by name"
  []
  @registry-ref)

(defn get-spec
  "Returns spec registered for keyword/symbol/var k, or nil."
  [k]
  (get (registry) (if (keyword? k) k (->sym k))))

(declare map-spec)

(defmacro spec
  "Takes a single predicate form, e.g. can be the name of a predicate,
  like even?, or a fn literal like #(< % 42). Note that it is not
  generally necessary to wrap predicates in spec when using the rest
  of the spec macros, only to attach a unique generator

  Can also be passed the result of one of the regex ops -
  cat, alt, *, +, ?, in which case it will return a regex-conforming
  spec, useful when nesting an independent regex.
  ---

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator.

  Returns a spec."
  [form & {:keys [gen]}]
  (when form
    `(spec-impl '~(res form) ~form ~gen nil)))

(defmacro multi-spec
  "Takes the name of a spec/predicate-returning multimethod and a
  tag-restoring keyword or fn (retag).  Returns a spec that when
  conforming or explaining data will pass it to the multimethod to get
  an appropriate spec. You can e.g. use multi-spec to dynamically and
  extensibly associate specs with 'tagged' data (i.e. data where one
  of the fields indicates the shape of the rest of the structure).

  (defmulti mspec :tag)

  The methods should ignore their argument and return a predicate/spec:
  (defmethod mspec :int [_] (s/keys :req-un [::tag ::i]))

  retag is used during generation to retag generated values with
  matching tags. retag can either be a keyword, at which key the
  dispatch-tag will be assoc'ed, or a fn of generated value and
  dispatch-tag that should return an appropriately retagged value.

  Note that because the tags themselves comprise an open set,
  the tag key spec cannot enumerate the values, but can e.g.
  test for keyword?.

  Note also that the dispatch values of the multimethod will be
  included in the path, i.e. in reporting and gen overrides, even
  though those values are not evident in the spec.
"
  [mm retag]
  `(multi-spec-impl '~(res mm) (var ~mm) ~retag))

(defmacro keys
  "Creates and returns a map validating spec. :req and :opt are both
  vectors of namespaced-qualified keywords. The validator will ensure
  the :req keys are present. The :opt keys serve as documentation and
  may be used by the generator.

  The :req key vector supports 'and' and 'or' for key groups:

  (s/keys :req [::x ::y (or ::secret (and ::user ::pwd))] :opt [::z])

  There are also -un versions of :req and :opt. These allow
  you to connect unqualified keys to specs.  In each case, fully
  qualfied keywords are passed, which name the specs, but unqualified
  keys (with the same name component) are expected and checked at
  conform-time, and generated during gen:

  (s/keys :req-un [:my.ns/x :my.ns/y])

  The above says keys :x and :y are required, and will be validated
  and generated by specs (if they exist) named :my.ns/x :my.ns/y 
  respectively.

  In addition, the values of *all* namespace-qualified keys will be validated
  (and possibly destructured) by any registered specs. Note: there is
  no support for inline value specification, by design.

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator."
  [& {:keys [req req-un opt opt-un gen]}]
  (let [unk #(-> % name keyword)
        req-keys (filterv keyword? (flatten req))
        req-un-specs (filterv keyword? (flatten req-un))
        _ (assert (every? #(c/and (keyword? %) (namespace %)) (concat req-keys req-un-specs opt opt-un))
                  "all keys must be namespace-qualified keywords")
        req-specs (into req-keys req-un-specs)
        req-keys (into req-keys (map unk req-un-specs))
        opt-keys (into (vec opt) (map unk opt-un))
        opt-specs (into (vec opt) opt-un)
        parse-req (fn [rk f]
                    (map (fn [x]
                           (if (keyword? x)
                             `#(contains? % ~(f x))
                             (let [gx (gensym)]
                               `(fn* [~gx]
                                     ~(walk/postwalk
                                       (fn [y] (if (keyword? y) `(contains? ~gx ~(f y)) y))
                                       x)))))
                         rk))
        pred-exprs [`map?]
        pred-exprs (into pred-exprs (parse-req req identity))
        pred-exprs (into pred-exprs (parse-req req-un unk))
        pred-forms (walk/postwalk res pred-exprs)]
   ;; `(map-spec-impl ~req-keys '~req ~opt '~pred-forms ~pred-exprs ~gen)
    `(map-spec-impl {:req '~req :opt '~opt :req-un '~req-un :opt-un '~opt-un
                     :req-keys '~req-keys :req-specs '~req-specs
                     :opt-keys '~opt-keys :opt-specs '~opt-specs
                     :pred-forms '~pred-forms
                     :pred-exprs ~pred-exprs
                     :gfn ~gen})))

(defmacro or
  "Takes key+pred pairs, e.g.

  (s/or :even even? :small #(< % 42))

  Returns a destructuring spec that returns a map entry containing the
  key of the first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "spec/or expects k1 p1 k2 p2..., where ks are keywords")
    `(or-spec-impl ~keys '~pf ~pred-forms nil)))

(defmacro and
  "Takes predicate/spec-forms, e.g.

  (s/and even? #(< % 42))

  Returns a spec that returns the conformed value. Successive
  conformed values propagate through rest of predicates."
  [& pred-forms]
  `(and-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

(defmacro merge
  "Takes map-validating specs (e.g. 'keys' specs) and
  returns a spec that returns a conformed map satisfying all of the
  specs.  Successive conformed values propagate through rest of
  predicates. Unlike 'and', merge can generate maps satisfying the
  union of the predicates."
  [& pred-forms]
  `(merge-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

(defmacro every
  "takes a pred and validates collection elements against that pred.

  Note that 'every' does not do exhaustive checking, rather it samples
  *coll-check-limit* elements. Nor (as a result) does it do any
  conforming of elements. 'explain' will report at most *coll-error-limit*
  problems.  Thus 'every' should be suitable for potentially large
  collections.

  Takes several kwargs options that further constrain the collection:

  :kind - one of [], (), {}, #{} - must be this kind of collection - (default nil)
  :count - specifies coll has exactly this count (default nil)
  :min-count, :max-count - coll has count (<= min-count count max-count) (defaults nil)
  :distinct - all the elements are distinct (default nil)

  And additional args that control gen

  :gen-max - the maximum coll size to generate (default 20)
  :into - one of [], (), {}, #{} - the default collection to generate into (default same as :kind if supplied, else [])
  
  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator

  See also - coll-of, every-kv
"
  [pred & {:keys [into kind count max-count min-count distinct gen-max gen] :as opts}]
  `(every-impl '~pred ~pred ~(dissoc opts :gen) ~gen))

(defmacro every-kv
  "like 'every' but takes separate key and val preds and works on associative collections.

  Same options as 'every', :into defaults to {}

  See also - map-of"

  [kpred vpred & opts]
  `(every (tuple ~kpred ~vpred) ::kfn (fn [i# v#] (nth v# 0)) :into {} ~@opts))

(defmacro coll-of
  "Returns a spec for a collection of items satisfying pred. Unlike
  'every', coll-of will exhaustively conform every value.

  Same options as 'every'. conform will produce a collection
  corresponding to :into if supplied, else will match the input collection,
  avoiding rebuilding when possible.

  See also - every, map-of"
  [pred & opts]
  `(every ~pred ::conform-all true ~@opts))

(defmacro map-of
  "Returns a spec for a map whose keys satisfy kpred and vals satisfy
  vpred. Unlike 'every-kv', map-of will exhaustively conform every
  value.

  Same options as 'every', :kind set to {}, with the addition of:

  :conform-keys - conform keys as well as values (default false)

  See also - every-kv"
  [kpred vpred & opts]
  `(every-kv ~kpred ~vpred ::conform-all true ~@opts :kind {}))


(defmacro *
  "Returns a regex op that matches zero or more values matching
  pred. Produces a vector of matches iff there is at least one match"
  [pred-form]
  `(rep-impl '~(res pred-form) ~pred-form))

(defmacro +
  "Returns a regex op that matches one or more values matching
  pred. Produces a vector of matches"
  [pred-form]
  `(rep+impl '~(res pred-form) ~pred-form))

(defmacro ?
  "Returns a regex op that matches zero or one value matching
  pred. Produces a single value (not a collection) if matched."
  [pred-form]
  `(maybe-impl ~pred-form '~pred-form))

(defmacro alt
  "Takes key+pred pairs, e.g.

  (s/alt :even even? :small #(< % 42))

  Returns a regex op that returns a map entry containing the key of the
  first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return"
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "alt expects k1 p1 k2 p2..., where ks are keywords")
    `(alt-impl ~keys ~pred-forms '~pf)))

(defmacro cat
  "Takes key+pred pairs, e.g.

  (s/cat :e even? :o odd?)

  Returns a regex op that matches (all) values in sequence, returning a map
  containing the keys of each pred and the corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    ;;(prn key-pred-forms)
    (assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "cat expects k1 p1 k2 p2..., where ks are keywords")
    `(cat-impl ~keys ~pred-forms '~pf)))

(defmacro &
  "takes a regex op re, and predicates. Returns a regex-op that consumes
  input as per re but subjects the resulting value to the
  conjunction of the predicates, and any conforming they might perform."
  [re & preds]
  (let [pv (vec preds)]
    `(amp-impl ~re ~pv '~(mapv res pv))))

(defmacro conformer
  "takes a predicate function with the semantics of conform i.e. it should return either a
  (possibly converted) value or :clojure.spec/invalid, and returns a
  spec that uses it as a predicate/conformer. Optionally takes a
  second fn that does unform of result of first"
  ([f] `(spec-impl '~f ~f nil true))
  ([f unf] `(spec-impl '~f ~f nil true ~unf)))

(defmacro fspec
  "takes :args :ret and (optional) :fn kwargs whose values are preds
  and returns a spec whose conform/explain take a fn and validates it
  using generative testing. The conformed value is always the fn itself.

  See 'fdef' for a single operation that creates an fspec and
  registers it, as well as a full description of :args, :ret and :fn

  fspecs can generate functions that validate the arguments and
  fabricate a return value compliant with the :ret spec, ignoring
  the :fn spec if present.

  Optionally takes :gen generator-fn, which must be a fn of no args
  that returns a test.check generator."
  
  [& {:keys [args ret fn gen]}]
  `(fspec-impl (spec ~args) '~(res args)
               (spec ~ret) '~(res ret)
               (spec ~fn) '~(res fn) ~gen))

(defmacro tuple
  "takes one or more preds and returns a spec for a tuple, a vector
  where each element conforms to the corresponding pred. Each element
  will be referred to in paths using its ordinal."
  [& preds]
  (assert (not (empty? preds)))
  `(tuple-impl '~(mapv res preds) ~(vec preds)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; instrument ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- expect
  "Returns nil if v conforms to spec, else throws ex-info with explain-data."
  [spec v]
  )

(defn- fn-spec?
  "Fn-spec must include at least :args or :ret specs."
  [m]
  (c/or (:args m) (:ret m)))

(defmacro with-instrument-disabled
  "Disables instrument's checking of calls, within a scope."
  [& body]
  `(binding [*instrument-enabled* nil]
     ~@body))

(defn- spec-checking-fn
  [v f fn-spec]
  (let [fn-spec (maybe-spec fn-spec)
        conform! (fn [v role spec data args]
                   (let [conformed (conform spec data)]
                     (if (= ::invalid conformed)
                       (let [ed (assoc (explain-data* spec [role] [] [] data)
                                  ::args args)]
                         (throw (ex-info
                                 (str "Call to " v " did not conform to spec:\n" (with-out-str (explain-out ed)))
                                 ed)))
                       conformed)))]
    (c/fn
     [& args]
     (if *instrument-enabled*
       (with-instrument-disabled
         (when (:args fn-spec) (conform! v :args (:args fn-spec) args args))
         (binding [*instrument-enabled* true]
           (.applyTo ^clojure.lang.IFn f args)))
       (.applyTo ^clojure.lang.IFn f args)))))

(defn- macroexpand-check
  [v args]
  (let [fn-spec (get-spec v)]
    (when-let [arg-spec (:args fn-spec)]
      (when (= ::invalid (conform arg-spec args))
        (let [ed (assoc (explain-data* arg-spec [:args]
                                       (if-let [name (spec-name arg-spec)] [name] []) [] args)
                   ::args args)]
          (throw (IllegalArgumentException.
                   (str
                     "Call to " (->sym v) " did not conform to spec:\n"
                     (with-out-str (explain-out ed))))))))))

(defmacro fdef
  "Takes a symbol naming a function, and one or more of the following:

  :args A regex spec for the function arguments as they were a list to be
    passed to apply - in this way, a single spec can handle functions with
    multiple arities
  :ret A spec for the function's return value
  :fn A spec of the relationship between args and ret - the
    value passed is {:args conformed-args :ret conformed-ret} and is
    expected to contain predicates that relate those values

  Qualifies fn-sym with resolve, or using *ns* if no resolution found.
  Registers an fspec in the global registry, where it can be retrieved
  by calling get-spec with the var or fully-qualified symbol.

  Once registered, function specs are included in doc, checked by
  instrument, tested by the runner clojure.spec.test/run-tests, and (if
  a macro) used to explain errors during macroexpansion.

  Note that :fn specs require the presence of :args and :ret specs to
  conform values, and so :fn specs will be ignored if :args or :ret
  are missing.

  Returns the qualified fn-sym.

  For example, to register function specs for the symbol function:

  (s/fdef clojure.core/symbol
    :args (s/alt :separate (s/cat :ns string? :n string?)
                 :str string?
                 :sym symbol?)
    :ret symbol?)"
  [fn-sym & specs]
  `(clojure.spec/def ~fn-sym (clojure.spec/fspec ~@specs)))

(defn- no-fn-spec
  [v spec]
  (ex-info (str "Fn at " v " is not spec'ed.")
           {:var v :spec spec}))

(def ^:private instrumented-vars
     "Map for instrumented vars to :raw/:wrapped fns"
     (atom {}))

(defn- ->var
  [s-or-v]
  (if (var? s-or-v)
    s-or-v
    (let [v (c/and (symbol? s-or-v) (resolve s-or-v))]
      (if (var? v)
        v
        (throw (IllegalArgumentException. (str (pr-str s-or-v) " does not name a var")))))))

(defn- instrument-choose-fn
  "Helper for instrument."
  [f spec sym {over :gen :keys [stub replace]}]
  (if (some #{sym} stub)
    (-> spec (gen over) gen/generate)
    (get replace sym f)))

(defn- instrument-choose-spec
  "Helper for instrument"
  [spec sym {overrides :spec}]
  (get overrides sym spec))

(defn- collectionize
  [x]
  (if (symbol? x)
    (list x)
    x))

(defn- instrument-1
  [s opts]
  (when-let [v (resolve s)]
    (let [spec (get-spec v)
          {:keys [raw wrapped]} (get @instrumented-vars v)
          current @v
          to-wrap (if (= wrapped current) raw current)
          ospec (c/or (instrument-choose-spec spec s opts)
                      (throw (no-fn-spec v spec)))
          ofn (instrument-choose-fn to-wrap ospec s opts)
          checked (spec-checking-fn v ofn ospec)]
      (alter-var-root v (constantly checked))
      (swap! instrumented-vars assoc v {:raw to-wrap :wrapped checked}))
    (->sym v)))

(defn- unstrument-1
  [s]
  (when-let [v (resolve s)]
    (when-let [{:keys [raw wrapped]} (get @instrumented-vars v)]
      (let [current @v]
        (when (= wrapped current)
          (alter-var-root v (constantly raw))))
      (swap! instrumented-vars dissoc v))
    (->sym v)))

(defn- opt-syms
  "Returns set of symbols referenced by 'instrument' opts map"
  [opts]
  (reduce into #{} [(:stub opts) (c/keys (:replace opts)) (c/keys (:spec opts))]))

(defn- sym-matcher
  "Returns a fn that matches symbols that are either in syms,
or whose namespace is in syms."
  [syms]
  (let [names (into #{} (map str) syms)]
    (fn [s]
      (c/or (contains? names (namespace s))
            (contains? names (str s))))))

(defn- validate-opts
  [opts]
  (assert (every? ident? (c/keys (:gen opts))) "instrument :gen expects ident keys"))

(defn instrument
  "Instruments the vars matched by ns-or-names, a symbol or a
collection of symbols. Instruments the current namespace if
ns-or-names not specified. Idempotent.

A var matches ns-or-names if ns-or-names includes either the var's
fully qualified name or the var's namespace.

If a var has an :args fn-spec, sets the var's root binding to a
fn that checks arg conformance (throwing an exception on failure)
before delegating to the original fn.

The opts map can be used to override registered specs, and/or to
replace fn implementations entirely. Opts for symbols not matched
by ns-or-names are ignored. This facilitates sharing a common
options map across many different calls to instrument.

The opts map may have the following keys:

  :spec     a map from var-name symbols to override specs
  :stub     a collection of var-name symbols to be replaced by stubs
  :gen      a map from spec names to generator overrides
  :replace  a map from var-name symbols to replacement fns

:spec overrides registered fn-specs with specs your provide. Use
:spec overrides to provide specs for libraries that do not have
them, or to constrain your own use of a fn to a subset of its
spec'ed contract.

:stub replaces a fn with a stub that checks :args, then uses the
:ret spec to generate a return value.

:gen overrides are used only for :stub generation.

:replace replaces a fn with a fn that checks args conformance, then
invokes the fn you provide, enabling arbitrary stubbing and mocking.

:spec can be used in combination with :stub or :replace.

Returns a collection of syms naming the vars instrumented."
  ([] (instrument (.name ^clojure.lang.Namespace *ns*)))
  ([ns-or-names] (instrument ns-or-names nil))
  ([ns-or-names opts]
     (validate-opts opts)
     (let [match? (sym-matcher (collectionize ns-or-names))]
       (locking instrumented-vars
         (into
          []
          (comp c/cat
                (filter symbol?)
                (filter match?)
                (distinct)
                (map #(instrument-1 % opts))
                (remove nil?))
          [(c/keys (registry)) (opt-syms opts)])))))

(defn unstrument
  "Undoes instrument on the vars matched by ns-or-names, specified
as in instrument. Returns a collection of syms naming the vars
unstrumented."
  ([] (unstrument (.name ^clojure.lang.Namespace *ns*)))
  ([ns-or-names]
     (let [match? (sym-matcher (collectionize ns-or-names))]
       (locking instrumented-vars
         (into
          []
          (comp (map ->sym)
                 (filter match?)
                 (map unstrument-1)
                 (remove nil?))
           (c/keys @instrumented-vars))))))

(defn instrument-all
  "Like instrument, but works on all vars."
  ([] (instrument-all nil))
  ([opts]
     (validate-opts opts)
     (locking instrumented-vars
       (into
        []
        (comp c/cat
              (filter symbol?)
              (distinct)
              (map #(instrument-1 % opts))
              (remove nil?))
        [(c/keys (registry)) (opt-syms opts)]))))

(defn unstrument-all
  "Like unstrument, but works on all vars."
  []
  (locking instrumented-vars
    (into
     []
     (comp (map ->sym)
           (map unstrument-1)
           (remove nil?))
     (c/keys @instrumented-vars))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; impl ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- recur-limit? [rmap id path k]
  (c/and (> (get rmap id) (::recursion-limit rmap))
         (contains? (set path) k)))

(defn- inck [m k]
  (assoc m k (inc (c/or (get m k) 0))))

(defn- dt
  ([pred x form] (dt pred x form nil))
  ([pred x form cpred?]
     (if pred
       (if-let [spec (the-spec pred)]
         (conform spec x)
         (if (ifn? pred)
           (if cpred?
             (pred x)
             (if (pred x) x ::invalid))
           (throw (Exception. (str (pr-str form) " is not a fn, expected predicate fn")))))
       x)))

(defn valid?
  "Helper function that returns true when x is valid for spec."
  ([spec x]
     (not= ::invalid (dt spec x ::unknown)))
  ([spec x form]
     (not= ::invalid (dt spec x form))))

(defn- explain-1 [form pred path via in v]
  ;;(prn {:form form :pred pred :path path :in in :v v})
  (let [pred (maybe-spec pred)]
    (if (spec? pred)
      (explain* pred path (if-let [name (spec-name pred)] (conj via name) via) in v)
      [{:path path :pred (abbrev form) :val v :via via :in in}])))

(defn ^:skip-wiki map-spec-impl
  "Do not call this directly, use 'spec' with a map argument"
  [{:keys [req-un opt-un pred-exprs opt-keys req-specs req req-keys opt-specs pred-forms opt gfn]
    :as argm}]
  (let [keys-pred (apply every-pred pred-exprs)
        k->s (zipmap (concat req-keys opt-keys) (concat req-specs opt-specs))
        keys->specs #(c/or (k->s %) %)
        id (java.util.UUID/randomUUID)]
    (reify
     Spec
     (conform* [_ m]
               (if (keys-pred m)
                 (let [reg (registry)]
                   (loop [ret m, [k & ks :as keys] (c/keys m)]
                     (if keys
                       (if (contains? reg (keys->specs k))
                         (let [v (get m k)
                               cv (conform (keys->specs k) v)]
                           (if (= cv ::invalid)
                             ::invalid
                             (recur (if (identical? cv v) ret (assoc ret k cv))
                                    ks)))
                         (recur ret ks))
                       ret)))
                 ::invalid))
     (unform* [_ m]
              (let [reg (registry)]
                (loop [ret m, [k & ks :as keys] (c/keys m)]
                  (if keys
                    (if (contains? reg (keys->specs k))
                      (let [cv (get m k)
                            v (unform (keys->specs k) cv)]
                        (recur (if (identical? cv v) ret (assoc ret k v))
                               ks))
                      (recur ret ks))
                    ret))))
     (explain* [_ path via in x]
               (if-not (map? x)
                 [{:path path :pred 'map? :val x :via via :in in}]
                 (let [reg (registry)]
                   (apply concat
                          (when-let [probs (->> (map (fn [pred form] (when-not (pred x) (abbrev form)))
                                                     pred-exprs pred-forms)
                                                (keep identity)
                                                seq)]
                            [{:path path :pred (vec probs) :val x :via via :in in}])
                          (map (fn [[k v]]
                                 (when-not (c/or (not (contains? reg (keys->specs k)))
                                                 (valid? (keys->specs k) v k))
                                   (explain-1 (keys->specs k) (keys->specs k) (conj path k) via (conj in k) v)))
                               (seq x))))))
     (gen* [_ overrides path rmap]
           (if gfn
             (gfn)
             (let [rmap (inck rmap id)
                   gen (fn [k s] (gensub s overrides (conj path k) rmap k))
                   ogen (fn [k s]
                          (when-not (recur-limit? rmap id path k)
                            [k (gen/delay (gensub s overrides (conj path k) rmap k))]))
                   req-gens (map gen req-keys req-specs)
                   opt-gens (remove nil? (map ogen opt-keys opt-specs))]
               (when (every? identity (concat req-gens opt-gens))
                 (let [reqs (zipmap req-keys req-gens)
                       opts (into {} opt-gens)]
                   (gen/bind (gen/choose 0 (count opts))
                             #(let [args (concat (seq reqs) (when (seq opts) (shuffle (seq opts))))]
                                (->> args
                                     (take (c/+ % (count reqs)))
                                     (apply concat)
                                     (apply gen/hash-map)))))))))
     (with-gen* [_ gfn] (map-spec-impl (assoc argm :gfn gfn)))
     (describe* [_] (cons `keys
                          (cond-> []
                                  req (conj :req req)
                                  opt (conj :opt opt)
                                  req-un (conj :req-un req-un)
                                  opt-un (conj :opt-un opt-un)))))))




(defn ^:skip-wiki spec-impl
  "Do not call this directly, use 'spec'"
  ([form pred gfn cpred?] (spec-impl form pred gfn cpred? nil))
  ([form pred gfn cpred? unc]
     (cond
      (spec? pred) (cond-> pred gfn (with-gen gfn))
      (regex? pred) (regex-spec-impl pred gfn)
      (named? pred) (cond-> (the-spec pred) gfn (with-gen gfn))
      :else
      (reify
       Spec
       (conform* [_ x] (dt pred x form cpred?))
       (unform* [_ x] (if cpred?
                        (if unc
                          (unc x)
                          (throw (IllegalStateException. "no unform fn for conformer")))
                        x))
       (explain* [_ path via in x]
                 (when (= ::invalid (dt pred x form cpred?))
                   [{:path path :pred (abbrev form) :val x :via via :in in}]))
       (gen* [_ _ _ _] (if gfn
                         (gfn)
                         (gen/gen-for-pred pred)))
       (with-gen* [_ gfn] (spec-impl form pred gfn cpred?))
       (describe* [_] form)))))

(defn ^:skip-wiki multi-spec-impl
  "Do not call this directly, use 'multi-spec'"
  ([form mmvar retag] (multi-spec-impl form mmvar retag nil))
  ([form mmvar retag gfn]
     (let [id (java.util.UUID/randomUUID)
           predx #(let [^clojure.lang.MultiFn mm @mmvar]
                    (c/and (contains? (methods mm)
                                      ((.dispatchFn mm) %))
                           (mm %)))
           dval #((.dispatchFn ^clojure.lang.MultiFn @mmvar) %)
           tag (if (keyword? retag)
                 #(assoc %1 retag %2)
                 retag)]
       (reify
        Spec
        (conform* [_ x] (if-let [pred (predx x)]
                          (dt pred x form)
                          ::invalid))
        (unform* [_ x] (if-let [pred (predx x)]
                         (unform pred x)
                         (throw (IllegalStateException. (str "No method of: " form " for dispatch value: " (dval x))))))
        (explain* [_ path via in x]
                  (let [dv (dval x)
                        path (conj path dv)]
                    (if-let [pred (predx x)]
                      (explain-1 form pred path via in x)
                      [{:path path :pred form :val x :reason "no method" :via via :in in}])))
        (gen* [_ overrides path rmap]
              (if gfn
                (gfn)
                (let [gen (fn [[k f]]
                            (let [p (f nil)]
                              (let [rmap (inck rmap id)]
                                (when-not (recur-limit? rmap id path k)
                                  (gen/delay
                                   (gen/fmap
                                    #(tag % k)
                                    (gensub p overrides (conj path k) rmap (list 'method form k))))))))
                      gs (->> (methods @mmvar)
                              (remove (fn [[k]] (= k ::invalid)))
                              (map gen)
                              (remove nil?))]
                  (when (every? identity gs)
                    (gen/one-of gs)))))
        (with-gen* [_ gfn] (multi-spec-impl form mmvar retag gfn))
        (describe* [_] `(multi-spec ~form))))))

(defn ^:skip-wiki tuple-impl
  "Do not call this directly, use 'tuple'"
  ([forms preds] (tuple-impl forms preds nil))
  ([forms preds gfn]
     (reify
      Spec
      (conform* [_ x]
                (if-not (c/and (vector? x)
                               (= (count x) (count preds)))
                  ::invalid
                  (loop [ret x, i 0]
                    (if (= i (count x))
                      ret
                      (let [v (x i)
                            cv (dt (preds i) v (forms i))]
                        (if (= ::invalid cv)
                          ::invalid
                          (recur (if (identical? cv v) ret (assoc ret i cv))
                                 (inc i))))))))
      (unform* [_ x]
               (assert (c/and (vector? x)
                              (= (count x) (count preds))))
               (loop [ret x, i 0]
                 (if (= i (count x))
                   ret
                   (let [cv (x i)
                         v (unform (preds i) cv)]
                     (recur (if (identical? cv v) ret (assoc ret i v))
                            (inc i))))))
      (explain* [_ path via in x]
                (cond
                 (not (vector? x))
                 [{:path path :pred 'vector? :val x :via via :in in}]

                 (not= (count x) (count preds))
                 [{:path path :pred `(= (count ~'%) ~(count preds)) :val x :via via :in in}]

                 :else
                 (apply concat
                        (map (fn [i form pred]
                               (let [v (x i)]
                                 (when-not (valid? pred v)
                                   (explain-1 form pred (conj path i) via (conj in i) v))))
                             (range (count preds)) forms preds))))
      (gen* [_ overrides path rmap]
            (if gfn
              (gfn)
              (let [gen (fn [i p f]
                          (gensub p overrides (conj path i) rmap f))
                    gs (map gen (range (count preds)) preds forms)]
                (when (every? identity gs)
                  (apply gen/tuple gs)))))
      (with-gen* [_ gfn] (tuple-impl forms preds gfn))
      (describe* [_] `(tuple ~@forms)))))

(defn- tagged-ret [tag ret]
  (clojure.lang.MapEntry. tag ret))

(defn ^:skip-wiki or-spec-impl
  "Do not call this directly, use 'or'"
  [keys forms preds gfn]
  (let [id (java.util.UUID/randomUUID)
        kps (zipmap keys preds)
        cform (fn [x]
                (loop [i 0]
                  (if (< i (count preds))
                    (let [pred (preds i)]
                      (let [ret (dt pred x (nth forms i))]
                        (if (= ::invalid ret)
                          (recur (inc i))
                          (tagged-ret (keys i) ret))))
                    ::invalid)))]
    (reify
     Spec
     (conform* [_ x] (cform x))
     (unform* [_ [k x]] (unform (kps k) x))
     (explain* [this path via in x]
               (when-not (valid? this x)
                 (apply concat
                        (map (fn [k form pred]
                               (when-not (valid? pred x)
                                 (explain-1 form pred (conj path k) via in x)))
                             keys forms preds))))
     (gen* [_ overrides path rmap]
           (if gfn
             (gfn)
             (let [gen (fn [k p f]
                         (let [rmap (inck rmap id)]
                           (when-not (recur-limit? rmap id path k)
                             (gen/delay
                              (gensub p overrides (conj path k) rmap f)))))
                   gs (remove nil? (map gen keys preds forms))]
               (when-not (empty? gs)
                 (gen/one-of gs)))))
     (with-gen* [_ gfn] (or-spec-impl keys forms preds gfn))
     (describe* [_] `(or ~@(mapcat vector keys forms))))))

(defn- and-preds [x preds forms]
  (loop [ret x
         [pred & preds] preds
         [form & forms] forms]
    (if pred
      (let [nret (dt pred ret form)]
        (if (= ::invalid nret)
          ::invalid
          ;;propagate conformed values
          (recur nret preds forms)))
      ret)))

(defn- explain-pred-list
  [forms preds path via in x]
  (loop [ret x
         [form & forms] forms
         [pred & preds] preds]
    (when pred
      (let [nret (dt pred ret form)]
        (if (not= ::invalid nret)
          (recur nret forms preds)
          (explain-1 form pred path via in ret))))))

(defn ^:skip-wiki and-spec-impl
  "Do not call this directly, use 'and'"
  [forms preds gfn]
  (reify
   Spec
   (conform* [_ x] (and-preds x preds forms))
   (unform* [_ x] (reduce #(unform %2 %1) x (reverse preds)))
   (explain* [_ path via in x] (explain-pred-list forms preds path via in x))
   (gen* [_ overrides path rmap] (if gfn (gfn) (gensub (first preds) overrides path rmap (first forms))))
   (with-gen* [_ gfn] (and-spec-impl forms preds gfn))
   (describe* [_] `(and ~@forms))))

(defn ^:skip-wiki merge-spec-impl
  "Do not call this directly, use 'merge'"
  [forms preds gfn]
  (reify
   Spec
   (conform* [_ x] (and-preds x preds forms))
   (unform* [_ x] (reduce #(unform %2 %1) x (reverse preds)))
   (explain* [_ path via in x]
             (apply concat
                    (map #(explain-1 %1 %2 path via in x)
                         forms preds)))
   (gen* [_ overrides path rmap]
         (if gfn
           (gfn)
           (gen/fmap
            #(apply c/merge %)
            (apply gen/tuple (map #(gensub %1 overrides path rmap %2)
                                  preds forms)))))
   (with-gen* [_ gfn] (merge-spec-impl forms preds gfn))
   (describe* [_] `(merge ~@forms))))

(defn- coll-prob [x kfn kform distinct count min-count max-count
                  path via in]
  (let []
    (cond
     (not (kfn x))
     [{:path path :pred kform :val x :via via :in in}]

     (c/and distinct (not (empty? x)) (not (apply distinct? x)))
     [{:path path :pred 'distinct? :val x :via via :in in}]

     (c/and count (not= count (bounded-count count x)))
     [{:path path :pred `(= ~count (c/count ~'%)) :val x :via via :in in}]

     (c/and (c/or min-count max-count)
            (not (<= (c/or min-count 0)
                     (bounded-count (if max-count (inc max-count) min-count) x)
                     (c/or max-count Integer/MAX_VALUE))))
     [{:path path :pred `(<= ~(c/or min-count 0) (c/count ~'%) ~(c/or max-count 'Integer/MAX_VALUE)) :val x :via via :in in}])))

(defn ^:skip-wiki every-impl
  "Do not call this directly, use 'every', 'every-kv', 'coll-of' or 'map-of'"
  ([form pred opts] (every-impl form pred opts nil))
  ([form pred {gen-into :into
               :keys [kind count max-count min-count distinct gen-max ::kfn
                      conform-keys ::conform-all]
               :or {gen-max 20}
               :as opts}
    gfn]
     (let [conform-into (c/or gen-into kind)
           gen-into (c/or gen-into kind [])
           check? #(valid? pred %)
           kfn (c/or kfn (fn [i v] i))
           addcv (fn [ret i v cv] (conj ret cv))
           [kindfn kindform] (cond 
                              (map? kind)  [map? `map?]
                              (vector? kind)  [vector? `vector?]
                              (list? kind)  [list? `list?]
                              (set? kind) [set? `set?]
                              :else [seqable? `seqable?])
           cfns (fn [x]
                  ;;returns a tuple of [init add complete] fns
                  (cond
                   (c/and (vector? x) (c/or (not conform-into) (vector? conform-into)))
                   [identity
                    (fn [ret i v cv]
                      (if (identical? v cv)
                        ret
                        (assoc ret i cv)))
                    identity]

                   (c/and (map? x) (map? conform-into))
                   [(if conform-keys empty identity)
                    (fn [ret i v cv]
                      (if (c/and (identical? v cv) (not conform-keys))
                        ret
                        (assoc ret (nth (if conform-keys cv v) 0) (nth cv 1))))
                    identity]
                  
                   (c/or (list? conform-into) (c/and (not conform-into) (list? x)))
                   [(constantly ()) addcv reverse]

                   :else [#(empty (c/or conform-into %)) addcv identity]))]
       (reify
        Spec
        (conform* [_ x]
                  (cond
                   (coll-prob x kindfn kindform distinct count min-count max-count
                              nil nil nil)
                   ::invalid

                   conform-all
                   (let [[init add complete] (cfns x)]
                     (loop [ret (init x), i 0, [v & vs :as vseq] (seq x)]
                       (if vseq
                         (let [cv (dt pred v nil)]
                           (if (= ::invalid cv)
                             ::invalid
                             (recur (add ret i v cv) (inc i) vs)))
                         (complete ret))))
                   
                   
                   :else
                   (if (indexed? x)
                     (let [step (max 1 (long (/ (c/count x) *coll-check-limit*)))]
                       (loop [i 0]
                         (if (>= i (c/count x))
                           x
                           (if (check? (nth x i))
                             (recur (c/+ i step))
                             ::invalid))))
                     (c/or (c/and (every? check? (take *coll-check-limit* x)) x)
                           ::invalid))))
        (unform* [_ x] x)
        (explain* [_ path via in x]
                  (c/or (coll-prob x kindfn kindform distinct count min-count max-count
                                   path via in)
                        (apply concat
                               ((if conform-all identity (partial take *coll-error-limit*))
                                (keep identity
                                      (map (fn [i v]
                                             (let [k (kfn i v)]
                                               (when-not (check? v)
                                                 (let [prob (explain-1 form pred path via (conj in k) v)]
                                                   prob))))
                                           (range) x))))))
        (gen* [_ overrides path rmap]
              (if gfn
                (gfn)
                (let [init (empty gen-into)
                      pgen (gensub pred overrides path rmap form)]
                  (gen/fmap
                   #(if (vector? init) % (into init %))
                   (cond
                    distinct
                    (if count
                      (gen/vector-distinct pgen {:num-elements count :max-tries 100})
                      (gen/vector-distinct pgen {:min-elements (c/or min-count 0)
                                                 :max-elements (c/or max-count (max gen-max (c/* 2 (c/or min-count 0))))
                                                 :max-tries 100}))

                    count
                    (gen/vector pgen count)

                    (c/or min-count max-count)
                    (gen/vector pgen (c/or min-count 0) (c/or max-count (max gen-max (c/* 2 (c/or min-count 0)))))

                    :else
                    (gen/vector pgen 0 gen-max))))))
        
        (with-gen* [_ gfn] (every-impl form pred opts gfn))
        (describe* [_] `(every ~form ~@(mapcat identity opts)))))))

;;;;;;;;;;;;;;;;;;;;;;; regex ;;;;;;;;;;;;;;;;;;;
;;See:
;; http://matt.might.net/articles/implementation-of-regular-expression-matching-in-scheme-with-derivatives/
;; http://www.ccs.neu.edu/home/turon/re-deriv.pdf

;;ctors
(defn- accept [x] {::op ::accept :ret x})

(defn- accept? [{:keys [::op]}]
  (= ::accept op))

(defn- pcat* [{[p1 & pr :as ps] :ps,  [k1 & kr :as ks] :ks, [f1 & fr :as forms] :forms, ret :ret, rep+ :rep+}]
  (when (every? identity ps)
    (if (accept? p1)
      (let [rp (:ret p1)
            ret (conj ret (if ks {k1 rp} rp))]
        (if pr
          (pcat* {:ps pr :ks kr :forms fr :ret ret})
          (accept ret)))
      {::op ::pcat, :ps ps, :ret ret, :ks ks, :forms forms :rep+ rep+})))

(defn- pcat [& ps] (pcat* {:ps ps :ret []}))

(defn ^:skip-wiki cat-impl
  "Do not call this directly, use 'cat'"
  [ks ps forms]
  (pcat* {:ks ks, :ps ps, :forms forms, :ret {}}))

(defn- rep* [p1 p2 ret splice form]
  (when p1
    (let [r {::op ::rep, :p2 p2, :splice splice, :forms form :id (java.util.UUID/randomUUID)}]
      (if (accept? p1)
        (assoc r :p1 p2 :ret (conj ret (:ret p1)))
        (assoc r :p1 p1, :ret ret)))))

(defn ^:skip-wiki rep-impl
  "Do not call this directly, use '*'"
  [form p] (rep* p p [] false form))

(defn ^:skip-wiki rep+impl
  "Do not call this directly, use '+'"
  [form p]
  (pcat* {:ps [p (rep* p p [] true form)] :forms `[~form (* ~form)] :ret [] :rep+ form}))

(defn ^:skip-wiki amp-impl
  "Do not call this directly, use '&'"
  [re preds pred-forms]
  {::op ::amp :p1 re :ps preds :forms pred-forms})

(defn- filter-alt [ps ks forms f]
  (if (c/or ks forms)
    (let [pks (->> (map vector ps
                        (c/or (seq ks) (repeat nil))
                        (c/or (seq forms) (repeat nil)))
                   (filter #(-> % first f)))]
      [(seq (map first pks)) (when ks (seq (map second pks))) (when forms (seq (map #(nth % 2) pks)))])
    [(seq (filter f ps)) ks forms]))

(defn- alt* [ps ks forms]
  (let [[[p1 & pr :as ps] [k1 :as ks] forms] (filter-alt ps ks forms identity)]
    (when ps
      (let [ret {::op ::alt, :ps ps, :ks ks :forms forms}]
        (if (nil? pr) 
          (if k1
            (if (accept? p1)
              (accept (tagged-ret k1 (:ret p1)))
              ret)
            p1)
          ret)))))

(defn- alts [& ps] (alt* ps nil nil))
(defn- alt2 [p1 p2] (if (c/and p1 p2) (alts p1 p2) (c/or p1 p2)))

(defn ^:skip-wiki alt-impl
  "Do not call this directly, use 'alt'"
  [ks ps forms] (assoc (alt* ps ks forms) :id (java.util.UUID/randomUUID)))

(defn ^:skip-wiki maybe-impl
  "Do not call this directly, use '?'"
  [p form] (assoc (alt* [p (accept ::nil)] nil [form ::nil]) :maybe form))

(defn- noret? [p1 pret]
  (c/or (= pret ::nil)
        (c/and (#{::rep ::pcat} (::op (reg-resolve! p1))) ;;hrm, shouldn't know these
               (empty? pret))
        nil))

(declare preturn)

(defn- accept-nil? [p]
  (let [{:keys [::op ps p1 p2 forms] :as p} (reg-resolve! p)]
    (case op
          ::accept true
          nil nil
          ::amp (c/and (accept-nil? p1)
                       (c/or (noret? p1 (preturn p1))
                             (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                               (not= ret ::invalid))))
          ::rep (c/or (identical? p1 p2) (accept-nil? p1))
          ::pcat (every? accept-nil? ps)
          ::alt (c/some accept-nil? ps))))

(declare add-ret)

(defn- preturn [p]
  (let [{[p0 & pr :as ps] :ps, [k :as ks] :ks, :keys [::op p1 ret forms] :as p} (reg-resolve! p)]
    (case op
          ::accept ret
          nil nil
          ::amp (let [pret (preturn p1)]
                  (if (noret? p1 pret)
                    ::nil
                    (and-preds pret ps forms)))
          ::rep (add-ret p1 ret k)
          ::pcat (add-ret p0 ret k)
          ::alt (let [[[p0] [k0]] (filter-alt ps ks forms accept-nil?)
                      r (if (nil? p0) ::nil (preturn p0))]
                  (if k0 (tagged-ret k0 r) r)))))

(defn- op-unform [p x]
  ;;(prn {:p p :x x})
  (let [{[p0 & pr :as ps] :ps, [k :as ks] :ks, :keys [::op p1 ret forms rep+ maybe] :as p} (reg-resolve! p)
        kps (zipmap ks ps)]
    (case op
          ::accept [ret]
          nil [(unform p x)]
          ::amp (let [px (reduce #(unform %2 %1) x (reverse ps))]
                  (op-unform p1 px))
          ::rep (mapcat #(op-unform p1 %) x)
          ::pcat (if rep+
                   (mapcat #(op-unform p0 %) x)
                   (mapcat (fn [k]
                             (when (contains? x k)
                               (op-unform (kps k) (get x k))))
                           ks))
          ::alt (if maybe
                  [(unform p0 x)]
                  (let [[k v] x]
                    (op-unform (kps k) v))))))

(defn- add-ret [p r k]
  (let [{:keys [::op ps splice] :as p} (reg-resolve! p)
        prop #(let [ret (preturn p)]
                (if (empty? ret) r ((if splice into conj) r (if k {k ret} ret))))]
    (case op
          nil r
          (::alt ::accept ::amp)
          (let [ret (preturn p)]
            ;;(prn {:ret ret})
            (if (= ret ::nil) r (conj r (if k {k ret} ret))))

          (::rep ::pcat) (prop))))

(defn- deriv
  [p x]
  (let [{[p0 & pr :as ps] :ps, [k0 & kr :as ks] :ks, :keys [::op p1 p2 ret splice forms] :as p} (reg-resolve! p)]
    (when p
      (case op
            ::accept nil
            nil (let [ret (dt p x p)]
                  (when-not (= ::invalid ret) (accept ret)))
            ::amp (when-let [p1 (deriv p1 x)]
                    (if (= ::accept (::op p1))
                      (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                        (when-not (= ret ::invalid)
                          (accept ret)))
                      (amp-impl p1 ps forms)))
            ::pcat (alt2 (pcat* {:ps (cons (deriv p0 x) pr), :ks ks, :forms forms, :ret ret})
                         (when (accept-nil? p0) (deriv (pcat* {:ps pr, :ks kr, :forms (next forms), :ret (add-ret p0 ret k0)}) x)))
            ::alt (alt* (map #(deriv % x) ps) ks forms)
            ::rep (alt2 (rep* (deriv p1 x) p2 ret splice forms)
                        (when (accept-nil? p1) (deriv (rep* p2 p2 (add-ret p1 ret nil) splice forms) x)))))))

(defn- op-describe [p]  
  (let [{:keys [::op ps ks forms splice p1 rep+ maybe] :as p} (reg-resolve! p)]
    ;;(prn {:op op :ks ks :forms forms :p p})
    (when p
      (case op
            ::accept nil
            nil p
            ::amp (list* 'clojure.spec/& (op-describe p1) forms)
            ::pcat (if rep+
                     (list `+ rep+)
                     (cons `cat (mapcat vector (c/or (seq ks) (repeat :_)) forms)))
            ::alt (if maybe
                    (list `? maybe)
                    (cons `alt (mapcat vector ks forms)))
            ::rep (list (if splice `+ `*) forms)))))

(defn- op-explain [form p path via in input]
  ;;(prn {:form form :p p :path path :input input})
  (let [[x :as input] input
        {:keys [::op ps ks forms splice p1 p2] :as p} (reg-resolve! p)
        via (if-let [name (spec-name p)] (conj via name) via)
        insufficient (fn [path form]
                       [{:path path
                         :reason "Insufficient input"
                         :pred (abbrev form)
                         :val ()
                         :via via
                         :in in}])]
    (when p
      (case op
            ::accept nil
            nil (if (empty? input)
                  (insufficient path form)
                  (explain-1 form p path via in x))
            ::amp (if (empty? input)
                    (if (accept-nil? p1)
                      (explain-pred-list forms ps path via in (preturn p1))
                      (insufficient path (op-describe p1)))
                    (if-let [p1 (deriv p1 x)]
                      (explain-pred-list forms ps path via in (preturn p1))
                      (op-explain (op-describe p1) p1 path via in input)))
            ::pcat (let [pkfs (map vector
                                   ps
                                   (c/or (seq ks) (repeat nil))
                                   (c/or (seq forms) (repeat nil)))
                         [pred k form] (if (= 1 (count pkfs))
                                         (first pkfs)
                                         (first (remove (fn [[p]] (accept-nil? p)) pkfs)))
                         path (if k (conj path k) path)
                         form (c/or form (op-describe pred))]
                     (if (c/and (empty? input) (not pred))
                       (insufficient path form)
                       (op-explain form pred path via in input)))
            ::alt (if (empty? input)
                    (insufficient path (op-describe p))
                    (apply concat
                           (map (fn [k form pred]
                                  (op-explain (c/or form (op-describe pred))
                                              pred
                                              (if k (conj path k) path)
                                              via
                                              in
                                              input))
                                (c/or (seq ks) (repeat nil))
                                (c/or (seq forms) (repeat nil))
                                ps)))
            ::rep (op-explain (if (identical? p1 p2)
                                forms
                                (op-describe p1))
                              p1 path via in input)))))

(defn- re-gen [p overrides path rmap f]
  ;;(prn {:op op :ks ks :forms forms})
  (let [{:keys [::op ps ks p1 p2 forms splice ret id] :as p} (reg-resolve! p)
        rmap (if id (inck rmap id) rmap)
        ggens (fn [ps ks forms]
                (let [gen (fn [p k f]
                            ;;(prn {:k k :path path :rmap rmap :op op :id id})
                            (when-not (c/and rmap id k (recur-limit? rmap id path k))
                              (if id
                                (gen/delay (re-gen p overrides (if k (conj path k) path) rmap (c/or f p)))
                                (re-gen p overrides (if k (conj path k) path) rmap (c/or f p)))))]
                  (map gen ps (c/or (seq ks) (repeat nil)) (c/or (seq forms) (repeat nil)))))]
    (c/or (when-let [g (get overrides path)]
            (case op
                  (:accept nil) (gen/fmap vector g)
                  g))
          (when p
            (case op
                  ::accept (if (= ret ::nil)
                             (gen/return [])
                             (gen/return [ret]))
                  nil (when-let [g (gensub p overrides path rmap f)]
                        (gen/fmap vector g))
                  ::amp (re-gen p1 overrides path rmap (op-describe p1))
                  ::pcat (let [gens (ggens ps ks forms)]
                           (when (every? identity gens)
                             (apply gen/cat gens)))
                  ::alt (let [gens (remove nil? (ggens ps ks forms))]
                          (when-not (empty? gens)
                            (gen/one-of gens)))
                  ::rep (if (recur-limit? rmap id [id] id)
                          (gen/return [])
                          (when-let [g (re-gen p2 overrides path rmap forms)]
                            (gen/fmap #(apply concat %)
                                      (gen/vector g)))))))))

(defn- re-conform [p [x & xs :as data]]
  ;;(prn {:p p :x x :xs xs})
  (if (empty? data)
    (if (accept-nil? p)
      (let [ret (preturn p)]
        (if (= ret ::nil)
          nil
          ret))
      ::invalid)
    (if-let [dp (deriv p x)]
      (recur dp xs)
      ::invalid)))

(defn- re-explain [path via in re input]
  (loop [p re [x & xs :as data] input i 0]
    ;;(prn {:p p :x x :xs xs :re re}) (prn)
    (if (empty? data)
      (if (accept-nil? p)
        nil ;;success
        (op-explain (op-describe p) p path via in nil))
      (if-let [dp (deriv p x)]
        (recur dp xs (inc i))
        (if (accept? p)
          (if (= (::op p) ::pcat)
            (op-explain (op-describe p) p path via (conj in i) (seq data))
            [{:path path
              :reason "Extra input"
              :pred (abbrev (op-describe re))
              :val data
              :via via
              :in (conj in i)}])
          (c/or (op-explain (op-describe p) p path via (conj in i) (seq data))
                [{:path path
                  :reason "Extra input"
                  :pred (abbrev (op-describe p))
                  :val data
                  :via via
                  :in (conj in i)}]))))))

(defn ^:skip-wiki regex-spec-impl
  "Do not call this directly, use 'spec' with a regex op argument"
  [re gfn]
  (reify
   Spec
   (conform* [_ x]
             (if (c/or (nil? x) (coll? x))
               (re-conform re (seq x))
               ::invalid))
   (unform* [_ x] (op-unform re x))
   (explain* [_ path via in x]
             (if (c/or (nil? x) (coll? x))
               (re-explain path via in re (seq x))
               [{:path path :pred (abbrev (op-describe re)) :val x :via via :in in}]))
   (gen* [_ overrides path rmap]
         (if gfn
           (gfn)
           (re-gen re overrides path rmap (op-describe re))))
   (with-gen* [_ gfn] (regex-spec-impl re gfn))
   (describe* [_] (op-describe re))))

;;;;;;;;;;;;;;;;; HOFs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call-valid?
  [f specs args]
  (let [cargs (conform (:args specs) args)]
    (when-not (= cargs ::invalid)
      (let [ret (apply f args)
            cret (conform (:ret specs) ret)]
        (c/and (not= cret ::invalid) 
               (if (:fn specs)
                 (valid? (:fn specs) {:args cargs :ret cret})
                 true))))))

(defn- validate-fn
  "returns f if valid, else smallest"
  [f specs iters]
  (let [g (gen (:args specs))
        prop (gen/for-all* [g] #(call-valid? f specs %))]
    (let [ret (gen/quick-check iters prop)]
      (if-let [[smallest] (-> ret :shrunk :smallest)]
        smallest
        f))))

(defn ^:skip-wiki fspec-impl
  "Do not call this directly, use 'fspec'"
  [argspec aform retspec rform fnspec fform gfn]
  (let [specs {:args argspec :ret retspec :fn fnspec}]
    (reify
     clojure.lang.ILookup
     (valAt [this k] (get specs k))
     (valAt [_ k not-found] (get specs k not-found))

     Spec
     (conform* [_ f] (if (ifn? f)
                       (if (identical? f (validate-fn f specs *fspec-iterations*)) f ::invalid)
                       ::invalid))
     (unform* [_ f] f)
     (explain* [_ path via in f]
               (if (ifn? f)
                 (let [args (validate-fn f specs 100)]
                   (if (identical? f args) ;;hrm, we might not be able to reproduce
                     nil
                     (let [ret (try (apply f args) (catch Throwable t t))]
                       (if (instance? Throwable ret)
                         ;;TODO add exception data
                         [{:path path :pred '(apply fn) :val args :reason (.getMessage ^Throwable ret) :via via :in in}]

                         (let [cret (dt retspec ret rform)]
                           (if (= ::invalid cret)
                             (explain-1 rform retspec (conj path :ret) via in ret)
                             (when fnspec
                               (let [cargs (conform argspec args)]
                                 (explain-1 fform fnspec (conj path :fn) via in {:args cargs :ret cret})))))))))
                 [{:path path :pred 'ifn? :val f :via via :in in}]))
     (gen* [_ overrides _ _] (if gfn
             (gfn)
             (gen/return
              (fn [& args]
                (assert (valid? argspec args) (with-out-str (explain argspec args)))
                (gen/generate (gen retspec overrides))))))
     (with-gen* [_ gfn] (fspec-impl argspec aform retspec rform fnspec fform gfn))
     (describe* [_] `(fspec :args ~aform :ret ~rform :fn ~fform)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; non-primitives ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(clojure.spec/def ::any (spec (constantly true) :gen gen/any))
(clojure.spec/def ::kvs->map (conformer #(zipmap (map ::k %) (map ::v %)) #(map (fn [[k v]] {::k k ::v v}) %)))

(defmacro keys*
  "takes the same arguments as spec/keys and returns a regex op that matches sequences of key/values,
  converts them into a map, and conforms that map with a corresponding
  spec/keys call:

  user=> (s/conform (s/keys :req-un [::a ::c]) {:a 1 :c 2})
  {:a 1, :c 2}
  user=> (s/conform (s/keys* :req-un [::a ::c]) [:a 1 :c 2])
  {:a 1, :c 2}

  the resulting regex op can be composed into a larger regex:

  user=> (s/conform (s/cat :i1 integer? :m (s/keys* :req-un [::a ::c]) :i2 integer?) [42 :a 1 :c 2 :d 4 99])
  {:i1 42, :m {:a 1, :c 2, :d 4}, :i2 99}"
  [& kspecs]
  `(clojure.spec/& (* (cat ::k keyword? ::v ::any)) ::kvs->map (keys ~@kspecs)))

(defmacro nilable
  "returns a spec that accepts nil and values satisfiying pred"
  [pred]
  `(and (or ::nil nil? ::pred ~pred) (conformer second #(if (nil? %) [::nil nil] [::pred %]))))

(defn exercise
  "generates a number (default 10) of values compatible with spec and maps conform over them,
  returning a sequence of [val conformed-val] tuples. Optionally takes
  a generator overrides map as per gen"
  ([spec] (exercise spec 10))
  ([spec n] (exercise spec n nil))
  ([spec n overrides]
     (map #(vector % (conform spec %)) (gen/sample (gen spec overrides) n))))

(defn exercise-fn
  "exercises the fn named by sym (a symbol) by applying it to
  n (default 10) generated samples of its args spec. When fspec is
  supplied its arg spec is used, and sym-or-f can be a fn.  Returns a
  sequence of tuples of [args ret]. "
  ([sym] (exercise-fn sym 10))
  ([sym n] (exercise-fn sym n (get-spec sym)))
  ([sym-or-f n fspec]
     (let [f (if (symbol? sym-or-f) (resolve sym-or-f) sym-or-f)]
       (for [args (gen/sample (gen (:args fspec)) n)]
         [args (apply f args)]))))

(defn inst-in-range?
  "Return true if inst at or after start and before end"
  [start end inst]
  (c/and (inst? inst)
         (let [t (inst-ms inst)]
           (c/and (<= (inst-ms start) t) (< t (inst-ms end))))))

(defmacro inst-in
  "Returns a spec that validates insts in the range from start
(inclusive) to end (exclusive)."
  [start end]
  `(let [st# (inst-ms ~start)
         et# (inst-ms ~end)
         mkdate# (fn [d#] (java.util.Date. ^{:tag ~'long} d#))]
     (spec (and inst? #(inst-in-range? ~start ~end %))
       :gen (fn []
              (gen/fmap mkdate#
                (gen/large-integer* {:min st# :max et#}))))))

(defn int-in-range?
  "Return true if start <= val and val < end"
  [start end val]
  (c/and int? (<= start val) (< val end)))

(defmacro int-in
  "Returns a spec that validates ints in the range from start
(inclusive) to end (exclusive)."
  [start end]
  `(spec (and int? #(int-in-range? ~start ~end %))
     :gen #(gen/large-integer* {:min ~start :max (dec ~end)})))

(defmacro double-in
  "Specs a 64-bit floating point number. Options:

    :infinite? - whether +/- infinity allowed (default true)
    :NaN?      - whether NaN allowed (default true)
    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)"
  [& {:keys [infinite? NaN? min max]
    :or {infinite? true NaN? true}
    :as m}]
  `(spec (and c/double?
              ~@(when-not infinite? '[#(not (Double/isInfinite %))])
              ~@(when-not NaN? '[#(not (Double/isNaN %))])
              ~@(when max `[#(<= % ~max)])
              ~@(when min `[#(<= ~min %)]))
         :gen #(gen/double* ~m)))
