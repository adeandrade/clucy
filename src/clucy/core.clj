(ns clucy.core
  (:import (java.io StringReader File)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.document Document Field Field$Index Field$Store)
           (org.apache.lucene.index IndexWriter IndexWriter$MaxFieldLength Term IndexNotFoundException)
           (org.apache.lucene.queryParser QueryParser)
           (org.apache.lucene.search BooleanClause BooleanClause$Occur
                                     BooleanQuery IndexSearcher TermQuery ScoreDoc)
           (org.apache.lucene.search.highlight Highlighter QueryScorer
                                               SimpleHTMLFormatter)
           (org.apache.lucene.store NIOFSDirectory RAMDirectory)
           (org.apache.lucene.util Version)))

(def ^{:dynamic true} *version* Version/LUCENE_CURRENT)
(def ^{:dynamic true} *analyzer* (StandardAnalyzer. *version*))

;; To avoid a dependency on either contrib or 1.2+
(defn as-str [x]
  (if (keyword? x)
    (name x)
    (str x)))

;; flag to indicate a default "_content" field should be maintained
(def ^{:dynamic true} *content* true)

(defn memory-index
  "Create a new index in RAM."
  []
  (RAMDirectory.))

(defn disk-index
  "Create a new index in a directory on disk."
  [dir-path]
  (NIOFSDirectory. (File. dir-path)))

(defn- index-writer
  "Create an IndexWriter."
  [index]
  (IndexWriter. index
                *analyzer*
                IndexWriter$MaxFieldLength/UNLIMITED))

(defn- add-field
  "Add a Field to a Document.
  Following options are allowed for meta-map:
  :stored - when false, then do not store the field value in the index.
  :indexed - when false, then do not index the field.
  :analyzed - when :indexed is enabled use this option to disable/eneble Analyzer for current field.
  :norms - when :indexed is enabled user this option to disable/enable the storing of norms."
  ([document key value]
     (add-field document key value {}))

  ([document key value meta-map]
     (.add document
           (Field. (as-str key) (as-str value)
                   (if (false? (:stored meta-map))
                     Field$Store/NO
                     Field$Store/YES)
                   (if (false? (:indexed meta-map))
                     Field$Index/NO
                     (case [(false? (:analyzed meta-map)) (false? (:norms meta-map))]
                       [false false] Field$Index/ANALYZED
                       [true false] Field$Index/NOT_ANALYZED
                       [false true] Field$Index/ANALYZED_NO_NORMS
                       [true true] Field$Index/NOT_ANALYZED_NO_NORMS))))))

(defn- indexed-fields
  "Returns a hash-map containing all of the values in the map that
  will be indexed  in the search index."
  [map-in]
  (merge {}
         (filter (complement nil?)
                 (map (fn [item]
                        (if (or (= nil (meta map-in))
                                (not= false
                                      (:indexed ((first item) (meta map-in)))))
                          item)) map-in))))

(defn- concat-values
  "Concatenate all the maps values being indexed into a single string."
  [map-in]
  (apply str (interpose " " (vals (indexed-fields map-in)))))

(defn- map->document
  "Create a Document from a map."
  [map]
  (let [document (Document.)]
    (doseq [[key value] map]
      (add-field document key value (key (meta map))))
    (if *content*
      (add-field document :_content (concat-values map) (:_content (meta map))))
    document))

(defn add
  "Add hash-maps to the search index."
  [index & maps]
  (with-open [writer (index-writer index)]
    (doseq [m maps]
      (.addDocument writer (map->document m)))))

(defn delete
  "Deletes hash-maps from the search index."
  [index & maps]
  (with-open [writer (index-writer index)]
    (doseq [m maps]
      (let [query (BooleanQuery.)]
        (doseq [[key value] m]
          (.add query
                (BooleanClause.
                 (TermQuery. (Term. (.toLowerCase (as-str key))
                                    (.toLowerCase (as-str value))))
                 BooleanClause$Occur/MUST)))
        (.deleteDocuments writer query)))))

(defn- document->map
  "Turn a Document object into a map."
  ([document score]
     (document->map document score (constantly nil)))
  ([document score highlighter]
     (let [m (into {} (for [f (.getFields document)]
                        [(keyword (.name f)) (.stringValue f)]))
           fragments (highlighter m) ; so that we can highlight :_content
           m (dissoc m :_content)]
       (with-meta
         m
         (-> (into {}
                   (for [f (.getFields document)]
                     [(keyword (.name f)) {:indexed (.isIndexed f)
                                           :stored (.isStored f)
                                           :tokenized (.isTokenized f)}]))
             (assoc :_fragments fragments :_score score)
             (dissoc :_content))))))

(defn- make-highlighter
  "Create a highlighter function which will take a map and return highlighted
fragments."
  [query searcher config]
  (if config
    (let [indexReader (.getIndexReader searcher)
          scorer (QueryScorer. (.rewrite query indexReader))
          config (merge {:field :_content
                         :max-fragments 5
                         :separator "..."
                         :pre "<b>"
                         :post "</b>"}
                        config)
          {:keys [field max-fragments separator fragments-key pre post]} config
          highlighter (Highlighter. (SimpleHTMLFormatter. pre post) scorer)]
      (fn [m]
        (let [str (field m)
              token-stream (.tokenStream *analyzer*
                                         (name field)
                                         (StringReader. str))]
          (.getBestFragments highlighter
                             token-stream
                             str
                             max-fragments
                             separator))))
    (constantly nil)))

(defn search
  "Search the supplied index with a query string."
  [index query max-results & {:keys [highlight default-field default-operator limit page] :or {limit max-results page 0}}]
  (if (every? false? [default-field *content*])
    (throw (Exception. "No default search field specified"))
    (let [default-field (or default-field :_content)
          searcher (try (IndexSearcher. index)
                        (catch IndexNotFoundException _ nil))]
      (if searcher
        (with-open [searcher searcher]
          (let [parser (doto (QueryParser. *version* (as-str default-field) *analyzer*)
                         (.setDefaultOperator (case (or default-operator :or)
                                                :and QueryParser/AND_OPERATOR
                                                :or  QueryParser/OR_OPERATOR)))
                query (.parse parser query)
                hits (.search searcher query max-results)
                highlighter (make-highlighter query searcher highlight)
                positions (let [total (min max-results (.totalHits hits))
                                start (* page limit)
                                end (+ start limit)]
                            (condp > total
                              start ()
                              end (range start total)
                              (range start end)))]
            (doall
             (with-meta (let [docs (.scoreDocs hits)]
                          (for [hit (map #(aget ^ScoreDoc docs %) positions)]
                            (document->map (.doc searcher (.doc hit))
                                           (.score hit)
                                           highlighter)))
               {:_total-hits (.totalHits hits)
                :_max-score (.getMaxScore hits)}))))
        (list)))))

(defn search-and-delete
  "Search the supplied index with a query string and then delete all
of the results."
  ([index query]
     (if *content*
       (search-and-delete index query :_content)
       (throw (Exception. "No default search field specified"))))
  ([index query default-field]
     (with-open [writer (index-writer index)]
       (let [parser (QueryParser. *version* (as-str default-field) *analyzer*)
             query  (.parse parser query)]
         (.deleteDocuments writer query)))))
