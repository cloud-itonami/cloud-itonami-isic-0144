# Business Model: Sheep-and-Goat Farm Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0144`
- ISIC Rev. 4: `0144`
- Industry: Raising of sheep and goats
- Social impact: animal-welfare, food-security, rural-employment

## Customer

- Small-to-medium sheep and goat farms (wool, meat, and dairy operations)
- Breeding-stock operations
- Contract/independent shepherds and goat herders
- Cooperative and integrator-affiliated flock operations

## Offer

- Flock husbandry management and record-keeping, including breeding
  (lambing/kidding) and shearing (fleece) data
- Grazing-rotation, shearing, and breeding operation scheduling
- Health and welfare tracking (e.g. scrapie/bluetongue risk surfacing)
- Supply procurement coordination
- Audit trail and transparency

## Revenue

- SaaS subscription (per-head-per-month pricing)
- Supply chain integration fees
- API access for veterinary partners
- Data analytics and reporting add-ons

## Trust Controls

- No culling decisions without human sign-off
- No direct treatment administration
- All veterinary recommendations are proposals, not commands
- Facility (paddock/pen) registration is required before any operation
- All animal health/welfare concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we do NOT do

- **Veterinary treatment decisions** — the veterinarian decides treatment
- **Animal welfare decisions** — the farm operator decides welfare actions
- **Economic decisions** (culling, breeding) — remain human authority
- **Direct animal handling** — the robot manages records and logistics only
- **Outbreak declarations / animal-health authority contact** — flagged
  concerns (e.g. suspected scrapie) are surfaced for human/veterinary
  judgment only

## Supported Operations

### Flock Husbandry Record Logging
- Daily flock counts
- Weight tracking
- Health status notes
- Offspring data (lambing/kidding counts — logging only, not decision-making)
- Fleece (shearing) weight tracking

### Farm Operation Coordination
- Schedule grazing-rotation moves
- Schedule shearing sessions
- Schedule breeding operations
- Propose follow-up care (not order it directly)

### Health/Welfare Concern Escalation
- Flag suspected disease (e.g. Scrapie, Bluetongue, Foot-and-Mouth Disease,
  Foot Rot)
- Report injuries or welfare concerns
- Automatic escalation to farm operator/veterinarian

### Supply Procurement
- Feed orders
- Veterinary supply orders
- Shearing-equipment procurement
- Cost threshold escalation for large orders
