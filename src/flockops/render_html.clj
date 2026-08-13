(ns flockops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and no generator at all. Everything on
  the rendered page is produced by driving the REAL actor stack --
  `flockops.operation/build` -> `flockops.advisor` -> `flockops.governor`
  -> `flockops.phase` -> `flockops.store` -- and reading back what those
  namespaces actually returned. No hand-typed entity, id, count, cost,
  hold reason or Japanese detail string appears in the output: the hold
  details you see are the literal `:detail` strings `flockops.governor`
  emitted during this run.

  Scope note (measured, not assumed): this repo's own sim driver
  (`clojure -M:dev:run`, run BEFORE this file was written) seeds its
  facility inline -- there is no `flockops.store/demo-data` to reuse, so
  the facility seed below lives here. Its `:breed` values are real
  `flockops.facts/breeds` ids, and the facility table is rendered from
  `flockops.store/registered-facility` reads rather than from the seed
  literal, so the page shows what the Store answers, not what was typed.

  The scenario deliberately exercises BOTH outcomes the flagship item
  requires:
    * every one of the Governor's five HARD rules
      (`facility-not-registered`, `no-execution`,
      `treatment-or-culling-blocked`, `op-not-allowed`,
      `flock-count-invalid`) -- refusals that never reach a human, and
    * approved paths (autonomous commits at phase-2/3 and escalations
      queued for a farm operator / veterinarian).

  It also separates two things that are easy to blur: a **Governor
  refusal** (hard violation, non-negotiable) and a **rollout phase-gate
  hold/escalation** (`flockops.phase`), which can carry an EMPTY
  `:violations` vector. `-main` therefore enforces a two-stage
  invariant: at least one `:governor-hold` fact AND at least one of them
  carrying a non-empty `:violations` -- a phase-gating hold alone does
  not satisfy it.

  Deterministic: no timestamps in page content, all collections
  iterated in an explicit order, byte-identical across reruns from the
  same seed.

  Usage: `clojure -M:render-html [out-file]`
         (default `docs/samples/operator-console.html`)

  `:dev` is not needed: this repo's `:deps` is empty, so the `:dev`
  alias's `:override-deps` for langchain/langgraph overrides nothing.
  `clojure -M:render-html` also works and produces the identical
  bytes (both measured)."
  (:refer-clojure :exclude [num])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [flockops.advisor :as advisor]
            [flockops.facts :as facts]
            [flockops.governor :as governor]
            [flockops.operation :as operation]
            [flockops.phase :as phase]
            [flockops.store :as store]
            [jp-go-dds.skin :as skin]))

;; --------------------------------------------------------------------
;; Scenario advisor
;; --------------------------------------------------------------------

(defrecord ScenarioAdvisor [base]
  advisor/Advisor
  (-advise [_this st request]
    ;; Delegates to the repo's own `flockops.advisor/mock-advisor`, then
    ;; applies an explicit per-request override. The override exists to
    ;; simulate a MISBEHAVING advisor (an LLM that lowers its own
    ;; confidence, or claims a non-`:propose` effect) so the Governor's
    ;; independent checks can be seen refusing it. It never fabricates
    ;; a verdict -- the Governor still runs on the resulting proposal.
    (merge (advisor/-advise base st request)
           (:demo/advisor-override request))))

(defn- scenario-advisor []
  (->ScenarioAdvisor (advisor/mock-advisor)))

;; --------------------------------------------------------------------
;; Seed + scenario
;; --------------------------------------------------------------------

(def ^:private facility-seed
  "Ordered facility seed. `:breed` values are ids of real entries in
  `flockops.facts/breeds` -- the page resolves them through
  `flockops.facts/breed-by-id`, so a typo here would render as an
  unresolved breed rather than as a plausible-looking invention."
  [["farm-001" {:id "farm-001" :name "Sunrise Flock Farm"
                :paddock "Paddock 4, Pen 7" :breed "suffolk"}]
   ["farm-002" {:id "farm-002" :name "Kitayama Goat Dairy"
                :paddock "Paddock 2, Milking Pen 1" :breed "saanen"}]
   ["farm-003" {:id "farm-003" :name "Hokuriku Merino Station"
                :paddock "Paddock 9, Shearing Shed 2" :breed "merino"}]
   ["farm-004" {:id "farm-004" :name "Shikoku Boer Ranch"
                :paddock "Paddock 1, Pen 3" :breed "boer"}]])

(def ^:private unregistered-facility-id
  "Deliberately NOT seeded -- used to exercise the Governor's
  `facility-not-registered` hard rule."
  "farm-999")

(def ^:private operator-context-base
  {:actor-id "flock-ops-01" :role :farm-operator})

(def ^:private scenario
  "Ordered scenario. Each entry is scenario INPUT (the operator request
  and the rollout phase); every disposition, verdict, violation and
  audit fact shown on the page is OUTPUT read back from the actor."
  [{:id "R01" :phase :phase-2
    :note "routine husbandry batch entry, reduced supervision"
    :request {:op :log-husbandry-record :facility-id "farm-001"
              :count 85 :weight 62 :health-status "healthy"
              :fleece-weight 3}}

   {:id "R02" :phase :phase-0
    :note "same request under simulation rollout -- no autonomous commit"
    :request {:op :log-husbandry-record :facility-id "farm-001"
              :count 85 :health-status "healthy"}}

   {:id "R03" :phase :phase-2
    :note "grazing rotation scheduling"
    :request {:op :schedule-farm-operation :facility-id "farm-002"
              :operation-type "grazing-rotation" :requested-date "2026-09-01"
              :reason "pasture-recovery"}}

   {:id "R04" :phase :phase-3
    :note "notifiable disease suspicion -- escalates even at full autonomy"
    :request {:op :flag-animal-health-concern :facility-id "farm-002"
              :concern "scrapie"}}

   {:id "R05" :phase :phase-2
    :note "shearing equipment order under its category threshold"
    :request {:op :order-supplies :facility-id "farm-003"
              :category "shearing-equipment" :cost 640}}

   {:id "R06" :phase :phase-2
    :note "feed order above its category threshold"
    :request {:op :order-supplies :facility-id "farm-003"
              :category "feed" :cost 1200}}

   {:id "R07" :phase :phase-2
    :note "advisor reports low confidence on a breeding schedule"
    :request {:op :schedule-farm-operation :facility-id "farm-004"
              :operation-type "breeding" :requested-date "2026-10-15"
              :reason "seasonal-breeding-window"
              :demo/advisor-override {:confidence 0.55}}}

   {:id "R08" :phase :phase-2
    :note "zero head count submitted -- not a real observation"
    :request {:op :log-husbandry-record :facility-id "farm-004"
              :count 0 :health-status "unspecified"}}

   {:id "R09" :phase :phase-2
    :note "proposal targets a facility the Store cannot verify"
    :request {:op :log-husbandry-record :facility-id "farm-999"
              :count 40 :health-status "healthy"}}

   {:id "R10" :phase :phase-3
    :note "direct treatment administration -- veterinary exclusive"
    :request {:op :administer-treatment :facility-id "farm-001"}}

   {:id "R11" :phase :phase-3
    :note "finalizing a culling decision -- farm operator exclusive"
    :request {:op :finalize-culling-decision :facility-id "farm-002"}}

   {:id "R12" :phase :phase-2
    :note "op outside the closed coordination allowlist"
    :request {:op :sell-flock :facility-id "farm-003"}}

   {:id "R13" :phase :phase-3
    :note "advisor claims a direct actuation instead of a proposal"
    :request {:op :log-husbandry-record :facility-id "farm-001"
              :count 90 :health-status "healthy"
              :demo/advisor-override {:effect :execute}}}

   {:id "R14" :phase :phase-9
    :note "misconfigured rollout phase -- conservative hold, no violation"
    :request {:op :log-husbandry-record :facility-id "farm-002"
              :count 60 :health-status "healthy"}}

   {:id "R15" :phase :phase-1
    :note "health concern under supervised rollout"
    :request {:op :flag-animal-health-concern :facility-id "farm-001"
              :concern "footrot"}}

   {:id "R16" :phase :phase-3
    :note "unregistered facility AND a permanently blocked op"
    :request {:op :administer-treatment :facility-id "farm-999"}}])

;; --------------------------------------------------------------------
;; Driving the real stack
;; --------------------------------------------------------------------

(defn- facility-ids
  "Every facility id this run touches, seeded or not, in a stable order."
  []
  (concat (map first facility-seed) [unregistered-facility-id]))

(defn- store-snapshot
  "What the Store answers for every facility id this run touches."
  [st]
  (into [] (map (fn [id] [id (store/registered-facility st id)])) (facility-ids)))

(defn run-demo!
  "Runs the whole scenario against a freshly seeded MemStore through
  `flockops.operation/build`. Returns
  {:store .. :runs [..] :before .. :after ..} where each run carries the
  actor's real `:disposition`, `:verdict`, `:audit` and `:record`."
  []
  (let [st (store/mem-store {:initial-facilities (into {} facility-seed)})
        actor (operation/build st {:advisor (scenario-advisor)})
        before (store-snapshot st)
        runs (mapv (fn [{:keys [phase request] :as spec}]
                     (let [context (assoc operator-context-base :phase phase)]
                       (assoc spec
                              :context context
                              :result (actor request context))))
                   scenario)]
    {:store st :runs runs :before before :after (store-snapshot st)}))

;; --------------------------------------------------------------------
;; Derivations over the real run output
;; --------------------------------------------------------------------

(defn- disposition-fact [run] (last (get-in run [:result :audit])))
(defn- advisor-fact [run] (first (get-in run [:result :audit])))
(defn- verdict [run] (get-in run [:result :verdict]))
(defn- violations [run] (:violations (verdict run)))

(defn- base-disposition
  "The disposition the Governor's verdict implies BEFORE the rollout
  phase gate -- recomputed from this run's real verdict with the very
  same pure function `flockops.operation` calls."
  [run]
  (phase/verdict->disposition (verdict run)))

(defn- phase-changed? [run]
  (not= (base-disposition run) (get-in run [:result :disposition])))

(defn- hard-hold? [run] (boolean (seq (violations run))))

(defn- phase-only-hold?
  "A `:hold` the Governor did NOT ask for -- the rollout phase gate's
  conservative default. Carries an empty `:violations`, which is exactly
  why the build invariant below cannot just count holds."
  [run]
  (and (= :hold (get-in run [:result :disposition]))
       (not (hard-hold? run))))

(defn- escalation-driver
  "Why this run needs a human, derived from the run's own verdict plus
  the Governor's own vars -- not from the audit fact's `:reason`, which
  labels a cost-driven escalation `:always-escalate` (see the
  discrepancy column on the page)."
  [run]
  (let [v (verdict run)
        op (get-in run [:request :op])]
    (cond
      (not= :escalate (base-disposition run)) :rollout-phase-gate
      (contains? governor/always-escalate-ops op) :always-escalate-op
      (:high-stakes? v) :cost-above-threshold
      :else :low-confidence)))

(def ^:private approver-key-candidates
  "Keys a store/audit record could plausibly use to name the human who
  signed off. Probed at render time so the disclosure self-corrects if
  the repo later grows an approval path."
  [:approver :approved-by :approval-by :by :signed-off-by :reviewer
   :human :operator :decided-by])

(defn- attribution-probe
  "MEASURES what this repo's Store and audit facts actually retain about
  WHO acted, instead of assuming a known scaffold defect. Walks every
  fact and record this run produced, plus the Store protocol's own
  method set and a before/after Store snapshot."
  [{:keys [runs before after]}]
  (let [facts (mapcat #(get-in % [:result :audit]) runs)
        by-type (reduce (fn [m f] (update m (:t f) (fnil into #{}) (keys f)))
                        {} facts)
        record-keys (into #{} (mapcat #(keys (get-in % [:result :record]))) runs)
        present (fn [ks] (filterv (set ks) approver-key-candidates))]
    {:fact-types (into []
                       (map (fn [[t ks]]
                              {:t t
                               :n (count (filter #(= t (:t %)) facts))
                               :keys (vec (sort-by name ks))
                               :actor? (contains? ks :actor)
                               :approver-keys (present ks)}))
                       (sort-by (comp name key) by-type))
     :record-keys (vec (sort-by name record-keys))
     :record-approver-keys (present record-keys)
     :store-reads (vec (sort (keys (:sigs store/Store))))
     :store-mutated? (not= before after)
     :records-returned (count (keep #(get-in % [:result :record]) runs))}))

;; --------------------------------------------------------------------
;; HTML
;; --------------------------------------------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))
(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- span [cls v] (str "<span class=\"" cls "\">" v "</span>"))
(defn- num [v] (str "<span class=\"num\">" (esc v) "</span>"))
(defn- muted [v] (span "muted" (esc v)))

(defn- tr [cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section
  "One `<section class=\"card\">`. When `rows` is empty an explicit
  'none in this run' row is emitted -- an empty table must not read the
  same as a table that was never populated."
  [{:keys [title lede headers rows]}]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str "        <tr><td colspan=\"" (count headers) "\">"
              (muted "この実行では該当なし — no rows produced by this run")
              "</td></tr>\n"))
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- disposition-badge [run]
  (let [d (get-in run [:result :disposition])]
    (case d
      :commit (span "ok" "commit")
      :escalate (span "warn" "escalate")
      :hold (if (hard-hold? run)
              (span "critical" "HARD hold")
              (span "critical" "hold (phase gate)"))
      (muted (kw d)))))

;; ---- §1 facilities --------------------------------------------------

(defn- last-run-for [runs fid]
  (last (filter #(= fid (get-in % [:request :facility-id])) runs)))

(defn- facility-rows [{:keys [store runs]}]
  (mapv (fn [fid]
          (let [rec (store/registered-facility store fid)
                breed (some-> (:breed rec) facts/breed-by-id)
                lr (last-run-for runs fid)]
            (tr [(code fid)
                 (if rec (esc (:name rec)) (span "critical" "未登録 / not registered"))
                 (if rec (esc (:paddock rec)) (muted "—"))
                 (cond
                   (nil? rec) (muted "—")
                   breed (str (esc (:name breed)) " " (muted (str "(" (kw (:species breed)) " · " (:id breed) ")")))
                   :else (span "critical" (str "unresolved breed id " (esc (:breed rec)))))
                 (if lr
                   ;; `span`, not `muted`: the content already contains
                   ;; markup from `code`, and `muted` escapes its argument
                   ;; -- routing it through `muted` printed a literal
                   ;; "<code>" on the page.
                   (str (disposition-badge lr) " "
                        (span "muted" (str "· " (code (kw (get-in lr [:request :op]))))))
                   (muted "no activity"))])))
        (facility-ids)))

;; ---- §2 run ledger --------------------------------------------------

(defn- run-rows [runs]
  (mapv (fn [{:keys [id phase request] :as run}]
          (let [f (disposition-fact run)
                a (advisor-fact run)]
            (tr [(code id)
                 (code (kw (:op request)))
                 (code (:facility-id request))
                 (code (kw phase))
                 (num (format "%.2f" (double (:confidence a 0.0))))
                 (disposition-badge run)
                 (code (kw (:t f)))
                 (muted (:note run))])))
        runs))

;; ---- §3 governor refusals -------------------------------------------

(defn- hard-hold-rows [runs]
  (into []
        (comp
         (filter hard-hold?)
         (mapcat (fn [{:keys [id request] :as run}]
                   (map (fn [{:keys [rule detail]}]
                          (tr [(code id)
                               (code (kw (:op request)))
                               (code (:facility-id request))
                               (span "critical" (esc (kw rule)))
                               (esc detail)]))
                        (violations run)))))
        runs))

;; ---- §4 rollout phase gate ------------------------------------------

(defn- phase-gate-rows [runs]
  (into []
        (comp
         (filter #(or (phase-only-hold? %)
                      (and (phase-changed? %) (not (hard-hold? %)))))
         (map (fn [{:keys [id phase request] :as run}]
                (let [f (disposition-fact run)]
                  (tr [(code id)
                       (code (kw phase))
                       (code (kw (:op request)))
                       (code (kw (base-disposition run)))
                       (disposition-badge run)
                       (code (kw (or (:phase-reason f) (:reason f) :none)))
                       (if (seq (violations run))
                         (span "critical" "yes")
                         (span "ok" "no — governor was clean"))])))))
        runs))

;; ---- §5 approval queue ----------------------------------------------

(defn- approval-rows [runs probe]
  (into []
        (comp
         (filter #(= :escalate (get-in % [:result :disposition])))
         (map (fn [{:keys [id phase request] :as run}]
                (let [f (disposition-fact run)
                      fact-reason (:reason f)
                      derived (escalation-driver run)
                      approver (some #(get f %) approver-key-candidates)]
                  (tr [(code id)
                       (code (kw (:op request)))
                       (code (:facility-id request))
                       (code (kw phase))
                       (code (kw fact-reason))
                       (code (kw derived))
                       (cond
                         approver (esc approver)
                         (empty? (:record-approver-keys probe))
                         (span "critical" "not recorded — no approver key on the fact")
                         :else (muted "—"))])))))
        runs))

;; ---- §6 committed records -------------------------------------------

(defn- commit-rows [runs]
  (into []
        (comp
         (filter #(= :commit (get-in % [:result :disposition])))
         (map (fn [{:keys [id request] :as run}]
                (let [r (get-in run [:result :record])
                      f (disposition-fact run)]
                  (tr [(code id)
                       (code (kw (:op request)))
                       (code (str/join " / " (:path r)))
                       (code (kw (:effect r)))
                       (esc (str/join ", " (sort (map kw (keys (:value r))))))
                       (code (or (:actor f) "—"))])))))
        runs))

;; ---- §7 attribution probe -------------------------------------------

(defn- attribution-rows [{:keys [fact-types record-keys record-approver-keys
                                 store-reads store-mutated? records-returned]}]
  (into
   (mapv (fn [{:keys [t n keys actor? approver-keys]}]
           (tr [(code (kw t))
                (num n)
                (esc (str/join ", " (map kw keys)))
                (if actor? (span "ok" "yes") (span "critical" "no"))
                (if (seq approver-keys)
                  (span "ok" (esc (str/join ", " (map kw approver-keys))))
                  (span "critical" "none"))]))
         fact-types)
   [(tr [(code ":record (returned by run-operation)")
         (num records-returned)
         (esc (str/join ", " (map kw record-keys)))
         (span "critical" "no")
         (if (seq record-approver-keys)
           (span "ok" (esc (str/join ", " (map kw record-approver-keys))))
           (span "critical" "none"))])
    (tr [(code "flockops.store/Store reads")
         (num (count store-reads))
         (esc (str/join ", " (map kw store-reads)))
         (muted "n/a")
         (span "critical" "no approval register to read")])
    (tr [(code "Store contents after run")
         (num (if store-mutated? 1 0))
         (if store-mutated?
           (span "warn" "changed during the run")
           (esc "identical to the seed — no committed record was persisted"))
         (muted "n/a")
         (muted "n/a")])]))

;; ---- §8 closed op contract ------------------------------------------

(defn- op-contract-rows []
  (let [all (vec (sort-by name (into governor/known-ops governor/blocked-ops)))]
    (mapv (fn [op]
            (tr [(code (kw op))
                 (cond
                   (contains? governor/blocked-ops op)
                   (span "critical" "HARD blocked — permanent, never escalates")
                   (contains? governor/always-escalate-ops op)
                   (span "warn" "ALWAYS human sign-off at every phase")
                   :else (span "ok" "may auto-commit when the Governor is clean"))
                 (if (contains? governor/blocked-ops op)
                   (esc "treatment-or-culling-blocked")
                   (muted "—"))]))
          all)))

;; ---- §9/§10/§11 reference data --------------------------------------

(defn- supply-rows []
  (mapv (fn [[id c]]
          (tr [(code id) (esc (:name c)) (num (:cost-threshold c))]))
        (sort-by key facts/supply-categories)))

(defn- concern-rows []
  (mapv (fn [[id c]]
          (tr [(code id) (esc (:name c))
               (if (:notifiable c)
                 (span "warn" "notifiable")
                 (muted "not notifiable"))]))
        (sort-by key facts/health-concerns)))

(defn- breed-rows []
  (mapv (fn [[id b]]
          (tr [(code id) (esc (:name b)) (code (kw (:species b)))]))
        (sort-by key facts/breeds)))

;; ---- document -------------------------------------------------------

(defn render
  "Renders the whole operator console from the output of `run-demo!`."
  [{:keys [runs] :as run-data}]
  (let [probe (attribution-probe run-data)
        hard (filterv hard-hold? runs)
        hard-rules (vec (sort (distinct (map (comp name :rule)
                                             (mapcat violations runs)))))
        n-commit (count (filter #(= :commit (get-in % [:result :disposition])) runs))
        n-escalate (count (filter #(= :escalate (get-in % [:result :disposition])) runs))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>cloud-itonami-isic-0144 · Sheep &amp; Goat Farm Operations — Operator Console</title>\n"
     "<style>" (skin/dds+skin) "</style>\n"
     "</head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Raising of sheep and goats (ISIC 0144) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">treatment &amp; culling permanently blocked</span> "
     "<span class=\"badge\">health concerns always human-reviewed</span></p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run at a glance</h2>\n"
     "    <p>Generated by <code>flockops.render-html</code> (<code>clojure -M:render-html</code>) by driving "
     "<code>flockops.operation</code> → <code>flockops.advisor</code> → <code>flockops.governor</code> → "
     "<code>flockops.phase</code> → <code>flockops.store</code>. Every id, count, cost, disposition and "
     "Japanese hold detail below is read back from that run.</p>\n"
     "    <ul>\n"
     "      <li><strong>" (num (count runs)) "</strong> operations run across <strong>"
     (num (count facility-seed)) "</strong> registered facilities plus one unregistered id "
     (code unregistered-facility-id) ".</li>\n"
     "      <li><strong>" (span "critical" (num (count hard))) "</strong> HARD Governor refusals, covering <strong>"
     (num (count hard-rules)) "</strong> distinct rules: "
     (str/join ", " (map #(code %) hard-rules)) ". None of these reached a human.</li>\n"
     "      <li><strong>" (span "ok" (num n-commit)) "</strong> autonomous commits and <strong>"
     (span "warn" (num n-escalate)) "</strong> escalations queued for a farm operator / veterinarian.</li>\n"
     "    </ul>\n"
     "  </section>\n"

     (section
      {:title "1 · Registered facilities"
       :lede (str "Read back through <code>flockops.store/registered-facility</code> — not printed from the seed "
                  "literal. Breed names are resolved through <code>flockops.facts/breed-by-id</code>.")
       :headers ["Facility" "Name" "Paddock / pen" "Breed" "Last operation"]
       :rows (facility-rows run-data)})

     (section
      {:title "2 · Operations run (scenario ledger)"
       :lede (str "Left of the disposition column is scenario INPUT (request + rollout phase); "
                  "confidence, disposition and audit-fact type are actor OUTPUT. "
                  "Confidence is taken from the <code>:advisor-proposal</code> fact the run actually emitted.")
       :headers ["Run" "Op" "Facility" "Phase" "Confidence" "Disposition" "Audit fact" "Scenario"]
       :rows (run-rows runs)})

     (section
      {:title "3 · Governor refusals (HARD holds — never reach a human)"
       :lede (str "One row per violation. The <em>detail</em> column is the literal <code>:detail</code> string "
                  "<code>flockops.governor</code> emitted during this run. A HARD hold cannot be overridden by "
                  "confidence, by cites, or by advancing the rollout phase.")
       :headers ["Run" "Op" "Facility" "Rule" "Governor detail (verbatim)"]
       :rows (hard-hold-rows runs)})

     (section
      {:title "4 · Rollout phase gate (a different thing from a refusal)"
       :lede (str "<code>flockops.phase</code> can hold or escalate a proposal the Governor found <em>clean</em>. "
                  "The last column is the reason this table is separate: a phase-gate hold carries an EMPTY "
                  "<code>:violations</code> vector, so counting <code>:governor-hold</code> facts alone would "
                  "mistake it for a refusal.")
       :headers ["Run" "Phase" "Op" "Governor said" "Phase gate said" "Phase reason" "Governor violation?"]
       :rows (phase-gate-rows runs)})

     (section
      {:title "5 · Human approval queue (escalations)"
       :lede (str "Escalated to a farm operator / veterinarian. <em>Fact reason</em> is what the actor wrote into "
                  "the <code>:approval-requested</code> fact; <em>derived driver</em> is recomputed here from the "
                  "run's own verdict and <code>flockops.governor/always-escalate-ops</code>. Where the two "
                  "disagree, the fact is the one that is wrong (see the footnote).")
       :headers ["Run" "Op" "Facility" "Phase" "Fact reason" "Derived driver" "Approver on record"]
       :rows (approval-rows runs probe)})

     (section
      {:title "6 · Committed records (autonomous path)"
       :lede (str "The record <code>flockops.operation/run-operation</code> returned for each auto-committed "
                  "proposal. <em>Value fields</em> lists the keys actually present on the record's "
                  "<code>:value</code>; <em>Actor</em> is the <code>:actor</code> the "
                  "<code>:committed</code> fact carries.")
       :headers ["Run" "Op" "Record path" "Effect" "Value fields" "Actor"]
       :rows (commit-rows runs)})

     (section
      {:title "7 · Who-acted attribution (measured, not assumed)"
       :lede (str "Probed at render time by walking every fact and record this run produced, the "
                  "<code>flockops.store/Store</code> protocol's own method set, and a before/after Store "
                  "snapshot. If this repo later grows an approval path, these rows change on their own — "
                  "nothing here is a hard-coded claim about the scaffold.")
       :headers ["Surface" "Count" "Keys present" "Names the acting actor?" "Names a human approver?"]
       :rows (attribution-rows probe)})

     "  <section class=\"card\">\n"
     "    <h3>What the probe above means</h3>\n"
     "    <p>Commits and holds do name the acting actor (<code>:actor "
     (esc (:actor-id operator-context-base))
     "</code>), so the audit trail is not anonymous. But <strong>no human approver is recorded anywhere</strong>, "
     "and the reason is structural rather than a dropped field: <code>flockops.store/Store</code> exposes "
     (num (count (:store-reads probe))) " read"
     (when (not= 1 (count (:store-reads probe))) "s")
     " (<code>" (esc (str/join ", " (map kw (:store-reads probe)))) "</code>) and no writer other than "
     "<code>add-facility</code>, there is no resume/approve entry point in the repo, and the "
     "<code>:approval-requested</code> fact has no approver slot to fill. "
     (if (:store-mutated? probe)
       "The Store DID change during this run."
       (str "The Store is byte-identical before and after the run, and the "
            (num (:records-returned probe))
            " committed record(s) exist only as return values."))
     " A reader should not have to guess whether nobody approved or whether the approval was lost: "
     "for this build, <em>no approval can be recorded at all</em>.</p>\n"
     "  </section>\n"

     (section
      {:title "8 · Closed op contract"
       :lede (str "Derived from <code>flockops.governor/known-ops</code>, <code>/blocked-ops</code> and "
                  "<code>/always-escalate-ops</code> — the same vars the Governor checks against, so this table "
                  "cannot drift from the enforced allowlist. Anything outside this union is "
                  "<code>op-not-allowed</code>.")
       :headers ["Op" "Gate" "Hard rule"]
       :rows (op-contract-rows)})

     (section
      {:title "9 · Supply-order escalation thresholds"
       :lede (str "From <code>flockops.facts/supply-categories</code>. An order at or below its threshold may "
                  "auto-commit; above it escalates. Unknown categories fall back to the conservative default "
                  "<code>" (num facts/default-cost-threshold) "</code>. Checked by "
                  "<code>flockops.registry/cost-exceeds-threshold?</code>, never taken from the advisor.")
       :headers ["Category id" "Name" "Escalation threshold"]
       :rows (supply-rows)})

     (section
      {:title "10 · Health / welfare concern vocabulary"
       :lede (str "From <code>flockops.facts/health-concerns</code>. Purely descriptive: notifiable status does "
                  "NOT change the disposition — <em>every</em> flagged concern escalates, at every phase. This "
                  "actor never declares an outbreak, orders a cull, or contacts animal-health authorities.")
       :headers ["Concern id" "Name" "Notifiable"]
       :rows (concern-rows)})

     (section
      {:title "11 · Breed reference"
       :lede "From <code>flockops.facts/breeds</code> — the ids the facility records above resolve against."
       :headers ["Breed id" "Name" "Species"]
       :rows (breed-rows)})

     "</main>\n"
     "<footer class=\"footer\">\n"
     "  <p>Regenerate with <code>clojure -M:render-html</code>. The page contains no timestamps and is "
     "byte-identical across reruns from the same seed. <code>-main</code> refuses to write unless the run "
     "produced at least one <code>:governor-hold</code> fact carrying a non-empty <code>:violations</code> "
     "vector, at least one commit and at least one escalation — a phase-gate hold with empty violations does "
     "not satisfy it.</p>\n"
     "  <p><strong>Known defect, surfaced not patched:</strong> "
     "<code>flockops.operation/run-operation</code> labels a cost-driven escalation "
     "<code>:always-escalate</code>, because it derives the reason from <code>:high-stakes?</code>, which is "
     "set by both an always-escalate op and a cost above threshold. Compare the <em>Fact reason</em> and "
     "<em>Derived driver</em> columns in section 5.</p>\n"
     "  <p>cloud-itonami-isic-0144 · AGPL-3.0-or-later · UI: "
     "<a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-digital-design-system</a> "
     "(デジタル庁デザインシステム).</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

;; --------------------------------------------------------------------
;; entrypoint
;; --------------------------------------------------------------------

(defn- assert-invariants!
  "Build-time invariant, two-stage on purpose. A phase-gating hold
  (`:unknown-phase`) produces a `:governor-hold` fact with an EMPTY
  `:violations`, so a naive hold count would pass on a run in which the
  Governor never actually refused anything."
  [runs]
  (let [facts (mapcat #(get-in % [:result :audit]) runs)
        holds (filterv #(= :governor-hold (:t %)) facts)
        substantive (filterv #(seq (:violations %)) holds)
        commits (filterv #(= :committed (:t %)) facts)
        escalations (filterv #(= :approval-requested (:t %)) facts)]
    (when (empty? holds)
      (throw (ex-info "render-html: scenario produced no :governor-hold facts at all"
                      {:runs (count runs)})))
    (when (empty? substantive)
      (throw (ex-info (str "render-html: every hold this run produced carried an empty :violations "
                           "vector — those are rollout phase-gate holds, not Governor refusals")
                      {:holds (count holds)
                       :phase-reasons (vec (distinct (keep :phase-reason holds)))})))
    (when (empty? commits)
      (throw (ex-info "render-html: scenario produced no committed (autonomous) path"
                      {:runs (count runs)})))
    (when (empty? escalations)
      (throw (ex-info "render-html: scenario produced no human-approval path"
                      {:runs (count runs)})))
    {:holds (count holds)
     :hard-holds (count substantive)
     :commits (count commits)
     :escalations (count escalations)
     :rules (vec (sort (distinct (map (comp name :rule)
                                      (mapcat :violations substantive)))))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [runs] :as run-data} (run-demo!)
        ;; Throws BEFORE anything is written: a run that failed the
        ;; invariant must leave no file behind.
        stats (assert-invariants! runs)
        html (render run-data)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out
             (str "(" (count runs) " operations, "
                  (:hard-holds stats) " HARD governor refusals over rules "
                  (str/join "/" (:rules stats)) ", "
                  (:commits stats) " commits, "
                  (:escalations stats) " escalations)"))))
