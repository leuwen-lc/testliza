# Vision (general-objective) : plateforme de billetterie événementielle

> Document d'entrée de niveau **`general-objective`** pour le pipeline MAS de Liza
> (planification d'epics → user stories → architecture → plan de code → code). Il
> énonce le problème, les utilisateurs, le comportement attendu, les règles
> métier et les contraintes — **pas** la surface HTTP, les formes de
> requête/réponse, les codes de statut, le schéma de stockage ni le flot de
> contrôle, qui relèvent de la conception faite par le pipeline. Dérivé de
> `vision.md`, une rétro-spécification d'une implémentation existante.
>
> Version française de `vision-general-objective.md` ; en cas de divergence, la
> version anglaise fait foi.

## Problème et motivation

Vendre des billets pour des événements avec places assises a un noyau dur : deux
personnes ne doivent jamais se retrouver avec le même siège ; un client ne doit
jamais perdre d'argent sans recevoir soit un billet, soit un remboursement ; et
le public doit voir une disponibilité et des prix exacts sans se connecter.
Tenir ces garanties sous forte concurrence est précisément là où les
implémentations naïves échouent. Ce service porte ce cycle de vie de bout en
bout, pour que les organisateurs mettent un événement en vente et que les clients
achètent en confiance.

## Utilisateurs cibles

- **Organisateur (admin)** — crée et gère les événements, l'inventaire de sièges
  et les prix ; ouvre et annule les événements.
- **Exploitation (opérateur)** — maintient l'inventaire sain : retire et
  réintègre des sièges à l'unité, déclenche la récupération des pré-réservations
  périmées.
- **Client (authentifié)** — navigue, pré-réserve un siège, l'achète, annule une
  réservation pour être remboursé. Connecté ; n'agit que sur ses propres
  réservations.
- **Public (anonyme)** — consulte le statut de vente, la disponibilité des sièges
  et les prix publiés. Sans compte.

## Objectif et périmètre du MVP

Un service HTTP/JSON couvrant tout le chemin de la création d'un événement à
l'achat puis à l'annulation d'un billet :

1. **Mise en place événement et inventaire** — créer un événement en brouillon,
   ajouter des sièges (chacun avec un palier tarifaire fixe), ouvrir l'événement
   à la vente, annuler un événement.
2. **Tarification** — fixer un prix absolu par (événement, palier), l'ajuster
   d'un pourcentage, lire le prix courant faisant autorité.
3. **Pré-réservations** — un client pose une pré-réservation limitée dans le
   temps sur un siège, peut en consulter l'état ; les pré-réservations périmées
   sont récupérées automatiquement.
4. **Achat** — un client achète un siège pré-réservé (ou libre) : le prix est
   établi, le paiement est prélevé, un billet et un reçu sont émis.
5. **Annulation** — un client annule une réservation et est remboursé
   intégralement ; le siège retourne à la vente.
6. **Modèles de lecture publics** — n'importe qui peut lire le statut de vente,
   la disponibilité par siège, le nombre de places vendues par événement et les
   tarifs destinés au client.

## Hors périmètre

- Le mécanisme d'authentification lui-même (on suppose que la plateforme fournit
  une identité authentifiée et ses rôles).
- La tarification par siège (les prix sont par palier uniquement), les remises,
  les codes promo, les commandes multi-sièges / panier, les listes d'attente,
  les remboursements partiels.
- Les plans de salle et leur rendu ; la modélisation de lieu au-delà d'un nom de
  lieu libre et de libellés section / rang / numéro.
- Les moyens de paiement, la conformité PCI et le règlement financier — le
  paiement est délégué à une passerelle externe derrière une interface réduite.
- La garantie de livraison, le contenu et les canaux des notifications.
- Le reporting, l'analytique et le rapprochement financier au-delà de
  « remboursé intégralement ».
- La conversion de devises (les prix d'un événement sont dans une seule devise).

## Concepts métier

- **Événement** — un spectacle vendable dans un lieu. Cycle de vie : `brouillon`
  → `en vente` → (éventuellement) `annulé`. L'annulation est terminale et
  idempotente.
- **Siège** — une place numérotée (section / rang / numéro) d'un événement, avec
  un **palier tarifaire** fixe. Cycle de vie : `disponible` ↔ `bloqué`
  (l'opérateur le retire) et `disponible` ↔ `vendu`. Un état `retiré` est réservé
  à un retrait définitif du catalogue.
- **Palier tarifaire** — un ensemble fermé et configuré de paliers nommés
  (premium, standard, économique, accessible, visibilité réduite).
- **Montant** — une somme dans une seule devise issue d'un ensemble fermé
  (USD / EUR / GBP), toujours exacte.
- **Prix** — le prix courant pour un (événement, palier), avec un historique
  complet de versions ; chaque changement produit une nouvelle version.
- **Pré-réservation** — la prise exclusive et temporaire d'un siège par un
  client. Cycle de vie : `pré-réservé` → `confirmé` (devenu un achat) | `expiré`
  (délai écoulé) | `annulé` (libéré).
- **Réservation ferme** — l'achat confirmé d'un siège par un client. Cycle de
  vie : `confirmée` → `annulée`.
- **Paiement** — le mouvement d'argent associé à une réservation : prélevé
  intégralement à l'achat, remboursé intégralement à l'annulation.
- **Billet** — ce que le client reçoit pour une réservation confirmée ; invalidé
  à l'annulation.
- **Projection de disponibilité** — une vue publique, à cohérence à terme, de la
  disponibilité de chaque siège et du nombre de places vendues par événement.
- **Projection de tarif client** — une vue publique, à cohérence à terme, du
  dernier prix par (événement, palier).

## Règles métier et décisions produit

### Concurrence et intégrité

- **La double attribution d'un siège est impossible.** Pour un siège donné, au
  plus un client peut le pré-réserver ou le posséder à un instant donné. Sous N
  acheteurs simultanés d'un même siège, exactement un réussit et les autres sont
  refusés proprement.
- **Un achat en échec ne laisse aucune trace** — aucune pré-réservation vivante,
  aucun argent prélevé.
- **L'annulation rembourse d'abord.** Le remboursement aboutit avant que le
  billet ou le siège ne soit touché, de sorte que « siège libéré mais argent
  conservé » ne peut jamais se produire. L'annulation est idempotente : une
  annulation rejouée ou reprise ne rembourse jamais deux fois.
- **Annuler un événement ne se propage pas** à ses sièges ni aux réservations
  existantes.

### Pré-réservations

- Une pré-réservation dure **15 minutes**. Le client propriétaire peut la
  rafraîchir en la reprenant, mais la **durée de vie totale d'une pré-réservation
  est plafonnée à 60 minutes** à compter de sa première prise, de sorte qu'un
  siège ne peut pas être squatté indéfiniment via une minuterie.
- La pré-réservation vivante d'un client se transforme directement en son achat.
- Les pré-réservations périmées — et les réservations confirmées orphelines
  laissées par une panne en cours d'achat — sont récupérées automatiquement par
  une purge périodique (environ une fois par minute) et à la demande de
  l'opérateur.
- L'état rapporté d'une pré-réservation est l'un de : fraîche, proche de
  l'expiration, expirée, vendue, ou aucune.

### Achat

- Un client peut détenir au plus **5 réservations fermes actives (confirmées)** à
  la fois ; un achat supplémentaire est refusé pour inéligibilité.
- Le prix facturé est le prix courant faisant autorité pour le **palier propre au
  siège** au moment de l'achat. Un client ne doit pas être coté ni facturé pour
  un palier autre que celui du siège qu'il achète (voir questions ouvertes).
- Le paiement est prélevé **intégralement au moment de l'achat** ; il n'y a pas
  d'étape de capture ultérieure distincte.
- La notification de confirmation est au mieux (best-effort) et ne bloque ni ne
  fait jamais échouer un achat.

### Tarification

- Les prix sont **versionnés** ; une version est allouée au plus une fois par
  (événement, palier), de sorte que deux changements de prix simultanés ne
  peuvent pas entrer en collision ni s'écraser silencieusement.
- Un ajustement en pourcentage met à l'échelle le prix courant et est **arrondi
  au demi supérieur** (`half-up`) à l'unité mineure entière.
- Les montants sont acceptés avec au plus deux décimales.
- La tarification opère sur une clé (événement, palier) et ne vérifie pas
  elle-même l'existence de l'événement.

### Attentes de cohérence

- Le **prix faisant autorité** (utilisé pour l'achat et pour la cotation admin)
  est mis à jour de façon synchrone avec le changement de prix.
- Les modèles publics de **disponibilité** et de **tarif client** sont à
  **cohérence à terme** ; une obsolescence bornée est acceptable. Ils ne sont
  jamais le mécanisme qui empêche la double attribution — c'est la vérification
  de pré-réservation faisant autorité qui l'assure.

## Contrôle d'accès

Chaque opération déclare exactement un niveau d'accès ; une opération sans niveau
est une erreur de compilation, jamais une route publique silencieuse.

- **Public** — statut de vente, vendabilité d'un siège, disponibilité d'un siège,
  nombre de places vendues, cotation de prix, tarif client.
- **Client authentifié** — poser une pré-réservation, la consulter, acheter,
  annuler ; uniquement pour l'identité et les réservations propres au client qui
  agit.
- **Organisateur (admin)** — toutes les écritures d'événement et de tarification.
- **Opérateur** — la purge des pré-réservations.

## Contraintes non fonctionnelles

- **Runtime / pile** : Java 25 (LTS), Spring Boot 4+, PostgreSQL 16+.
- **Garde-fou de persistance** : toutes les transitions d'état sont du SQL gardé
  (requête conditionnelle) écrit à la main, en une seule instruction (pas
  d'ORM / JPA) ; les changements de schéma sont des migrations versionnées.
- **Montants** : unités mineures entières de bout en bout ; aucun flottant nulle
  part dans la tarification ou les paiements.
- **Signalement des échecs** : tout échec connu est associé à un résultat typé et
  précis, exploitable par l'appelant (entrée malformée, violation de règle,
  ressource absente, permission refusée, dépendance indisponible) — jamais une
  erreur serveur générique.
- **Passerelle de paiement externe** : atteinte via une interface réduite
  (autoriser, annuler, rembourser) avec une implémentation HTTP réelle et un
  bouchon (stub) en mémoire sélectionnable pour les tests et l'exécution locale.
- **Configuration** : connexion base de données, adresse de la passerelle,
  cadence de purge et toutes les valeurs de temporisation des pré-réservations
  sont configurables de l'extérieur.
- **Tests** : chaque cas d'usage teste unitairement le chemin nominal et chaque
  échec typé ; des tests d'intégration couvrent le SQL gardé et les projections
  contre un vrai PostgreSQL ; un test de concurrence prouve un seul gagnant par
  siège.

## Critères de succès

- Un organisateur peut mener un événement de la création à la mise « en vente »
  en passant par la configuration des sièges et la tarification ; une seconde
  tentative d'ouverture du même événement est refusée.
- Un client peut pré-réserver un siège, le voir rapporté comme frais, puis
  l'acheter et recevoir une réservation, un billet, un reçu et le bon montant.
- Après un achat réussi, la vue publique de disponibilité montre le siège vendu
  et le compteur de places vendues de l'événement incrémenté (dans la limite de
  son obsolescence).
- Un second client achetant le même siège est refusé.
- Un client détenant déjà cinq réservations fermes actives se voit refuser la
  sixième.
- Un paiement refusé produit une erreur de paiement et laisse le siège vendable,
  sans pré-réservation ni prélèvement.
- Une annulation rend un remboursement intégral puis libère le siège ; une
  annulation répétée est refusée sans second remboursement ; l'annulation par un
  non-propriétaire est refusée.
- Une pré-réservation non achetée est rapportée proche de l'expiration, puis
  expirée après celle-ci, et son siège redevient vendable après la purge.
- Bloquer un siège refuse les pré-réservations et les achats sur ce siège ; le
  débloquer les rétablit.
- Aucun chemin d'échec connu ne renvoie une erreur serveur générique.

## Risques, hypothèses et questions ouvertes

- **Hypothèse** : une identité authentifiée et un jeu de rôles sont fournis par
  la plateforme ; ce service applique les niveaux d'accès mais n'implémente pas
  la connexion.
- **Question ouverte — liaison d'identité** : l'achat et l'annulation doivent
  agir sur l'identité du client *authentifié*, non sur une valeur de client
  passée dans le corps de la requête. La rétro-spécification source laisse ce
  point ambigu ; la règle visée est « l'identité provient du principal
  authentifié ». À trancher lors de l'écriture des stories.
- **Question ouverte — cohérence palier / siège** : la règle visée est qu'un
  achat est toujours facturé au palier propre au siège. Une implémentation
  antérieure faisait confiance à un palier fourni par le client ; à régler lors
  de l'écriture des stories.
- **Risque** : l'obsolescence des modèles de lecture est acceptable pour
  l'affichage mais ne doit jamais fonder une décision de correction.
- **Risque** : un événement de domaine perdu (siège vendu / libéré, prix changé)
  laisse les projections publiques obsolètes jusqu'au prochain événement pour
  cette entité — acceptable pour le MVP ; à revoir si cela devient visible pour
  l'utilisateur.
