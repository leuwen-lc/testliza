---
liza_version: "dev-unknown"
---

# User Stories: Adjust Price by Percentage

Status: draft

---

# Part 1 — Story Review

*For the intent owner. Verify the user-visible behavior before coding starts.*

## Promise

**Before this story:** Un organisateur ayant déjà fixé un prix absolu pour une paire (événement,
palier) (`set-price.md`, ST-001) n'a aucun moyen de le changer relativement — chaque nouvelle
fixation de prix nécessite de recalculer et de ressaisir à la main un tout nouveau montant absolu.

**After this story:** Un organisateur peut ajuster le prix courant d'une paire (événement, palier)
d'un pourcentage entier (à la hausse ou à la baisse) et voir le nouveau montant, arrondi half-up à
l'unité mineure entière la plus proche, devenir le nouveau prix courant. Comme pour un « set »
direct, chaque ajustement alloue une nouvelle version, et si deux changements entrent en concurrence
pour la même paire — deux ajustements, ou un ajustement entrant en concurrence avec un « set »
direct — l'un gagne visiblement et l'autre est clairement informé qu'il a perdu, jamais
silencieusement.

## Behavior Map

| Story | User-visible change | Source intent captured | Main exclusion |
|-------|---------------------|-------------------------|----------------|
| ST-002 — Adjust price by percentage | L'organisateur fait varier le prix courant d'une paire (événement, palier) d'un pourcentage entier et voit le résultat arrondi devenir le prix courant ; un changement concurrent perdant (un autre ajustement, ou un « set » direct concurrent) est visiblement rejeté | vision-general-objective-fr.md:45-46, 160-161 | La lecture du prix courant ou de son historique de versions (CAP-004, épopée sœur) ; la fixation d'un prix absolu initial (ST-001, `set-price.md`) |

## Example Walkthrough

Un organisateur représentatif procéderait ainsi :
1. A déjà un prix courant de 30,00 USD pour (événement X, palier « Standard ») — fixé précédemment
   via `set-price.md`.
2. Décide de l'augmenter de 10 % avant la prochaine phase de vente et saisit « +10 ».
3. Voit le système calculer 30,00 × 1,10 = 33,00 USD, allouer une nouvelle version, et l'afficher
   comme le nouveau prix courant.
4. Plus tard, décide de baisser le prix de 50 % sur une paire dont le prix courant est un montant
   impair (par exemple 10,05 USD = 1005 unités mineures) ; voit le système calculer 1005 × 0,50 =
   502,5 unités mineures, l'arrondir half-up à 503 (5,03 USD), et l'afficher comme le nouveau prix
   courant.
5. Si, au même moment, un second organisateur soumet soit un autre ajustement en pourcentage soit un
   tout nouveau prix absolu pour exactement la même paire, exactement un des deux changements est
   accepté ; le perdant voit clairement le résultat « quelqu'un d'autre vient de changer ce prix —
   recharger et réessayer ».

## Interpretation Decisions

| Source signal | Story interpretation | Confidence | Verify? |
|---|---|---|---|
| « Un ajustement en pourcentage » (vision-general-objective-fr.md:160-161), sans restriction de direction énoncée | Un ajustement en pourcentage peut être positif (hausse) ou négatif (baisse) ; « ajuster » n'est pas lu comme hausse uniquement. Un ajustement de 0 % est une requête explicite valide, bien qu'inhabituelle, qui alloue tout de même une nouvelle version | MEDIUM | Yes |
| Aucun plancher énoncé pour le montant résultant après un ajustement en pourcentage (epic OQ-000-1) | Le montant résultant après arrondi doit être un nombre strictement positif d'unités mineures ; un ajustement qui l'amènerait à zéro ou en dessous est rejeté, reflétant le même plancher que celui utilisé pour un « set » direct (ASM-001-1 dans `set-price.md`), plutôt que d'être plafonné à un minimum | MEDIUM | Yes |

## Review Questions

- [ ] Un ajustement en pourcentage doit-il vraiment pouvoir être négatif (une baisse de prix), ou
      « ajuster » était-il conçu comme une hausse uniquement, comme le laisse ouvert la propre
      Review Question 3 de l'epic ?
- [ ] Un ajustement qui amènerait le prix à zéro ou en dessous doit-il vraiment être rejeté d'emblée
      (ASM-002-1), ou existe-t-il un autre plancher métier (par exemple un prix minimum fixe) que le
      porteur d'intention a en tête ?
- [ ] Un ajustement de 0 % doit-il réellement créer une nouvelle version avec un montant inchangé, ou
      doit-il être traité comme un no-op qui ne crée rien ?
- [ ] Un simple « conflit, veuillez réessayer » visible suffit-il pour un ajustement concurrent
      perdant, ou la réponse doit-elle porter le montant gagnant pour que l'organisateur décide de sa
      prochaine action sans lecture supplémentaire ?

---

# Part 2 — Coder Contract

*For the Coder. Implementation-ready acceptance criteria and context.*

## Goal
Un organisateur peut faire varier le prix courant d'une paire (événement, palier) d'un pourcentage
entier, arrondi half-up à l'unité mineure la plus proche, chaque ajustement allouant une nouvelle
version et aucun changement concurrent — qu'il s'agisse d'un autre ajustement ou d'un « set » direct
— n'étant jamais silencieusement perdu.

## Parent Epic
`specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability CAP-003 (Price
Setting and Adjustment)

## Context
Ceci est la seconde des deux opérations d'écriture sur l'agrégat de prix du module `pricing` pour
une clé (événement, palier) donnée (la première est le « set » absolu direct dans `set-price.md`,
ST-001). Un ajustement lit le prix courant et écrit le nouveau en une seule opération interne gardée
sur le même agrégat et la même chaîne de versions que celle sur laquelle écrit « set » — il ne passe
jamais par l'interface de lecture publique que la capacité sœur CAP-004 expose à ses propres
consommateurs. Parce que les deux opérations partagent une même chaîne de versions par paire
(événement, palier), la garantie de concurrence ci-dessous doit tenir que la course oppose deux
ajustements ou un ajustement et un « set » direct. Cette capacité ne vérifie pas que la clé
(événement, palier) correspond à un événement réel et existant
(vision-general-objective-fr.md:163-164).

## Personas
- **Organisateur (admin)** : opère depuis une console d'administration (livrable séparé, non décrit
  ici) ; ajuste les prix en pourcentage surtout pour réagir vite à la demande (par exemple
  « augmenter tout de 10 % pour la dernière phase ») sans recalculer les montants absolus à la main ;
  attend un refus visible en cas de collision de changement, jamais un écrasement silencieux.

## General Information

Applies to: l'intégralité du périmètre (ST-002).

### References
- spec: vision-general-objective-fr.md:45-46 (MVP item 2 — tarification), :90-91 (Montant), :92-93
  (Prix — versionné, historique complet), :155-164 (Tarification — versionnement, arrondi half-up,
  pas de vérification d'existence de l'événement), :177-191 (Contrôle d'accès — l'organisateur seul
  écrit la tarification), :511-512 (montants en unités mineures entières)
- epic: `specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability
  CAP-003, « Completion Criteria », NFR-000-1, NFR-000-2, NFR-000-3, NFR-000-4, ASM-000-1,
  ASM-000-2, OQ-000-1, OQ-000-2
- guardrails: GUARDRAILS.md G1.1 (contrôle de rôle sur l'écriture), G1.2 (la version et le prix
  résultant sont toujours dérivés côté serveur, jamais acceptés depuis l'appelant), G2.1 (forme en
  couches), G2.7 (discipline de la pile)

### Non-Functional Requirements
- NFR-000-1: Les montants sont des unités mineures entières de bout en bout ; aucun flottant nulle
  part dans le calcul ou le stockage d'un prix.
- NFR-000-2: Une version de prix est allouée au plus une fois par paire (événement, palier) et par
  changement logique ; une écriture concurrente perdante échoue visiblement avec un résultat typé de
  conflit — jamais silencieusement écrasée ou fusionnée.
- NFR-000-3: L'arrondi d'un ajustement en pourcentage est half-up à l'unité mineure entière la plus
  proche.
- NFR-000-4: L'écriture de cette capacité déclare exactement un niveau d'accès (organisateur) ; un
  niveau d'accès non déclaré est une erreur de compilation, jamais une route publique silencieuse.

### Out of Scope
- Lire le prix courant faisant autorité ou son historique de versions (CAP-004, épopée sœur).
- Vérifier que la clé (événement, palier) correspond à un événement qui existe réellement
  (vision-general-objective-fr.md:163-164).
- Fixer un prix absolu initial (ST-001, `set-price.md`) — cette story ne fait que faire varier un
  montant déjà existant.
- Tarification par siège, remises, codes promo (explicitement hors périmètre de la vision).
- La consommation du prix résultant par le parcours d'achat ou par la projection tarifaire publique.
- Toute IHM, écran ou navigation de la console d'administration.

### Assumptions
- **ASM-000-1** : Il n'existe pas de prix implicite par défaut pour une paire (événement, palier)
  tant qu'un « set » explicite n'a pas eu lieu. — *Why* : la vision ne décrit que « set » (absolu) et
  « adjust » (relatif au prix courant), jamais de valeur de départ implicite. Confidence: HIGH.
- **ASM-000-2** : Un prix porte sa propre devise ; un ajustement en pourcentage hérite implicitement
  de la devise du prix courant qu'il fait varier, sans nouvelle saisie de devise par l'appelant et
  sans vérification croisée avec la devise configurée de l'événement. — *Why* : « Montant » est
  défini comme une somme dans une devise (vision-general-objective-fr.md:90-91), et cette capacité
  n'opère que sur la clé (événement, palier) (vision-general-objective-fr.md:163-164). Confidence:
  MEDIUM.

### Open Questions
- **OQ-000-2** : Qui garantit — si quiconque — que la devise portée par un prix correspond à la
  devise configurée de l'événement, étant donné que cette capacité ne vérifie jamais que l'événement
  existe ? — *Impact if unresolved* : une incohérence de devise entre un prix et son événement
  pourrait passer inaperçue jusqu'à l'achat.

---

## Story ST-002 - Adjust price by percentage

### References
- spec: vision-general-objective-fr.md:160-161 (ajustement en pourcentage, arrondi half-up),
  :155-159 (versionnement, collision de changements simultanés)
- epic: Capability CAP-003, Story Documents table (row "adjust-price-percentage.md")

### User Story
**En tant qu'** organisateur (admin) opérant depuis la console d'administration, **je veux**
ajuster le prix courant d'une paire (événement, palier) d'un pourcentage entier, **afin de** réagir
vite à la demande en faisant varier un prix existant à la hausse ou à la baisse sans recalculer et
ressaisir à la main un nouveau montant absolu, tout en étant clairement informé si quelqu'un d'autre
a changé le même prix au même moment.

### Acceptance Criteria
- AC-002-1: Étant donné un prix courant faisant autorité de montant A pour une paire (événement,
  palier), quand l'organisateur soumet un ajustement en pourcentage entier positif de P %, alors le
  système calcule A × (1 + P/100), arrondit le résultat half-up à l'unité mineure entière la plus
  proche, alloue une nouvelle version avec ce montant arrondi dans la même devise que le prix
  courant, et cette version devient le nouveau prix courant faisant autorité. Exemple : prix courant
  333 unités mineures, P = +50 → brut 499,5 → arrondi 500.
- AC-002-1b: Étant donné le même contexte, quand l'organisateur soumet un pourcentage entier négatif
  (une baisse), alors le système calcule le montant mis à l'échelle de la même façon et l'arrondit
  half-up. Exemple : prix courant 1005 unités mineures, P = -50 → brut 502,5 → arrondi 503. (voir
  ASM-002-2)
- AC-002-2: Étant donné une valeur de pourcentage non entière (par exemple 12,5), quand
  l'organisateur la soumet, alors la requête est rejetée avec une erreur de validation nommant le
  champ pourcentage, et aucune nouvelle version n'est créée.
- AC-002-3: Étant donné une paire (événement, palier) pour laquelle aucune version de prix n'existe
  encore, quand l'organisateur tente un ajustement en pourcentage, alors la requête est rejetée avec
  un résultat typé « aucun prix courant à ajuster », et aucune version n'est créée. (voir Depends on)
- AC-002-4: Étant donné un ajustement en pourcentage qui amènerait le montant résultant à zéro ou à
  un nombre négatif, quand l'organisateur le soumet, alors la requête est rejetée avec un résultat
  typé de violation de règle, le prix courant faisant autorité reste inchangé, et aucune nouvelle
  version n'est créée. (voir ASM-002-1)
- AC-002-4b: Étant donné un ajustement en pourcentage de exactement 0 %, quand l'organisateur le
  soumet, alors une nouvelle version est créée avec le même montant que le prix courant, et cette
  version devient le nouveau prix courant faisant autorité. (voir ASM-002-2)
- AC-002-5: Étant donné deux organisateurs (ou deux requêtes concurrentes) soumettant un ajustement
  en pourcentage pour exactement la même paire (événement, palier) au même moment, quand les deux
  soumettent, alors exactement l'un réussit et sa version résultante devient le nouveau prix courant
  faisant autorité ; l'autre reçoit un résultat de conflit visible et typé — jamais silencieusement
  écrasé, fusionné, ou réessayé automatiquement.
- AC-002-5b: Étant donné qu'un organisateur soumet un prix absolu via `set-price.md` (ST-001) pour
  une paire (événement, palier) au même moment qu'un autre organisateur soumet un ajustement en
  pourcentage pour cette même paire, quand les deux soumettent de façon concurrente, alors exactement
  l'une des deux opérations réussit et devient le nouveau prix courant faisant autorité ; l'autre
  reçoit un résultat de conflit visible et typé.
- AC-002-6: Étant donné un principal ne détenant pas le rôle organisateur, quand il tente un
  ajustement en pourcentage, alors la requête est refusée avec un résultat typé de refus de
  permission et aucun changement de prix ne se produit.

### Depends on:
Ordre d'implémentation :
- Story ST-001 - Set absolute price (`set-price.md`) : un ajustement en pourcentage fait varier un
  prix courant existant, cette story requiert donc que ST-001 ait déjà produit au moins une version
  pour la paire (événement, palier) ajustée. Ajuster une paire sans « set » préalable est le cas
  d'échec de l'AC-002-3, pas le chemin nominal.

### Out of Scope
- Fixer un prix absolu initial (ST-001 dans `set-price.md`).
- Lire le prix courant résultant ou tout historique de versions renvoyé à l'appelant au-delà de la
  confirmation que la nouvelle version a été créée (CAP-004, épopée sœur).
- Vérifier la clé (événement, palier) par rapport à un événement réellement existant.

### Assumptions
- **ASM-002-1** : Le montant résultant après un ajustement en pourcentage (post-arrondi) doit être
  un nombre strictement positif d'unités mineures ; un ajustement qui l'amènerait à zéro ou en
  négatif est rejeté, pas accepté, plafonné, ou corrigé silencieusement. — *Why* : l'epic signale
  explicitement cet écart (OQ-000-1) spécifiquement pour les ajustements en pourcentage, puisqu'un
  pourcentage extrême ou négatif peut mathématiquement atteindre zéro ou en dessous ; un prix
  représente une somme monétaire facturable, et un résultat nul ou négatif n'en est pas une. Ceci
  reflète le même plancher que celui utilisé pour un « set » direct (ASM-001-1 dans
  `set-price.md`), afin que les deux chemins d'écriture traitent le plancher de façon cohérente.
  Confidence: MEDIUM — signalé pour confirmation humaine avant que cette story ne soit codée.
- **ASM-002-2** : Un ajustement en pourcentage peut être positif (hausse) ou négatif (baisse), et un
  ajustement de exactement 0 % est une requête explicite valide qui alloue tout de même une nouvelle
  version avec le montant inchangé, plutôt que d'être rejeté ou silencieusement ignoré. — *Why* : la
  vision énonce « un ajustement en pourcentage » sans restriction de direction, et la propre Review
  Question de l'epic (« peut-il être négatif, ou 'ajuster' signifie-t-il hausse uniquement ? »)
  reste ouverte plutôt que tranchée ; traiter 0 % comme une action explicite réelle (pas un no-op à
  filtrer) garde l'invariant de version (« chaque changement produit une nouvelle version ») cohérent
  plutôt que de créer une exception silencieuse pour une seule valeur d'entrée. Confidence: MEDIUM —
  signalé pour confirmation humaine avant que cette story ne soit codée.

### Open Questions
Aucune au-delà de l'OQ-000-2 au niveau du document, ci-dessus.
