# User Stories: Set Absolute Price

Status: draft

---

# Part 1 — Story Review

*For the intent owner. Verify the user-visible behavior before coding starts.*

## Promise

**Before this story:** Pour une paire (événement, palier) donnée, il n'existe aucun moyen pour un
organisateur d'établir un prix. Rien en aval n'a quoi que ce soit d'autoritaire à lire ou à
facturer.

**After this story:** Un organisateur peut fixer un prix absolu (montant + devise) pour n'importe
quelle paire (événement, palier). À chaque fois, une nouvelle version de prix est créée et devient
immédiatement le prix faisant autorité pour cette paire. Si deux organisateurs (ou deux soumissions
concurrentes) tentent de fixer le prix de la même paire au même instant, l'un des deux réussit et
l'autre se voit clairement notifier que son changement a été rejeté parce que quelqu'un d'autre l'a
changé en premier — aucun des deux n'est jamais silencieusement écarté, fusionné, ou écrasé sans
notification visible.

## Behavior Map

| Story | User-visible change | Source intent captured | Main exclusion |
|-------|---------------------|-------------------------|----------------|
| ST-001 — Set absolute price | L'organisateur fixe un prix pour une paire (événement, palier) et le voit devenir le prix courant ; une tentative concurrente perdante est visiblement rejetée plutôt que silencieusement perdue | vision-general-objective-fr.md:45-46, 92-93, 155-164 | La lecture du prix courant ou de son historique de versions (CAP-004, épopée sœur) ; l'opération d'ajustement en pourcentage (ST-002, `adjust-price-percentage.md`) |

## Example Walkthrough

Un organisateur représentatif procéderait ainsi :
1. Ouvre la console d'administration (livrable séparé) et choisit un événement et un palier (par
   exemple « Standard »).
2. Saisit un montant et une devise (par exemple 25,00 USD) et confirme.
3. Voit le prix accepté comme version 1 — le prix courant pour cette paire.
4. Plus tard, décide de refixer le prix et saisit un nouveau montant (par exemple 30,00 USD) pour la
   même paire.
5. Voit la version 2 créée et devenir le nouveau prix courant ; la version 1 n'est plus affichée
   comme courante (son historique est lu ailleurs — hors périmètre ici).
6. Si, exactement au même moment, un autre organisateur (ou un second onglet de navigateur) soumet
   un prix différent pour la même paire, exactement une des deux soumissions est acceptée ; l'autre
   voit clairement le résultat « quelqu'un d'autre vient de changer ce prix — recharger et
   réessayer », jamais une perte silencieuse de son propre changement tenté ni de celui de l'autre
   organisateur.

## Interpretation Decisions

| Source signal | Story interpretation | Confidence | Verify? |
|---|---|---|---|
| « Montant... une somme dans une seule devise... toujours exacte » (vision-general-objective-fr.md:90-91) ; aucun plancher explicite énoncé pour un prix absolu fixé directement | Un montant de prix fixé doit être un nombre strictement positif d'unités mineures ; fixer un montant nul ou négatif est rejeté en tant que violation de règle plutôt qu'accepté | MEDIUM | Yes |

## Review Questions

- [ ] Fixer un prix absolu à 0 ou à un montant négatif doit-il vraiment être rejeté, comme supposé
      (ASM-001-1), ou existe-t-il un cas d'usage légitime (par exemple un palier gratuit) qui
      nécessiterait un traitement différent ?
- [ ] La devise doit-elle vraiment être libre à chaque appel « set », sans vérification croisée avec
      la devise propre de l'événement, comme le suppose l'epic (ASM-000-2), ou le porteur
      d'intention attend-il une vérification même si cette capacité ne lit jamais la gestion
      d'événement ?
- [ ] Quand un « set » perd une course de concurrence, le résultat de conflit visible doit-il porter
      suffisamment d'information (par exemple le montant de la version gagnante) pour que
      l'organisateur décide de sa prochaine action, ou un simple « conflit, veuillez réessayer »
      suffit-il pour cette story ?

---

# Part 2 — Coder Contract

*For the Coder. Implementation-ready acceptance criteria and context.*

## Goal
Un organisateur peut fixer un prix absolu pour une paire (événement, palier), chaque changement de
prix allouant une nouvelle version et aucun changement concurrent n'étant jamais silencieusement
perdu.

## Context
Ceci est la première des deux opérations d'écriture sur l'agrégat de prix du module `pricing` pour
une clé (événement, palier) donnée (la seconde est l'ajustement en pourcentage dans
`adjust-price-percentage.md`, ST-002). Les deux opérations partagent la même chaîne de versions par
paire (événement, palier), c'est pourquoi la garantie de concurrence ci-dessous doit tenir quelle
que soit l'opération en course contre l'autre. Cette capacité ne vérifie pas que la clé (événement,
palier) correspond à un événement réel et existant — cette vérification appartient à
`eventmanagement` et est hors périmètre ici (vision-general-objective-fr.md:163-164).

## Personas
- **Organisateur (admin)** : opère depuis une console d'administration (livrable séparé, non décrit
  ici) ; fixe et refixe les paliers surtout avant et pendant la fenêtre de mise en vente ; attend un
  refus visible en cas de collision de changement, jamais un écrasement silencieux du sien ou de
  celui de quelqu'un d'autre.

## General Information

Applies to: l'intégralité du périmètre (ST-001).

### References
- spec: vision-general-objective-fr.md:45-46 (MVP item 2 — tarification), :90-91 (Montant), :92-93
  (Prix — versionné, historique complet), :155-164 (Tarification — versionnement, pas de
  vérification d'existence de l'événement), :177-191 (Contrôle d'accès — l'organisateur seul écrit
  la tarification), :511-512 (montants en unités mineures entières)
- epic: `specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability
  CAP-003, « Completion Criteria », NFR-000-1, NFR-000-2, NFR-000-4, ASM-000-1, ASM-000-2, OQ-000-2
- guardrails: GUARDRAILS.md G1.1 (contrôle de rôle sur l'écriture), G1.2 (la version et le prix
  résultant sont toujours dérivés côté serveur, jamais acceptés depuis l'appelant), G2.1 (forme en
  couches), G2.7 (discipline de la pile)

### Non-Functional Requirements
- NFR-000-1: Les montants sont des unités mineures entières de bout en bout ; aucun flottant nulle
  part dans le calcul ou le stockage d'un prix.
- NFR-000-2: Une version de prix est allouée au plus une fois par paire (événement, palier) et par
  changement logique ; une écriture concurrente perdante échoue visiblement avec un résultat typé de
  conflit — jamais silencieusement écrasée ou fusionnée.
- NFR-000-4: L'écriture de cette capacité déclare exactement un niveau d'accès (organisateur) ; un
  niveau d'accès non déclaré est une erreur de compilation, jamais une route publique silencieuse.

### Out of Scope
- Lire le prix courant faisant autorité ou son historique de versions (CAP-004, épopée sœur).
- Vérifier que la clé (événement, palier) correspond à un événement qui existe réellement
  (vision-general-objective-fr.md:163-164).
- L'opération d'ajustement en pourcentage (ST-002, `adjust-price-percentage.md`).
- Tarification par siège, remises, codes promo (explicitement hors périmètre de la vision).
- La consommation du prix résultant par le parcours d'achat ou par la projection tarifaire publique.
- Toute IHM, écran ou navigation de la console d'administration.
- Enregistrer qui (quel organisateur) a effectué un changement donné, au-delà du numéro de version
  lui-même — la vision ne demande pas de piste d'audit ici.

### Assumptions
- **ASM-000-1** : Il n'existe pas de prix implicite par défaut pour une paire (événement, palier)
  tant qu'un « set » explicite n'a pas eu lieu. — *Why* : la vision ne décrit que « set » (absolu) et
  « adjust » (relatif au prix courant), jamais de valeur de départ implicite. Confidence: HIGH.
- **ASM-000-2** : Un prix porte sa propre devise, fournie par l'appelant au moment du « set » (parmi
  l'ensemble fermé USD/EUR/GBP) ; cette capacité ne vérifie pas cette devise par rapport à la devise
  configurée de l'événement, puisqu'elle ne lit jamais la gestion d'événement. — *Why* : « Montant »
  est défini comme une somme dans une devise (vision-general-objective-fr.md:90-91), et cette
  capacité n'opère que sur la clé (événement, palier) (vision-general-objective-fr.md:163-164).
  Confidence: MEDIUM.

### Open Questions
- **OQ-000-2** : Qui garantit — si quiconque — que la devise portée par un prix fixé correspond à la
  devise configurée de l'événement, étant donné que cette capacité ne vérifie jamais que l'événement
  existe ? — *Impact if unresolved* : une incohérence de devise entre un prix et son événement
  pourrait passer inaperçue jusqu'à l'achat.

---

## Story ST-001 - Set absolute price

### References
- spec: vision-general-objective-fr.md:155-164 (fixer un prix absolu ; versionnement ; au plus deux
  décimales), :90-91 (Montant)
- epic: Capability CAP-003, Story Documents table (row "set-price.md")

### User Story
**En tant qu'** organisateur (admin) opérant depuis la console d'administration, **je veux** fixer
un prix absolu (un montant et une devise) pour une paire (événement, palier) spécifique, **afin
que** cette paire ait un prix courant faisant autorité sur lequel les autres parties du système
puissent ensuite s'appuyer, et que je puisse le changer à nouveau chaque fois que nécessaire sans
jamais perdre silencieusement un changement concurrent.

### Acceptance Criteria
- AC-001-1: Étant donné une paire (événement, palier) sans version de prix existante, quand
  l'organisateur fixe un prix absolu avec un montant valide (au plus deux décimales) et une devise
  de l'ensemble fermé (USD/EUR/GBP), alors une nouvelle version de prix (la première) est créée pour
  cette paire et devient son prix courant faisant autorité.
- AC-001-1b: Étant donné une paire (événement, palier) ayant déjà un prix courant faisant autorité,
  quand l'organisateur fixe un nouveau prix absolu pour elle, alors une nouvelle version, numérotée
  strictement après la précédente, est créée et devient le nouveau prix courant faisant autorité ;
  le montant enregistré de la version précédemment courante n'est ni modifié ni supprimé.
- AC-001-2: Étant donné un montant exprimé avec plus de deux décimales, quand l'organisateur tente
  de le fixer, alors la requête est rejetée avec une erreur de validation nommant le champ montant,
  et aucune nouvelle version n'est créée.
- AC-001-2b: Étant donné une devise hors de l'ensemble fermé (USD/EUR/GBP), quand l'organisateur
  tente de fixer un prix dans cette devise, alors la requête est rejetée avec une erreur de
  validation nommant le champ devise, et aucune nouvelle version n'est créée.
- AC-001-3: Étant donné un montant nul ou négatif, quand l'organisateur tente de le fixer, alors la
  requête est rejetée avec un résultat typé de violation de règle, et aucune nouvelle version n'est
  créée. (voir ASM-001-1)
- AC-001-4: Étant donné deux organisateurs (ou deux requêtes concurrentes, par exemple deux onglets
  de navigateur) soumettant un « set absolute price » pour exactement la même paire (événement,
  palier) au même moment, quand les deux soumettent, alors exactement l'un réussit et sa version
  devient le nouveau prix courant faisant autorité ; l'autre reçoit un résultat de conflit visible et
  typé et son prix soumis est écarté — jamais silencieusement fusionné, réessayé automatiquement, ou
  partiellement appliqué.
- AC-001-5: Étant donné un principal ne détenant pas le rôle organisateur (un client authentifié, un
  opérateur, ou un appelant non authentifié), quand il tente de fixer un prix absolu, alors la
  requête est refusée avec un résultat typé de refus de permission et aucun changement de prix ne se
  produit.

### Depends on:
Aucune. Cette story est la première écriture contre l'agrégat de prix d'une paire (événement,
palier).

### Out of Scope
- L'opération d'ajustement en pourcentage (ST-002 dans `adjust-price-percentage.md`).
- Lire le prix courant résultant ou tout historique de versions renvoyé à l'appelant au-delà de la
  confirmation que la nouvelle version a été créée (CAP-004, épopée sœur).
- Vérifier la clé (événement, palier) par rapport à un événement réellement existant.

### Assumptions
- **ASM-001-1** : Un montant de prix fixé doit être un nombre strictement positif d'unités mineures ;
  fixer un montant nul ou négatif est rejeté, pas accepté, plafonné, ou corrigé silencieusement. —
  *Why* : la vision n'énonce pas explicitement de plancher pour un prix absolu fixé directement, mais
  un prix représente une somme monétaire facturable pour un palier de billet, et un prix nul ou
  négatif n'est pas une somme significative à facturer. Ceci reflète le même écart ouvert que l'epic
  signale pour les ajustements en pourcentage (OQ-000-1 dans le document epic), afin que les deux
  chemins d'écriture traitent le plancher de façon cohérente. Confidence: MEDIUM — signalé pour
  confirmation humaine avant que cette story ne soit codée.

### Open Questions
Aucune au-delà de l'OQ-000-2 au niveau du document, ci-dessus.
