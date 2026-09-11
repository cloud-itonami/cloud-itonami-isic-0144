(ns flockops.facts
  "Reference facts for sheep-and-goat farm operations coordination: supply
  category cost policy, breed classification (sheep and goat), and
  disease/welfare reference vocabulary. This namespace contains pure
  lookup functions for domain reference data -- the Governor and Advisor
  consult these instead of inventing thresholds. Mirrors `swineops.facts`
  (cloud-itonami-isic-0145) in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (farm operator/veterinarian)."
  {"feed"
   {:id "feed" :name "飼料" :cost-threshold 500}

   "veterinary-supply"
   {:id "veterinary-supply" :name "獣医用品" :cost-threshold 500}

   "shearing-equipment"
   {:id "shearing-equipment" :name "剪毛用機材" :cost-threshold 800}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def breeds
  "Common commercial sheep and goat breeds this actor's facility/flock
  records may cover (ISIC 0144: raising of sheep and goats)."
  {"suffolk"  {:id "suffolk"  :name "サフォーク"   :species :sheep}
   "merino"   {:id "merino"   :name "メリノ"       :species :sheep}
   "saanen"   {:id "saanen"   :name "ザーネン"     :species :goat}
   "boer"     {:id "boer"     :name "ボア"         :species :goat}})

(defn breed-by-id [id]
  (get breeds id))

(def health-concerns
  "Reference vocabulary for common sheep/goat disease and welfare concerns
  this actor's `:flag-animal-health-concern` op may cite (e.g. suspected
  scrapie or bluetongue). Purely descriptive -- citing a concern (or
  leaving it free text) NEVER changes the Governor's disposition: EVERY
  flagged concern always escalates for veterinary/farm-operator review
  (`flockops.governor/always-escalate-ops`), regardless of the concern's
  `:notifiable` status or apparent severity. This actor has no authority
  to declare an outbreak, order a cull, or contact animal-health
  authorities -- it only surfaces the observation for human/veterinary
  judgment."
  {"scrapie"    {:id "scrapie"    :name "スクレイピー (Scrapie)" :notifiable true}
   "bluetongue" {:id "bluetongue" :name "ブルータング (Bluetongue)" :notifiable true}
   "fmd"        {:id "fmd"        :name "口蹄疫 (Foot-and-Mouth Disease)" :notifiable true}
   "footrot"    {:id "footrot"    :name "蹄病 (Foot Rot)" :notifiable false}})

(defn health-concern-by-id [id]
  (get health-concerns id))
