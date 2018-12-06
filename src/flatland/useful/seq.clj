(ns flatland.useful.seq
  (:use [flatland.useful.fn :only [decorate]]
        [flatland.useful.utils :only [pair]])
  (:import (java.util.concurrent LinkedBlockingQueue
                                 BlockingQueue)))

(defn find-first
  "Returns the first item of coll where (pred item) returns logical true."
  [pred coll]
  (first (filter pred coll)))

(defn find-with
  "Returns the val corresponding to the first key where (pred key) returns true."
  [pred keys vals]
  (->> (map vector keys vals)
       (find-first (comp pred first))
       last))

(defn extract
  "Extracts the first item that matches pred from coll, returning a vector of that item
   followed by coll with the item removed."
  [pred coll]
  (let [[head [item & tail]] (split-with (complement pred) coll)]
    [item (concat head tail)]))

(defn separate
  "Split coll into two sequences, one that matches pred and one that doesn't. Unlike the
  version in clojure.contrib.seq-utils, pred is only called once per item."
  [pred coll]
  (let [pcoll (map (decorate pred) coll)]
    (vec (for [f [filter remove]]
           (map first (f second pcoll))))))

(defn include?
  "Check if val exists in coll."
  [val coll]
  (some (partial = val) coll))

(defn zip
  "Returns a lazy sequence of vectors of corresponding items from each collection. If one collection
   is longer than the others, the missing items will be filled in with nils."
  [& colls]
  (lazy-seq
   (when (some seq colls)
     (cons (vec (map first colls))
           (apply zip (map rest colls))))))

(defn insert
  "Inserts a seq of items into coll at position n."
  [items n coll]
  (let [[before after] (split-at n coll)]
    (concat before items after)))

(defn slice
  "Divide coll into n approximately equal slices."
  [n coll]
  (loop [num n, slices [], items (vec coll)]
    (if (empty? items)
      slices
      (let [size (Math/ceil (/ (count items) num))]
        (recur (dec num) (conj slices (subvec items 0 size)) (subvec items size))))))

(defn cross
  "Computes the cartesian-product of the provided seqs. In other words, compute the set of all
  possible combinations of ways you can choose one item from each seq."
  [& seqs]
  (if (seq (rest seqs))
    (for [x (first seqs)
          y (apply cross (rest seqs))]
      (cons x y))
    (map list (first seqs))))

(defn lazy-cross
  "Compute a lazy cartesian-product of the provided seqs. The provided seqs can be lazy or even
   infinite, and lazy-cross will consume all sequences equally, only consuming more of any sequence
   when all possible combinations at the current level have been exhausted. This can be thought of
   intuitively as a breadth-first search of the cartesian product set."
  [& seqs]
  (letfn [(step [heads tails dim]
            (lazy-seq
             (when (< dim (count tails))
               (let [tail (get tails dim)]
                 (concat (apply cross (assoc heads dim tail))
                         (step (update-in heads [dim] concat tail)
                               tails (inc dim)))))))
          (lazy-cross [seqs level]
            (lazy-seq
             (let [heads (vec (map #(take level %) seqs))
                   tails (vec (map #(take 1 (drop level %)) seqs))]
               (when-not (every? empty? tails)
                 (concat (step heads tails 0)
                         (lazy-cross seqs (inc level)))))))]
    (lazy-cross seqs 0)))

(defn alternates
  "Split coll into 'threads' subsequences (defaults to 2), feeding
  each alternately from the input sequence. Effectively the inverse of
  interleave:

  (alternates 3 (range 9))
  ;=> ((0 3 6) (1 4 7) (2 5 8))"
  ([coll] (alternates 2 coll))
  ([threads coll]
     (lazy-seq
      (when (seq coll)
        (apply map list (partition threads coll))))))

(defmacro lazy-loop
  "Provide a simplified version of lazy-seq to eliminate
  boilerplate. Arguments are as to the built-in (loop...recur),
  and (lazy-recur) will be defined for you. However, instead of doing
  actual tail recursion, lazy-recur trampolines through lazy-seq. In
  addition to enabling laziness, this means you can call lazy-recur
  when not in the tail position.

  Regular recurs are also supported, if they are in tail position and don't
  need any laziness."
  [bindings & body]
  (let [f 'lazy-recur
        [names values] (alternates bindings)
        blob-names (repeatedly (count names) gensym)]
    `(letfn [(~f [~@blob-names]
               (lazy-seq
                 (iter# ~@blob-names)))
             (iter# [~@names]
               ~@body)]
       (~f ~@values))))

(defn unfold
  "Traditionally unfold is the 'opposite of reduce': it turns a single
  seed value into a (possibly infinite) lazy sequence of output
  values.

  Next is a function that operates on a seed: it should
  return a pair, [value new-seed]; the value half of the pair is
  inserted into the resulting list, while the new seed is used to
  continue unfolding. Notably, the value is never passed as an
  argument to next. If nil is returned instead of a pair, the resulting
  sequence will terminate.

  (defn fibs []
    (unfold (fn [[a b]]
              [a [b (+ a b)]])
            [0 1]))"
  [next seed]
  (lazy-loop [seed seed]
    (when-let [[val seed] (next seed)]
      (cons val (lazy-recur seed)))))

(defn take-shuffled
  "Lazily take (at most) n elements at random from coll, without
  replacement. For n=1, this is equivalent to rand-nth; for n>=(count
  coll) it is equivalent to shuffle.

  Clarification of \"without replacement\": each index in the original
  collection is chosen at most once. Thus if the original collection
  contains no duplicates, neither will the result of this
  function. But if the original collection contains duplicates, this
  function may include them in its output: it does not do any
  uniqueness checking aside from being careful not to use the same
  index twice."
  [n coll]
  (let [coll (vec coll)
        n (min n (count coll))]
    (take n
          (lazy-loop [coll coll]
            (let [idx (rand-int (count coll))
                  val (coll idx)
                  coll (-> coll
                           (assoc idx (peek coll))
                           pop)]
              (cons val (lazy-recur coll)))))))

(defn foldr
  "http://www.haskell.org/haskellwiki/Fold"
  [f start coll]
  (reduce #(f %2 %1) start (reverse coll)))

(defn unchunk
  "Create a one-at-a-time sequence out of a chunked sequence."
  [s]
  (lazy-seq
   (when-let [s (seq s)]
     (cons (first s)
           (unchunk (rest s))))))

(defmacro lazy
  "Return a lazy sequence of the passed-in expressions. Each will be evaluated
  only if necessary."
  [& exprs]
  `(map force (list ~@(for [expr exprs]
                        `(delay ~expr)))))

(defn glue
  "Walk over an input sequence, \"gluing\" together elements to create batches.
   Batches may be of any type you like, and are computed as follows:
   - Each batch is initialized by combining init (default false) with next-item.
   - For each additional item in coll, functions glue? and unglue? are consulted to
     decide whether the next item should be included into the current batch.
     - If (glue? current-batch next-item) returns truthy, then a prospective
       updated-batch is computed, as (combine current-batch next-item). If
       (unglue? updated-batch) returns falsey, then updated-batch is accepted and
       may be used as the target for further gluing.
     - If glue? returned falsey, or unglue? returned truthy, then the current batch
       is inserted into the output sequence, and a new batch is started as
       (combine init next-item)."
  ([combine glue? coll]
     (glue combine nil glue? coll))
  ([combine init glue? coll]
     (glue combine init glue? (constantly false) coll))
  ([combine init glue? unglue? coll]
     (lazy-seq
       (when-let [coll (seq coll)]
         (lazy-loop [glob (combine init (first coll)), coll (rest coll)]
           (if-let [coll (seq coll)]
             (let [x (first coll)
                   more (rest coll)
                   glued (delay (combine glob x))]
               (if (and (glue? glob x)
                        (not (unglue? @glued)))
                 (recur @glued more)
                 (cons glob (lazy-recur (combine init x) more))))
             (list glob)))))))

(defn partition-between
  "Partition an input seq into multiple sequences, as with partition-by.
   Walks the collection two at a time, calling (split? [a b]) for each pair.
   Any time split? returns truthy, the partition containing a ends, and a new
   one containing b begins. Note that the split? predicate should not take two
   arguments, but instead a single argument, a pair.

   Like partition-by, a lazy sequence of partitions is returned, but the
   partitions themselves are eager.

   For example, to cause each nil to be folded into the next partition:
   (partition-between (fn [[a b]] (not (nil? a))) '[1 nil nil 2 nil 3])
   => ([1] [nil nil 2] [nil 3])"
  [split? coll]
  (glue conj []
        (fn [v x]
          (not (split? [(peek v) x])))
        (constantly false)
        coll))

(defn remove-prefix
  "Remove prefix from coll, returning the remaining suffix. Returns nil if prefix does not
  match coll."
  [prefix coll]
  (if (seq prefix)
    (and (seq coll)
         (= (first prefix) (first coll))
         (recur (rest prefix) (rest coll)))
    coll))

(defn prefix-of?
  "Given prefix is N elements long, are the first N elements of coll equal to prefix?"
  [coll prefix]
  (boolean (remove-prefix prefix coll)))

(defn merge-sorted
  "Merge N sorted sequences together, as in the merge phase of a merge-sort.
   Comparator should be a two-argument predicate like `<`, which returns true if
   its first argument belongs before its second element in the merged sequence.
   The collections themselves should already be sorted in the order your
   comparator would put them; otherwise ordering is undefined."
  ([comparator]
     nil)
  ([comparator xs]
     xs)
  ([comparator xs ys]
     (lazy-loop [xs xs, ys ys]
       (if-let [xs (seq xs)]
         (if-let [ys (seq ys)]
           (let [x (first xs), y (first ys)]
             (if (comparator x y)
               (cons x (lazy-recur (rest xs) ys))
               (cons y (lazy-recur xs (rest ys)))))
           xs)
         ys)))
  ([comparator xs ys & more]
     (apply merge-sorted comparator
            (merge-sorted comparator xs ys)
            more)))

(defn indexed
  "Returns a lazy sequence of pairs of index and item."
  [coll]
  (map-indexed pair coll))

(defn sequeue
  "A version of seque from clojure.core that uses a future instead of an agent.
The agent version was causing problems because you can't depend on an agent from
within another agent's action, which means you can't use seque inside an agent.

This version is probably less performant, because it keeps a thread running
until the sequence is entirely consumed, and it attempts to refill the queue as
soon as there is space, rather than when the queue is emptied.

More importantly, though, this version may be *DANGEROUS* if you are not careful:
if you do not consume the entire output sequence, the future-thread will remain
active indefinitely, blocking on the queue and holding the lazy sequence open,
ineligible for garbage collection."
  ([s] (sequeue 100 s))
  ([n-or-q s]
     (let [^BlockingQueue q (if (instance? BlockingQueue n-or-q)
                              n-or-q
                              (LinkedBlockingQueue. (int n-or-q)))
           NIL (Object.)            ;nil sentinel since LBQ doesn't support nils
           worker (future
                    (try
                      (loop [[x & xs :as s] (seq s)]
                        (if s
                          (do (.put q (if (nil? x) NIL x))
                              (recur xs))
                          (.put q q)))  ; q itself is eos sentinel
                      (catch Exception e
                        (.put q q)
                        (throw e))))]
       (lazy-loop []
         (let [x (.take q)]
           (if (identical? x q)         ;q itself is eos sentinel
             (do @worker nil)           ;just to propagate errors
             (cons (if (identical? x NIL) nil x)
                   (lazy-recur))))))))

(defn seque*
  "A version of clojure.core/seque that fixes a memory/thread-handle leak."
  {:added "1.0"
   :static true}
  ([s] (seque 100 s))
  ([n-or-q s]
   (let [^BlockingQueue q (if (instance? BlockingQueue n-or-q)
                             n-or-q
                             (LinkedBlockingQueue. (int n-or-q)))
         NIL (Object.) ;nil sentinel since LBQ doesn't support nils
         agt (agent (sequence s)) ; never start with nil; that signifies we've already put eos
         log-error (fn [q e]
                     (if (.offer q q)
                       (throw e)
                       e))
         fill (fn [s]
                (when s
                  (if (instance? Exception s) ; we failed to .offer an error earlier
                    (log-error q s)
                    (try
                      (loop [[x & xs :as s] (seq s)]
                        (if s
                          (if (.offer q (if (nil? x) NIL x))
                            (recur xs)
                            s)
                          (when-not (.offer q q) ; q itself is eos sentinel
                            ()))) ; empty seq, not nil, so we know to put eos next time
                      (catch Exception e
                        (log-error q e))))))
         drain (fn drain []
                 (lazy-seq
                  (let [x (.take q)]
                    (if (identical? x q) ;q itself is eos sentinel
                      (do @agt nil)  ;touch agent just to propagate errors
                      (do
                        (send-off agt fill)
                        (cons (if (identical? x NIL) nil x) (drain)))))))]
     (send-off agt fill)
     (drain))))

(defn take-until
  "Take from coll up to and including the first item that satisfies pred."
  [pred coll]
  (lazy-seq
   (when-let [coll (seq coll)]
     (let [x (first coll)]
       (cons x (when-not (pred x)
                 (take-until pred (rest coll))))))))

(defn map-nth
  "Calls f on every nth element of coll. If start is passed, starts
   at that element (counting from zero), otherwise starts with zero."
  ([f nth coll] (map-nth f 0 nth coll))
  ([f start nth coll]
     (map #(% %2)
          (concat (repeat start identity)
                  (cycle (cons f (repeat (dec nth) identity))))
          coll)))

(defn update-first
  "Returns a lazy-seq that is a version of coll with the first item matching
  pred updated by calling f on it with the supplied args."
  ([coll pred f]
     (lazy-seq
      (if-let [coll (seq coll)]
        (let [x (first coll)
              xs (rest coll)]
          (if (pred x)
            (cons (f x) xs)
            (cons x (update-first xs pred f))))
        (list (f nil)))))
  ([coll pred f & args]
     (update-first coll pred #(apply f % args))))

(defn single?
  "Does coll have only one element?"
  [coll]
  (and (seq coll)
       (not (next coll))))

(defn assert-length
  "Assert, as a side effect, that coll has exactly len elements, and then
   return coll."
  [len coll]
  (if (zero? len)
    (assert (empty? coll) "Too many elements")
    (let [last-expected (nthnext coll (dec len))]
      (assert last-expected "Too few elements")
      (assert (not (next last-expected)) "Too many elements")))
  coll)

(defn flatten-all
  "Takes a nested collection and flattens it into one flat collection.
   Like clojure.core/flatten, but also works with maps and collections
   containing nested maps."
  [form] (remove coll? (tree-seq coll? seq form)))

(defn groupings
  "Similar to clojure.core/group-by, but allowing you to specify how to add items to each group.
   For example, if you are grouping by :name, you may want to remove the :name key from each map
   before adding it to the list. So, you can specify #(dissoc % :name) as your transform.

   If you need finer-grained control, you can specify a reduce function for accumulating each group,
   rather than mapping over the items in it. For example, (groupings even? + 0 coll) finds you the
   sum of all odd numbers in coll and the sum of all even numbers in coll."
  ([group transform coll]
     (groupings group #(conj %1 (transform %2)) [] coll))
  ([group reductor init coll]
     (loop [ret {}, coll (seq coll)]
       (if-not coll
         ret
         (let [x (first coll)
               category (group x)]
           (recur (assoc ret category (reductor (get ret category init) x))
                  (next coll)))))))

(defn increasing*
  "Scans through a collection, comparing items via (comp (keyfn x) (keyfn y)), and finding those
  which are in increasing order. Each input item x is output once, as part of a pair, [included?
  x]. Those items which are part of an increasing sequence will have included? true, while any that
  go \"backwards\" from the current max will have included? false."
  [keyfn comp coll]
  (lazy-seq
    (when-first [x coll]
      (let [max (keyfn x)]
        (cons [true x]
              (lazy-loop [max max, coll (rest coll)]
                (when-first [x coll]
                  (let [key (keyfn x)]
                    (if (neg? (comp key max))
                      (cons [false x] (lazy-recur max (rest coll)))
                      (cons [true x] (lazy-recur key (rest coll))))))))))))

(defn increasing
  "Throw away any elements from coll which are not in increasing order, according to keyfn and
   comp (used similarly to the arguments to sort-by)."
  ([coll]
     (increasing identity compare coll))
  ([keyfn coll]
     (increasing keyfn compare coll))
  ([keyfn comp coll]
     (map second (filter first (increasing* keyfn comp coll)))))
