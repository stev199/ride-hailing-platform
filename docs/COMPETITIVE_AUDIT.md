# COMPETITIVE AUDIT

Desktop research audit of reference products used to drive the Feature Discovery Mandate (Part B). Method: public knowledge of product UX and feature sets (no paid tools, no scraping); date 2026-11-23; treated as directional, not exhaustive. Findings feed FEATURES.md priorities.

## Lyft

- Strengths: polished booking funnel, transparent price breakdown before confirm, driver flow optimized for short turns (accept -> navigate -> arrive -> start), strong safety suite (emergency button, ride check mid-trip alerts, trusted contacts).
- Rider screens: home map w/ bottom sheet, ride-type carousel with ETA+price, confirmation with payment picker, live trip w/ driver card + actions (share, contact, safety), destination set screen, post-trip rating+tip+receipt.
- Driver screens: duty toggle, request card w/ ETA/earnings estimate, guided navigation, earnings dashboard w/ weekly summaries, document status center.
- Adopted: permanent estimate disclaimer, per-ride-type price rows, mid-trip ride-check style safety check-ins (R-12), earnings breakdown depth (D-06), receipt quality (R-14).

## Uber

- Strengths: multi-product surface (rides, courier, hourly, intercity), scheduled rides w/ dispatch window, wallet + pay-splitting, robust admin-side fraud tooling (device fingerprints, GPS-spoof heuristics), ride types per city config.
- Rider: saved places hierarchy (home/work), multi-stop builder, fare lock/upfront pricing concept, live trip sharing via link, safety toolkit drawer.
- Driver: quest/incentive boards, surge + heatmap, acceptance-rate metrics, earnings tiers.
- Adopted: upfront/metered-with-cap quote policy (P-03), scheduled + concurrent trips (R-18), quests/incentives/tiers (D-11/A-19), fraud-signal platform (B-13), ride-type configurability per city (A-09/A-10).
- Skipped/deferred: Uber's closed map/geo stack (we are FLOSS-only per constraints).

## Bolt

- Strengths: lightweight app, strong wallet/points culture, city-level pricing config agility, fast onboarding.
- Adopted: wallet-first payment UX incl. points as first-class (R-16/B-08), aggressive city/ride-type fare configurability (A-09), quick rebook (R-15).

## inDrive

- Strengths: offer-your-fare marketplace model (rider proposes, drivers counter), explicit rider-driver negotiation UI.
- Adopted: optional "propose your fare" mode behind admin toggle (S-08) — non-default, recorded as P2; the default stays metered/quoted pricing.

## Careem

- Strengths: super-app wallet, strong RTL/Arabic UX maturity, loyalty (Careem Rewards), corporate accounts.
- Adopted: full-RTL-first i18n (S-05), loyalty tiers + corporate accounts + vouchers (A-18/A-19), generous cancellation transparency.

## Yassir

- Strengths: growth in low-connectivity markets, cash-heavy flows, light-data UX.
- Adopted: data-saver/low-end mode (R-25), cash payment path parity with wallet (A-09), low-bandwidth map behavior.

## Google Maps

- Strengths: search quality (typed/partial/POI), smooth marker + camera motion, offline city packs with clear management UI, timeline/visited places.
- Adopted: visited-places model w/ first/last/count metadata (M-03), offline region manager UX patterns (M-05), layer toggles (M-08), camera/marker animation bar for 60fps (M-02).

## Waze

- Strengths: driver-centric navigation, crowd signals, aggressive battery/GPS duty-cycling.
- Adopted: TTS instruction strategy + reroute-on-deviation (D-04, R-12), beacon/GPS duty-cycling budgets for the driver foreground service (X-07).

## Consolidated adoption table

| Theme | Source | Lands as |
|---|---|---|
| Estimate disclaimer + breakdown | Lyft | P-02 |
| Upfront vs metered-with-cap quotes | Uber | P-03/B-07 |
| Points-first wallet | Bolt/Careem | R-16/B-08 |
| Quests/incentives/tiers | Uber | D-11/A-19 |
| Fraud signals + device binding | Uber | B-13 |
| Visited places + offline regions | Google Maps | M-03/M-05 |
| RTL-first i18n | Careem | S-05 |
| Data-saver mode | Yassir | R-25 |
| Propose-fare mode (opt-in) | inDrive | S-08 |
| Safety check-ins + trusted contacts | Lyft | R-12/S-07 |
| TTS + deviation rerouting | Waze | D-04/R-12 |

## Lifecycle completeness checklist (from Feature Discovery Mandate)

- Rider lifecycle: install -> permissions onboarding -> signup (multi-method) -> first booking -> payment methods -> trip problem flows (cancel/dispute/lost item/accident) -> retention (referrals/promos/loyalty) -> privacy controls -> account deletion. Covered by R-01..R-17, S-04, A-16/A-17.
- Driver lifecycle: install -> document onboarding -> verification wait -> first duty -> earnings -> vehicle/document renewals -> performance (ratings/quests) -> disputes/appeals -> deactivation. Covered by D-01..D-13, A-05/A-06.
- Admin lifecycle: role assignment -> daily queues (verification, support, incidents) -> money ops (fares/payouts/reconciliation) -> safety/bans -> analytics -> config changes -> audit. Covered by A-01..A-17.
- Edge states budget per screen: loading skeleton, empty, error, offline, permission-denied, stale-data — enforced by UI test variants (E2/E9).
