# Description du projet Liza

## Objet

Liza est un système multi-agents de développement logiciel (Go + scripts Python) qui orchestre des agents IA (Claude, Codex, Gemini, Mistral…) pour produire du code de qualité production dès le premier passage, avec auditabilité complète.

Il fonctionne selon deux modes complémentaires :

- **Pairing** — un agent IA code en tandem avec le développeur, sous contrat comportemental strict
- **MAS (Multi-Agent System)** — pipeline autonome de goal → epics → user stories → plans → code → tests, avec 12 rôles spécialisés organisés en paires doer/reviewer. Les *entry points* (`--entry-point`) permettent d'entrer plus bas dans le pipeline (spec fonctionnelle → architecture → plan → code ; spec technique → plan → code). Un goal simple ne produit qu'une seule tâche de planning ; un goal à fan-out produit d'abord une tâche de planning « maître » dont l'`output[]` approuvé par quorum engendre les tâches spécialisées.

---

## Postures de collaboration (mode Pairing)

Les postures définissent la répartition des rôles entre l'humain et l'agent selon le contexte. Elles sont activables à la volée.

| Posture | Rôle de l'agent | Quand l'utiliser |
|---------|----------------|-----------------|
| **Autonomous** *(défaut)* | Propose, l'humain approuve, l'agent exécute | Développement courant, besoins clairs |
| **Coach** | Pose des questions Socratiques sur le *pourquoi*, sans proposer de solution | Le QUOI est clair mais pas le POURQUOI |
| **Challenger** | Attaque un plan finalisé avant exécution ("quel failure mode n'a pas été discuté ?") | Stress-test avant engagement |
| **User Duck** | Pense à voix haute pas à pas, l'humain écoute et redirige | Débogage complexe, code inconnu |
| **Agent Duck** | L'humain verbalise, l'agent pose des questions pour clarifier | Idée floue, exigences imprécises |
| **True Pairing** | Échange rapide, aucun ne drive exclusivement | Exploration à haute incertitude |
| **Spike** | Co-explore via du code jetable, livre une spec (pas du code de prod) | Validation de requirements par le code |

---

## Rôle de l'humain en mode MAS

L'humain est **superviseur entre les sprints**, pas participant dans les sprints. Les agents gèrent tout le travail interne ; l'humain intervient aux checkpoints.

### Avant le lancement
- Définir le goal et pointer vers la spec (`liza init "..." --spec`)
- Rédiger les `GUARDRAILS.md` (contraintes projet)
- Spawner les agents (`liza launch wezterm mas --preset ...`)

### Aux checkpoints (entre chaque paire de rôles)

| Action | Commande | Quand |
|--------|----------|-------|
| Accepter et continuer | `liza resume` | Satisfait du résultat |
| Amender et replanifier | Éditer le plan + `liza replan` | Vouloir changer la décomposition proposée |
| Transition manuelle | `liza proceed <task-id> <transition>` | Créer les tâches suivantes manuellement |
| Tout arrêter | `liza stop` | Abandonner |

`/checkpoint-summary` dans une session de pairing produit un digest priorisé des décisions agents, points ouverts et risques.

### Sémantique de `resume` / `proceed`

- Un sprint au statut `CHECKPOINT` ne passe **pas** `COMPLETED` tout seul : il faut un `liza resume` (sauf `--auto-resume`).
- `liza resume` est surchargé. Au `CHECKPOINT`, s'il existe une transition exécutable (une tâche de planning approuvée avec un `output[]` non consommé), il **exécute cette transition** — crée les tâches enfants et repasse le sprint `IN_PROGRESS` — au lieu de marquer `COMPLETED` ; un `liza proceed` manuel échoue alors avec « sprint must be COMPLETED ».
- N'avancer qu'une partie des tâches de planning (échantillonner un sous-domaine) suppose une fenêtre étroite : `liza replan` exige le sprint au `CHECKPOINT`, la tâche cible `MERGED` **avec** un `output[]`, et aucune transition déjà exécutée dans le sprint.

### Modifier la spec en cours de run

Amender la spec après le lancement est possible ; le coût dépend de l'avancement.

**Dans la fenêtre de replan** (sprint `CHECKPOINT`, planning `MERGED` avec `output[]`, aucune transition exécutée) : éditer le fichier de plan puis `liza replan`. Le planner reprend une nouvelle tâche, relit le plan amendé et régénère son `output[]`. C'est le chemin propre.

**Une fois les tâches de coding créées**, `liza replan` est refusé et propager un changement de spec devient une opération manuelle sur le graphe :

1. éditer `specs/vision.md` **en gardant les titres de section stables** — les `spec_ref` des tâches pointent vers `vision.md#<titre>` et la validation d'état rejette au merge toute référence d'artefact devenue invalide ;
2. `liza supersede-task <tâche> --by <remplaçante>` pour chaque tâche dont le `done_when` ou le scope change (nouvelles tâches via `add-tasks` depuis un JSON) ;
3. `liza retarget-dependency` pour rebrancher les arêtes des tâches en aval vers les remplaçantes ;
4. ajuster les fichiers partagés qu'implique le nouveau périmètre (p. ex. une dépendance dans `pom.xml`) ;
5. `liza start` puis respawn des `liza agent`.

**Cas rencontré.** La spec imposait des tests d'intégration « Testcontainers PostgreSQL » ; le sandbox des agents n'ayant pas de daemon de conteneur, la tâche de projection a fini `BLOCKED` avec un code qui pourtant compilait — son `done_when` (`mvn … verify`) ne pouvait pas s'exécuter. Décision humaine : valider le `done_when` contre un PostgreSQL démarré in-process (embedded-postgres), et reléguer Testcontainers à un profil Maven optionnel réservé à la CI. L'amendement de `specs/vision.md` s'est limité au bullet *Tests* de `## Constraints` et à l'item *Build gate* des *Success Criteria*, **titre `## Constraints` inchangé** pour préserver l'ancre `#constraints` que référencent les tâches. Mais comme la tâche de planning avait déjà engendré ses neuf enfants, le replan n'était plus disponible : rejouer le codage supposerait le parcours supersede / retarget ci-dessus, plus l'ajout de la dépendance embedded-postgres au `pom.xml`. Leçon : décider tôt — idéalement au checkpoint planning → coding — de la façon dont chaque `done_when` s'exécutera réellement dans l'environnement des agents, car après le fan-out le coût d'un changement de stratégie de validation est bien supérieur à celui de l'édition de la spec elle-même.

### Ce que l'humain ne fait PAS
- Il n'approuve pas chaque tâche individuelle (c'est le reviewer agent)
- Il ne participe pas aux échanges doer/reviewer
- Il ne commit pas le code

En mode `--auto-resume` ("yolo"), même les checkpoints sont automatiques.

---

## Connaissance métier et choix de l'entry point

À `--entry-point general-objective`, le pipeline démarre à la planification d'epics : les agents déclinent l'objectif produit en epics puis en user stories avant toute architecture. Cette décomposition suppose une compréhension fonctionnelle du domaine. La question — *sur quels domaines les agents en savent-ils assez ?* — est mal posée : **dans le modèle de Liza, la connaissance métier vient du goal document, pas de l'entraînement des LLM.**

### Le cadrage : la réalité métier est un input humain

Le skill `check-liza-input-readiness` (`/check-liza-input-readiness <doc> general-objective`) exige que le document d'entrée contienne déjà :

- énoncé du problème et motivation ;
- personas cibles avec assez de contexte pour piloter le comportement ;
- périmètre MVP et hors-scope explicite ;
- capacités, workflows, **règles métier**, entrées/sorties, cas limites ;
- **décisions produit** qui affectent le comportement ;
- critères de succès, risques et hypothèses.

Il marque comme *bloquant* : « Critical product choices deferred to agents » et « vague capability descriptions such as "improve UX" or "add auth" with no behavior ». Le skill `goal-writing` coache l'humain pour produire exactement ça, sous les postures Coach puis Challenger. La règle affichée du système : *agents may make implementation choices but not product decisions.*

### Ce que l'agent apporte réellement à l'étape epic / US

C'est une compétence surtout **structurelle**, largement indépendante du domaine :

- borner une capacité cohérente, la découper en 3–8 user stories qui *composent* (cohésion forte, couplage faible, frontières DDD) ;
- regrouper des personas, expliciter le hors-scope ;
- transformer un besoin en critères d'acceptation observables et falsifiables ;
- repérer contradictions, TBD sur chemin critique, NFR manquantes.

La connaissance métier « native » du LLM n'intervient qu'en **appoint** : reconnaître un pattern standard, poser la bonne question, employer le bon terme, penser aux cas limites habituels, détecter une omission dans le goal doc. Utile — jamais source de vérité. Les paires adversariales `epic-plan-reviewer` / `us-reviewer` valident la **structure, la cohérence, la testabilité**, pas la justesse fonctionnelle : seul le **checkpoint humain en fin de phase spécification** valide que la décomposition est juste pour le métier. Plus le domaine est éloigné du logiciel publiquement documenté, plus ce checkpoint doit être une vraie revue menée par quelqu'un qui connaît le métier, pas un tampon.

### Gradient de domaines

| Niveau | Domaines | Décomposition autonome à `general-objective` |
|--------|----------|----------------------------------------------|
| **Bonne couverture** — corpus public abondant, implémentations open-source, standards ouverts | SaaS/CRUD génériques (auth, RBAC, abonnements type Stripe, notifications, back-office) ; e-commerce (catalogue, panier, checkout, commandes, stock, prix, promos) ; réservation / planification / ticketing / gestion de projet ; outils dev & infra (CI/CD, observabilité, feature flags, API gateway, files de messages) ; CMS / forums / messagerie ; IAM / SSO / OAuth-OIDC-SAML ; primitives fintech documentées (paiements, facturation, compta partie double basique) ; ETL / tracking analytics ; télémétrie IoT ; logistique de base | Fiable **si le goal document est solide** |
| **Couverture moyenne** — la forme est connue, les règles réelles sont idiosyncratiques ou dépendent de la juridiction | Santé (FHIR/HL7 connus ; workflows cliniques, facturation US/EU, consentement, HIPAA/RGPD-santé → spécifiques) ; assurance (cycle police/sinistres/souscription ; règles actuarielles, dépôts réglementaires → propres à l'organisme) ; RH / paie (concepts OK ; fiscalité, droit du travail, conventions collectives → nationaux) ; LMS / SIS ; immobilier / gestion locative ; secteur public ; télécom OSS/BSS ; ERP / achats / supply chain ; ad tech | Front-loading métier important + checkpoint scruté |
| **Couverture faible / risquée** — corpus propriétaire mince, réglementation mouvante, erreur subtile à forte conséquence, expertise tacite | Marchés financiers (microstructure, pricing dérivés, risque, MiFID II, clearing & settlement, ISO 20022/SWIFT/SEPA détaillés) ; core banking, AML/KYC par juridiction ; safety-critical régulé (dispositifs médicaux IEC 62304/FDA, aéronautique DO-178C, automobile ISO 26262, énergie/nucléaire) ; moteurs de calcul fiscal ; juridique local ; pharma / essais cliniques (GxP, 21 CFR Part 11) ; défense ; domaines scientifiques/industriels de niche ; **tout SI interne à règles métier fortement propriétaires** | Ne pas utiliser `general-objective` en autonome |

### Vrai quelle que soit la couverture

Les règles propres à **ton** organisation ne sont dans aucun corpus d'entraînement : modèle d'entitlements, paliers tarifaires, circuit de validation conformité, SLA. L'entraînement du LLM couvre le *domaine générique*, jamais *ton instance* du domaine — ces règles doivent figurer explicitement dans le goal document à tous les niveaux du gradient.

### Recommandation pratique

| Niveau | Approche |
|--------|----------|
| Bonne couverture | `general-objective` possible ; passer `/check-liza-input-readiness <doc> general-objective` avant lancement ; front-loader les règles propres à l'instance |
| Couverture moyenne | Goal document fortement front-loadé (règles métier, cas limites, NFR, glossaire) ; envisager d'écrire les epics à la main et d'entrer en `functional-spec` ; checkpoint humain-métier serré en fin de phase spécification |
| Couverture faible / risquée | Décomposition epic/US produite par un humain ; entrer en `functional-spec` (voire `technical-spec`) avec des specs rédigées à la main ; ne pas confier la phase spécification aux agents en autonome |

---

## Spécifications d'architecture : où et comment les introduire

« Spécifications d'architecture » recouvre trois choses de nature différente. Chacune a son emplacement et son altitude — les confondre revient soit à sur-spécifier le goal document (et retirer à l'architecte des décisions qu'il ferait mieux), soit à laisser une contrainte réelle se faire deviner.

### 1. Les contraintes qui *bornent* l'architecte → le goal document

Langage, frameworks imposés, base de données, patterns obligatoires, NFR (perf, sécurité, observabilité) : ce sont des **décisions humaines**, pas du design. Le skill `goal-writing` l'affirme explicitement — une **pile héritée**, une **borne de compatibilité**, un **garde-fou d'ingénierie** (« toutes les requêtes passent par la couche repository ») ne sont pas du détail d'implémentation : l'humain les possède **à toute altitude**, et les NFR ne sont jamais ce qu'on retire au nom du « lean ».

- **Où** : sections *Solution Overview* / *Contraintes non fonctionnelles* du goal document.
- **Altitude** : `general-objective` ou `functional-spec`.
- **Formuler comme borne, pas comme design** :
  - ✅ « doit tourner sur Java 25 + Spring Boot 4 + PostgreSQL 16 » (borne héritée)
  - ✅ « toute mutation d'état passe par un `UPDATE` gardé à une seule instruction ; pas de JPA » (garde-fou)
  - ✅ « architecture en slices : un slice = un cas d'usage, aucun couplage direct inter-slice » (règle structurelle)
  - ❌ le découpage en modules, les interfaces entre eux, le diagramme de composants, le data flow — **c'est le livrable de l'architecte** ; l'écrire dans le goal doc fige la décision avec l'autorité de l'humain et personne ne la rouvre.

### 2. Les règles permanentes liant *tous* les agents → `GUARDRAILS.md`

Fichier à la racine du projet, édité par l'humain **avant `liza init`** (ou entre sprints). Diffère du goal document : celui-ci décrit *ce goal* ; `GUARDRAILS.md` décrit les règles qui s'appliquent à **tout ce que les agents font dans ce dépôt**, quelle que soit la tâche.

- **Système de tiers** (repris de `contracts/CORE.md`) : **Tier 0** inviolable → halt immédiat (RESET) ; **Tier 1** hard → waiver explicite avec justification ; **Tier 2** defaults forts.
- **Mécanique** : le hook `enforce-init.sh` **bloque toute action** (Write, Edit, Bash…) tant que l'agent n'a pas lu `GUARDRAILS.md` — enforcement mécanique, pas seulement du prompt.
- **Contenu type** : « clean code / SOLID obligatoire », « pas de dépendance circulaire entre modules », « `booking` et `pricing` ne se référencent jamais directement », « toute API publique a un test de contrat », conventions de commit et de nommage, « pas de secret en clair ».

### 3. La conception d'architecture elle-même → livrable de la phase architecture, ou input en `technical-spec`

| En main | Entry point | Qui produit l'arch-plan | Où vont les contraintes du point 1 |
|---------|-------------|-------------------------|------------------------------------|
| Problème + contraintes | `general-objective` | Liza : epic → US → **architecture** | goal doc + `GUARDRAILS.md` |
| Comportement fonctionnel résolu, archi à faire | `functional-spec` | Liza : **architecture** (saute epic/US) | idem |
| Architecture déjà arrêtée | `technical-spec` | **l'humain** (écrit l'arch-plan) | dans le doc `technical-spec` : composants, interfaces, migrations, stratégie de test |

Format **`arch-plan`** (produit par l'agent `architect`, ou fourni par l'humain en `technical-spec`) : *Composants* (responsabilité, frontières, décisions + rationale), *Interfaces* (contrat, direction, invariants), *Data Flow*, *Cross-Cutting* (erreurs, observabilité, config, test), *Décomposition* en scopes + table de couverture *spec → scope*. Chemin : `specs/arch-plan/<goal-slug>/<timestamp>-<task-id>.md`.

En `general-objective` / `functional-spec`, l'`architect` produit ce document, l'`architecture-reviewer` le valide par verdict liant, et le checkpoint `architecture-to-code-plan` laisse l'humain le relire et l'amender avant le fan-out coding (`liza resume` pour accepter, ou éditer le plan + `liza replan` dans la fenêtre).

### Timing

Décider tôt. Une contrainte d'architecture absente que l'architecte a dû deviner devient coûteuse à corriger après le fan-out coding (`supersede-task` + `retarget-dependency` — même schéma que le cas décrit plus haut en « Modifier la spec en cours de run »). Si l'architecture est déjà arrêtée, l'écrire et entrer en `technical-spec` ; sinon, poser les **bornes** dans le goal document et `GUARDRAILS.md`, et laisser la phase architecture de Liza produire le plan, validé au checkpoint.

---

## Passer en mode maintenance

Une fois un premier incrément livré, Liza n'est ni « repartir d'en haut à chaque fois », ni « régénérer tout », ni un one-shot. **Chaque changement = un nouveau goal cadré**, à l'altitude que demande le *delta* — pas celle du système.

### Ce que Liza fait du code existant

- `liza init` est **par-goal**. Relancé sur un repo qui a déjà un `.liza/`, il propose de purger le blackboard, les worktrees et les branches `task/*` du run précédent — **pas** la branche d'intégration ni le code. Un incrément suivant = `liza init` à nouveau, nouveau goal doc, même branche d'intégration (`--branch`).
- Les agents travaillent **contre** le code existant : *survey* de l'architecture en place, `goal.BaseCommit` = base de diff, l'`integration-analyst` valide `base..HEAD`, les coders **ajoutent des commits**.
- Liza **ne diffe pas** les fichiers de spec. Chaque `init` est une décomposition fraîche ; c'est le *code* (branche d'intégration) qui est incrémental, pas la planification.

### Quels fichiers on touche

| Fichier | Rôle | En maintenance |
|---------|------|----------------|
| goal doc `general-objective` (+ traductions) | intention produit + **contraintes durables** (NFR, garde-fous d'archi) | **édité en place** quand une contrainte change ; référencé par les goals suivants |
| `GUARDRAILS.md` | règles d'ingénierie permanentes | **édité en place** ; jamais passé à `liza init` (les agents lisent le fichier de la branche) |
| `specs/goals/<changement>.md` | **un fichier par changement** | **nouveau fichier**, c'est le `--spec` du run |
| `specs/build/`, `specs/arch-plan/<slug>/`, `specs/plans/` | intermédiaires **produits par Liza** | **régénérés par les runs**, pas édités à la main (au plus un correctif trivial au checkpoint) |

### Choisir l'altitude du changement

| Nature du changement | Entrée |
|----------------------|--------|
| Nouvelle capacité, décision produit, refonte | nouveau `general-objective` (ou `functional-spec`) |
| Comportement fonctionnel résolu / changement purement structurel | `functional-spec` (ou `technical-spec` si l'arch-plan est pré-écrit) |
| Correctif ciblé, 1–2 fichiers | pas de run MAS → mode Pairing ou à la main |

La course-correction *pendant* un sprint relève de `replan` / `supersede-task` (voir [Modifier la spec en cours de run](#modifier-la-spec-en-cours-de-run)) ; cette section couvre le changement *entre deux goals*.

### Anatomie d'un goal cadré

Un doc qui décrit **un seul changement**. Il référence le système (« changement au service décrit dans le goal doc `general-objective` ») et met l'essentiel des mots sur le **delta** :

1. **Cadre** (1 ligne) — quel système, quelle spec de référence.
2. **Pourquoi** — la vraie raison (elle conditionne l'acceptation).
3. **Retiré / Modifié / Inchangé** — la liste **Inchangé** est la clôture de périmètre, pas optionnelle : c'est la barrière que le reviewer fait respecter.
4. **Décisions forcées** — tranchées ici, pas déléguées.
5. **Contraintes renversées** — nommer les lignes du goal doc / `GUARDRAILS.md` ; leur MàJ est un livrable, et pour un **renversement** elle se fait **avant** le run (sinon les agents butent sur l'ancienne règle dès la tâche 1).
6. **Migration & déploiement** — données, config, ordre.
7. **Critères de succès** — comportementaux + « la garantie X tient toujours » ; « la suite de tests existante passe sans modification » est le meilleur filet de régression.
8. **Risques / questions ouvertes.**

Passer `/check-liza-input-readiness specs/goals/<changement>.md <entry-point>` avant `init`.

### Exemple — « tout passer par JPA, y compris les updates verrouillés »

Changement de prérequis technique, comportement HTTP inchangé, mais qui **renverse des contraintes durables**.

1. **Éditer en place, avant le run** : `GUARDRAILS.md` (retirer « no ORM » ; reformuler « SQL toujours paramétré » pour JPQL / natif) ; le goal doc `general-objective` section *Contraintes NF*, puces *Persistance* (« passe par JPA/Hibernate, schéma toujours géré par Flyway ») et *Transactions* (nommer le nouveau mécanisme de concurrence). **Ne pas toucher** aux *Règles métier* : la garantie « double-attribution impossible » y est déjà énoncée sans mécanisme. Commit.
2. **Écrire `specs/goals/jpa-migration.md`** : cadre + pourquoi ; renvoi aux lignes de contrainte déjà mises à jour ; **la décision de concurrence** (verrou pessimiste + isolation, *ou* `@Version` optimiste sans retry, *ou* retry borné) avec confirmation humaine que « N acheteurs → 1 confirmé, N-1 × 409 » tient et acceptation de la régression de perf sous contention ; **Inchangé** (contrats HTTP, ordre de la saga, annulation « remboursement d'abord », projections version-guardées, BFF) ; **régénéré par le run** = l'arch-plan persistance/concurrence + les code plans (les epics/US de `specs/build/` décrivent du fonctionnel inchangé, ils restent valides) ; **critères** = suite de tests existante inchangée passe, test de concurrence 1 gagnant / N-1 × 409 via JPA, plus de SQL brut pour les transitions d'état.
3. **Lancer** :
   ```bash
   /check-liza-input-readiness specs/goals/jpa-migration.md functional-spec
   liza init --spec specs/goals/jpa-migration.md --entry-point functional-spec
   # quorum de revue relevé conseillé ; au checkpoint archi le reviewer scrute la garantie anti-double-attribution + l'isolation
   ```

---

## Support multi-langage cible

Liza est **agnostique au langage cible**. C'est un orchestrateur — le code applicatif est produit par les agents LLM sous-jacents, dans n'importe quel langage.

Écosystèmes pré-configurés dans les permissions Claude Code :

| Écosystème | Outils pré-approuvés |
|------------|---------------------|
| Python | `uv`, `ruff`, `pytest`, `mypy`, `pip`, `pre-commit` |
| Go | `go`, `make` |
| Rust | `cargo`, `rustfmt`, `clippy-driver` |
| Node.js | `node`, `npm`, `npx`, `yarn`, `pnpm`, `bun`, `eslint`, `prettier`, `tsc` |

Pour d'autres langages, ajouter les outils dans `.claude/settings.json`. L'indexation symbolique (`scip-search`) supporte Go, TypeScript et Python via `--scip-search <lang>` à l'init.

---

## Ce que le code Go implémente

Le binaire `liza` est **l'infrastructure** : il ne produit pas de code applicatif lui-même, il pilote les agents LLM qui le font.

### Composants principaux

**`cmd/liza/`** — CLI unique avec tous les sous-commandes : `init`, `agent`, `tui`, `launch`, `pause`, `resume`, `replan`, `check-commit-allowed`, etc.

**`internal/agent/`** — Moteur de supervision. Pour chaque agent spawné, une boucle tourne : enregistrement, revendication de tâche, heartbeats, redémarrage en cas de crash avec backoff. Backends LLM pluggables : CLI standard, sessions ACP (Codex/Cursor).

**`internal/db/` + `internal/models/`** — State machine partagée via `state.yaml` protégé par `flock(2)`. Contient tâches, agents, sprints, anomalies, circuit breakers. C'est le "blackboard" de coordination inter-agents.

**`internal/prompts/`** — Construction des prompts injectés aux LLM : rôle, tâche, dépendances, contexte de code (SCIP, Stacklit, Semble).

**`internal/git/`** — Gestion des worktrees git. Chaque tâche reçoit un worktree isolé dans `.worktrees/task-N/` pour exécution parallèle sans interférence.

**`internal/statevalidate/`** — Validation d'intégrité avant chaque transition : dépendances circulaires, références invalides, rôles manquants.

**`internal/tui/`** — Dashboard temps réel (Bubbletea) : liste des tâches, état des agents, alertes. Permet de spawner/pauser/reprendre sans quitter le terminal.

**`internal/toolchain/`** — Installation, configuration et health-check des CLIs LLM supportés.

**`internal/pipeline/`** — Parsing et résolution de la configuration YAML des pipelines : rôles, paires, transitions, politiques de quorum.

**`internal/embedded/`** — Tous les fichiers markdown (contrats, skills, docs support, templates) compilés dans le binaire via `go:embed`. C'est ce que `liza setup` déploie dans `~/.liza/`.

**`internal/secretmask/`** — Filtre les secrets (clés API, tokens) dans les logs agents avant écriture disque.

**`skills/`** — 28 répertoires de templates markdown définissant des workflows agents spécialisés : code-review, spec-review, architecture-planning, adversarial-pairing, checkpoint-summary, liza-logs, etc.

**`contracts/`** — Contrats comportementaux markdown (CORE.md, MULTI_AGENT_MODE.md, PAIRING_MODE.md, AGENT_TOOLS.md…) injectés dans les prompts agents.

---

## Mécanismes de contrôle au-delà des prompts

Les prompts définissent le comportement *attendu*. Des mécanismes mécaniques enforced les invariants critiques indépendamment du comportement de l'agent.

### Hooks PreToolUse (shell scripts)

Interceptent **chaque appel d'outil** avant son exécution. Non contournables par l'agent.

| Hook | Ce qu'il bloque |
|------|----------------|
| `enforce-init.sh` | Toute action (Write, Edit, Bash…) tant que l'agent n'a pas lu `AGENT_TOOLS.md`, le contrat de mode et `GUARDRAILS.md` |
| `git-guard.sh` | `git push --force`, `git reset --hard`, `git clean -f` pour les agents MAS |
| `worktree-path-guard.sh` | Chemins `.worktrees/task-1/task-1/…` (doublon de segment, bug agent connu) |
| `rtk-guard.sh` | Lecture des fichiers tee RTK et `rtk proxy` (contournements d'outillage) |

### Hook pre-commit git (par worktree)

Installé dans chaque worktree de tâche, chaîne deux vérifications :
1. **`liza check-commit-allowed`** — La tâche doit être dans un état autorisant les commits
2. **`pre-commit run`** — Les hooks qualité du projet (linters, formatters, tests rapides)

### Validation d'état

Avant chaque transition (claim, submit, approve, merge), `state.yaml` est validé : cohérence des dépendances, rôles, artefacts. Une incohérence bloque tous les agents.

### Auto-repair du pool d'agents

Le TUI (et le watch headless) spawne automatiquement un agent doer pour tout rôle qui a du travail revendicable mais aucun agent vivant enregistré (`auto_repair_agent_spawned`, tracé dans `log.yaml`, sans alerte ; les échecs lèvent `AUTO REPAIR FAILED`). Conséquence pratique : spawner le seul orchestrateur suffit souvent à amorcer un sprint — planners, reviewers et coders sont tirés à la demande quand leurs tâches deviennent disponibles.

### Reprise après arrêt

Le blackboard sur disque (`state.yaml`) est l'unique source de vérité. Après un `liza stop`, un crash ou la fin d'une session, on **relance les processus** `liza agent <rôle>` : ils se ré-enregistrent et reprennent l'état où il en était. On ne repasse **pas** par `liza init` — sur un workspace existant, `liza init` déclenche le flux de *cleanup* (purge de `.liza/`, des worktrees et des branches `task/*`).

- SIGTERM/SIGINT ou exit 42 → le claim de la tâche est relâché atomiquement, aucun claim orphelin.
- `liza recover-agent <id>` — relâche un claim resté actif, supprime le worktree et l'enregistrement de l'agent (utile quand les leases ne sont pas encore expirées).
- Les enregistrements d'agents périmés (lease expirée, PID mort) et les « zombie process » sont signalés mais demandent une action opérateur.

### Allowlist de permissions

`.claude/settings.json` généré par `liza init` liste explicitement les commandes autorisées. Les agents non-interactifs ne peuvent pas répondre à des prompts de permission — tout outil absent de la liste échoue silencieusement.

### Récapitulatif

| Couche | Mécanisme | Contournable par le prompt ? |
|--------|-----------|------------------------------|
| Hooks PreToolUse | Shell scripts bloquants avant exécution | Non |
| Pre-commit worktree | Git hook + vérification d'état | Non |
| Permissions Claude Code | Allowlist dans `settings.json` | Non |
| State machine | Validation YAML avec flock | Non |
| Contrat comportemental | Prompts injectés | Oui (en théorie) |

---

## Qualité, conformité et maintenabilité en mode MAS autonome

### Ce qui est mécaniquement garanti

Les invariants Go (state machine, hooks, verrous) empêchent les pires échecs de manière absolue :

- **Pas d'auto-approbation** — dans le chemin nominal (superviseur Go), seul l'agent ayant posé le dernier APPROVED déclenche le merge ; via le CLI (`liza wt-merge`), c'est une règle contractuelle (le prompt l'interdit aux coders) mais pas un blocage mécanique absolu
- **Pas de saut de review** — la transition IMPLEMENTING → MERGED est bloquée dans le code
- **TDD obligatoire** — les tests doivent précéder l'implémentation, vérifiable à la review
- **Pas de commits hors état valide** — le pre-commit hook vérifie l'état de la tâche avant tout commit
- **Boucles interrompues** — après 10 itérations coder / 5 cycles review, la tâche passe en BLOCKED
- **Hypothesis exhaustion** — si 2 coders différents échouent sur la même tâche, le système bloque pour intervention humaine plutôt que de boucler

### Ce qui est géré mais pas garanti

**La dérive comportementale et l'oubli de contexte** — Les contrats `.md` sont du prompt. Sur une session longue, un LLM peut déprioritiser des instructions données en début de contexte ("lost in the middle"). Liza reconnaît ce problème explicitement et le traite par plusieurs mécanismes complémentaires :

**Sessions courtes par design (principale protection)** — Chaque tâche MAS est une session agent distincte avec un contexte frais. Le contexte ne s'accumule pas sur tout un sprint, juste sur une tâche. C'est architectural, pas contractuel.

**Compaction agressive** — Le `claude.env` recommandé configure `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=30` : la compaction se déclenche à 30% de contexte restant, pas quand c'est plein. Le projet documente : *"Claude's performance degrades much before hitting a fraction of 1M context."*

**Protocole de tiers de contexte** — Le contrat définit trois niveaux de dégradation avec des transitions explicites :

- **Full** (session fraîche) — tout le contrat actif
- **Working Set** (pression détectée) — l'agent re-lit les Tier 0-1 et l'état de la tâche, annonce `⚠️ WORKING SET`
- **Kernel** (dégradation sévère) — l'agent se checkpoint et **se termine** (exit code 42) pour redémarrage propre

**Handoff (exit code 42)** — À l'épuisement de contexte, l'agent écrit un résumé + `next_action` sur le blackboard et se suicide. Le superviseur redémarre un nouvel agent qui relit le contrat from scratch.

**Limite fondamentale** — Ces mécanismes reposent sur le LLM lui-même pour détecter sa propre dégradation et déclencher le protocole. C'est circulaire : si le contexte est déjà trop dégradé pour suivre le protocole de récupération, le protocole ne s'exécute pas. Les Tier 0 sont "jamais violés" par instruction — ce qui les rend robustes en pratique c'est la combinaison sessions courtes + enforcement mécanique Go pour les cas critiques, pas le prompt seul.

**La qualité du reviewer** — Le reviewer est aussi un LLM. La mitigation est la diversité de providers (un reviewer Codex pour un coder Claude réduit les blind spots partagés) et le quorum configurable sur les tâches à risque.

### Ce qui dépend entièrement de la spec

La conformité fonctionnelle dépend de la qualité de la spec d'entrée. Si la spec est vague, les agents produisent quelque chose de cohérent avec leur interprétation. Le principe est explicite : "Spec is law — no improvements beyond spec, no refactoring outside scope, `done_when` is contract." C'est une feature (pas de scope creep) et une limite (garbage in, garbage out).

**L'ambiguïté n'arrête pas le pipeline.** Il n'existe pas de détecteur d'ambiguïté qui met le run en pause. Face à un point non tranché par la spec, le planner **choisit une interprétation et la tague comme décision explicite** (`DERIVED` / `ASSUMED`) dans le fichier de plan, à charge pour le reviewer de l'accepter ou de la contester. Le point d'intervention humain sur l'ambiguïté est le **checkpoint planning → coding** : relire les décisions taguées (`/checkpoint-summary` aide), puis `liza resume` pour accepter, ou éditer le plan + `liza replan`.

**Un blocage structurel réel, lui, arrête la tâche.** Quand un doer a besoin d'un artefact que son périmètre lui interdit de créer — p. ex. un type du kernel partagé qui n'existe pas encore parce que la tâche qui le possède n'a jamais été matérialisée — il passe la tâche en `BLOCKED` avec des `blocked_questions` précises plutôt que de sortir de son périmètre. L'orchestrateur est re-réveillé, l'humain tranche.

**Un `done_when` que le sandbox ne peut pas exécuter bloque aussi la tâche — même si le code est correct.** Observé en run : une tâche dont la validation impose un service externe absent de l'environnement d'exécution des agents (un daemon, une base réelle instanciée à la volée, un binaire non installé). Le doer écrit le code, il compile, mais la commande `done_when` échoue sur l'infrastructure, pas sur le code. L'agent le diagnostique correctement (il vérifie le PATH, les sockets, les variables d'environnement avant de conclure), rédige un `blocked_reason` précis et escalade ; l'orchestrateur (`assess-blocked`) confirme qu'il n'a pas autorité pour provisionner l'infra manquante et s'arrête. Conséquence : **tout le sous-arbre de dépendances gèle** — les tâches en aval restent `DRAFT_CODE` dependency-held, le sprint passe `STALLED`, et une alerte « no task progress for N minutes » se répète toutes les 5 min **sans aucune remédiation automatique**. C'est à l'humain de remarquer et de trancher : fournir l'infra, ou amender la spec / le plan pour une stratégie de validation exécutable dans le sandbox (et, si les tâches enfants sont déjà créées, `liza replan` n'est plus disponible — il faut passer par `supersede-task` + `retarget-dependency`).

### Ce qui reste fragile (TECH_DEBT documenté)

- Protection worktree sur MultiEdit/NotebookEdit : non vérifiée empiriquement
- Preflight provider : le premier crash d'un provider défaillant coûte une itération avant détection
- Refs d'artefacts sur tâches SUPERSEDED : non validés en merge global
- **`output[]` non persisté après un arrêt brutal** — si le processus d'un planner est tué entre le commit de ses artefacts (le plan `.md` + le `-output.json`) et l'appel `liza set-task-output` qui charge le `output[]` structuré dans `state.yaml`, la tâche peut finir `MERGED` avec un `output[]` **vide**. Le pipeline ne le détecte pas : `liza resume` saute la tâche avec un simple `WARNING: … has no output[] entries`, ne crée **aucune** tâche de coding pour ce périmètre, et avance. Les tâches en aval bloquent alors sur du code qui n'existera jamais. Symptôme : une tâche de planning `MERGED` mais `output[]=0`, alors que ses fichiers de plan sont bien sur la branche d'intégration.
- **`AGENT_TOOLS.md` stock non revu** — le contrat d'outils livré par défaut route les agents vers une douzaine de CLIs et plusieurs serveurs MCP. Si l'environnement d'exécution ne les fournit pas, les agents perdent des tours à tenter des outils absents avant de retomber sur les fallbacks, et les lookups de documentation se dégradent silencieusement. La revue décrite dans `support-docs/CUSTOMIZING_AGENT_TOOLS.md` (retirer/renommer les lignes selon l'outillage réellement installé) est indispensable avant un run sérieux, tout comme l'ajout des commandes de la toolchain cible à l'allowlist `.claude/settings.json` — une commande absente de la liste échoue silencieusement pour un agent non-interactif.
- **Sprint `STALLED` sans remédiation ni abandon** — une tâche `BLOCKED` qui demande une action humaine (infra manquante, question de spec non tranchée) ne déclenche qu'une alerte périodique « no task progress » dans `alerts.log` et le TUI. Le run ne s'arrête pas, ne notifie pas au-delà, et n'a pas de délai au bout duquel il abandonne : sans surveillance active, un sprint peut rester gelé indéfiniment.
- **Toute opération git destructive sur l'arbre de travail depuis l'extérieur casse les agents en vol** — les processus `liza agent` lisent/écrivent `.liza/state.yaml` en continu, et `.liza/` n'est pas gitignoré. Un `git stash -u`, `git clean -fdx` ou un checkout de branche divergente lancé sur le répertoire projet pendant qu'un run tourne retire l'état sous les pieds des agents, qui sortent tous sur `state.yaml: no such file or directory`. Le code déjà mergé sur `integration` n'est pas affecté et l'état se restaure en remettant `.liza/`, mais les process agents sont à respawner. Ne jamais manipuler le dépôt hors du flux `liza` tant que le système n'est pas `stop`.

Les `lessons/agents/` documentent les vrais bugs rencontrés en production : paths de worktrees mal construits, lecture de gros fichiers de tests, symlinks détruits par l'outil Edit, prérequis de build manquants. Ce sont des frictions opérationnelles, pas des échecs de qualité du code produit. Le mécanisme se nourrit pendant le run : observé sur un sprint Java, un coder a rencontré une incompatibilité de toolchain (le hook `pretty-format-java` / google-java-format 1.22 qui casse sur JDK 25 avec un `NoSuchMethodError`), l'a corrigée seul — épinglage d'une version compatible via un flag du hook, sans downgrade du JDK ni nouvel outillage — et a écrit la fiche `lessons/agents/` correspondante dans le même passage, sans intervention humaine.

### Récapitulatif

| Dimension | Réalisme |
|-----------|----------|
| Pas d'auto-approbation / pas de merge sans review | Garanti mécaniquement |
| Pas de boucle infinie | Garanti (iteration limits + circuit breaker) |
| Tests écrits avant le code (TDD) | Enforced structurellement, vérifié en review |
| Conformité à la spec | Aussi bonne que la spec — garbage in, garbage out |
| Absence de dérive comportementale | Gérée et annoncée, pas éliminée |
| Qualité architecturale | Dépend du reviewer (LLM) + de la spec archi |
| Maintenabilité | Meilleure qu'un agent solo (anomaly logs + ADR skill) — non garantie |
| Gestion de l'ambiguïté de spec | Interprétation taguée (`DERIVED`) + gate reviewer, pas d'arrêt automatique ; `BLOCKED` + questions sur dépendance structurelle manquante |
| Blocage nécessitant l'humain (infra absente, `done_when` non exécutable, spec à trancher) | Correctement détecté et escaladé par le doer + l'orchestrateur, mais gèle tout le sous-arbre de dépendances ; alerte périodique seulement, ni remédiation ni abandon automatiques |
| Reprise après arrêt/crash | Le blackboard est la source de vérité ; on relance `liza agent <rôle>`, jamais `liza init`. Un arrêt entre commit d'artefacts et `set-task-output` peut laisser une tâche `MERGED` sans `output[]` — non détecté |

Ce n'est pas "ça marche tout seul sans dérive". C'est "les dérives critiques sont mécaniquement bloquées, les dérives mineures sont annoncées et bornées, et l'humain reste le filet de sécurité aux checkpoints inter-sprints".
