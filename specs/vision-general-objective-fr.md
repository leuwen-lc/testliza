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

- L'administration de l'IdP et le cycle de vie des utilisateurs — comptes,
  inscription, réinitialisation de mot de passe, vérification d'e-mail, politique
  MFA, tout géré dans Keycloak — ainsi que le choix de la bibliothèque cliente
  OIDC.
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
- La protection anti-abus au bord — rate limiting, WAF, défense anti-bot /
  anti-bruteforce — assurée par la passerelle / l'infrastructure et par l'IdP,
  pas par cette application.

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
- **Identité client** — le sujet OIDC stable (claim `sub`) du principal
  authentifié. Le système enregistre cette valeur comme le client sur les
  pré-réservations, les réservations fermes et les paiements ; il ne stocke
  aucun mot de passe ni autre secret de connexion.

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

Les niveaux d'accès sont dérivés du jeton OIDC validé : un rôle / groupe de realm
Keycloak configuré donne **admin**, un autre **opérateur**, tout principal
porteur d'un jeton valide est un **client authentifié**, et un jeton absent ou
invalide est **public**. Quel nom de rôle accorde quel niveau relève de la
configuration de déploiement, pas du code.

- **Public** — statut de vente, vendabilité d'un siège, disponibilité d'un siège,
  nombre de places vendues, cotation de prix, tarif client.
- **Client authentifié** — poser une pré-réservation, la consulter, acheter,
  annuler ; uniquement pour l'identité (le `sub` du jeton) et les réservations
  propres au client qui agit.
- **Organisateur (admin)** — toutes les écritures d'événement et de tarification.
- **Opérateur** — la purge des pré-réservations.

## Interfaces utilisateur (côté client)

Périmètre de cette section : uniquement les surfaces utilisées par le **public**
et par les **clients authentifiés**. Les consoles organisateur et opérateur sont
un livrable distinct, non décrit ici. Ce qui suit est du comportement et des
garanties, pas des composants, des routes, des maquettes ni un framework front
particulier.

### Surfaces et session

- **Catalogue public** (sans compte) — vues en lecture seule de ce qui est en
  vente.
- **Espace client** (connecté) — pré-réserver, acheter, gérer ses réservations.
- La navigation anonyme est toujours possible ; la connexion n'est requise que
  pour pré-réserver, acheter ou annuler.
- Se connecter = redirection (via le BFF) vers la page de login hébergée par
  l'IdP (Keycloak) ; l'application n'a aucun champ mot de passe et ne voit jamais
  les identifiants du client. Le navigateur revient sur une session same-origin —
  l'identité et le rôle sont résolus côté serveur.
- Navigation filtrée par rôle : un client connecté ne voit que les écrans client.
  Toute tentative d'atteindre un écran organisateur/opérateur (y compris en
  saisissant une URL) aboutit sur un écran sobre « vous n'avez pas accès ». Le
  filtrage de l'IHM est du confort — l'API reste le point d'application.
- Expiration de session en cours de tâche → le BFF rafraîchit ses jetons
  silencieusement quand c'est possible ; sinon le client est renvoyé vers l'IdP
  pour se reconnecter puis ramené là où il était, avec le contexte en cours
  (événement/siège sélectionné, pré-réservation vivante) préservé s'il est encore
  valide. Rien n'est perdu silencieusement.
- Se déconnecter efface la session locale et met fin à la session au niveau de
  l'IdP (single logout).

### Écrans

**1. Parcourir les événements et la disponibilité** (public)

- Objectif : trouver un événement et voir si des sièges sont en vente et à quel
  prix.
- Affiché : les événements avec leur statut de vente et leur heure de mise en
  vente ; la disponibilité par siège (disponible / vendu) et le nombre de places
  vendues par événement ; le prix publié par palier.
- Actions : ouvrir un événement ; depuis un siège, démarrer une pré-réservation
  (invite à se connecter si besoin).
- Fraîcheur : disponibilité, prix et compteurs proviennent de projections qui
  peuvent être en retard. L'écran affiche un repère « à jour au <heure> » et un
  rafraîchissement manuel, et ne présente jamais une donnée en retard comme
  garantie — un siège affiché « disponible » peut encore être refusé au moment
  de la pré-réservation, ce qui est géré proprement (écran 2).
- États : chargement ; vide (aucun événement / aucun siège) ; un siège ou un
  prix temporairement indisponible → montré comme tel, pas comme une page
  d'erreur.

**2. Pré-réserver un siège** (authentifié)

- Objectif : poser une pré-réservation exclusive et limitée dans le temps sur un
  siège choisi, pour pouvoir l'acheter sans concurrence.
- Affiché : le siège (section / rang / numéro) et son palier ; l'événement.
- Actions : confirmer la pré-réservation ; annuler.
- En cas de succès : aller vers « Ma pré-réservation » (écran 3) avec un compte à
  rebours en direct.
- Saisies : rien qui identifie le client — l'identité vient de la session.
- États :
  - Siège pris à l'instant par quelqu'un d'autre, retiré de la vente, ou
    événement qui a cessé de vendre → un message précis et non technique (« Ce
    siège vient d'être pris » / « Ce siège n'est pas en vente » / « Cet événement
    n'est plus en vente ») et un retour au parcours ; rien de réservé, rien de
    débité.
  - Le client détient déjà ce siège → la pré-réservation est rafraîchie et il
    continue, sans erreur.
  - Service indisponible → « Nous n'avons pas pu réserver ce siège », proposer de
    réessayer.

**3. Ma pré-réservation / checkout** (authentifié)

- Objectif : suivre une pré-réservation vivante et la transformer en achat avant
  qu'elle n'expire.
- Affiché : le siège pré-réservé et l'événement ; un **compte à rebours en
  direct** jusqu'à l'expiration, avec aussi l'heure d'expiration absolue ; le
  plafond de durée de vie totale, pour qu'un client qui rafraîchit sans cesse
  comprenne pourquoi cela finira par ne plus se rafraîchir ; le prix à payer une
  fois chargé.
- Actions : passer à l'achat ; libérer la pré-réservation maintenant (le siège
  retourne en vente immédiatement) ; quitter (la pré-réservation suit son
  cours).
- États :
  - Le compte à rebours atteint zéro à l'écran → l'achat est désactivé, un
    message clair explique que la pré-réservation a expiré, et on propose au
    client de retenter une pré-réservation du siège (peut échouer) ou de revenir
    au parcours.
  - Prix pas encore chargé → aucune action d'achat n'est proposée tant que le
    prix faisant autorité n'est pas affiché.

**4. Acheter ma place** (authentifié) — l'écran critique pour la confiance

- Objectif : finaliser l'achat avec une clarté totale sur ce qui sera débité,
  réversible jusqu'à une unique confirmation délibérée.
- Affiché, avant tout débit :
  - L'événement (nom, lieu, date/heure), le siège exact et son palier.
  - Le **total exact** à débiter : un montant et une devise sans ambiguïté, dans
    la devise de l'événement, formaté selon la locale — pas d'« estimation », pas
    de frais révélé plus tard, jamais un entier d'unités mineures brut.
  - Le temps restant sur la pré-réservation (en direct).
  - Une phrase claire indiquant qu'**aucun montant n'est prélevé tant que
    « Confirmer » n'est pas actionné**, et que le paiement est pris en charge par
    le prestataire externe sur un canal sécurisé — l'application ne voit ni ne
    stocke jamais de données de carte.
- Saisies :
  - Rien qui identifie le client — l'identité vient de la session ; l'écran
    n'affiche ni ne demande jamais d'identifiant client.
  - Aucun choix de palier — c'est celui du siège ; s'il semble faux, le client
    annule, il ne peut pas le forcer.
- Actions :
  - **Confirmer l'achat** — la seule action qui débite. Le bouton nomme le
    montant (« Payer 49,50 USD »), demande une pression délibérée (jamais
    déclenchée par un Entrée involontaire sur un bouton focalisé par défaut), est
    sûr en simple pression et au rechargement (aucun double débit sous
    rafraîchissement, bouton retour ou nouvelle tentative réseau), et montre une
    progression pendant le traitement avec une mention « ne fermez pas cette
    page ».
  - **Annuler** — toujours disponible, jamais destructif ; la pré-réservation est
    laissée intacte pour le temps qu'il lui reste.
- États, chacun indiquant **si un montant a été prélevé** :
  - Prix indisponible pour le palier du siège → expliquer, ramener en arrière, ne
    pas laisser l'achat se poursuivre. *(pas de débit)*
  - Pré-réservation expirée avant la confirmation → achat désactivé, message,
    proposer de re-réserver ou de revenir. *(pas de débit)*
  - Siège devenu indisponible / événement qui a cessé de vendre au moment de la
    confirmation → message précis, retour au parcours. *(pas de débit)*
  - Paiement refusé → « Votre paiement a été refusé », siège et pré-réservation
    inchangés, rappeler le montant exact, proposer de réessayer. *(pas de débit)*
  - Client devenu inéligible (limite de réservations actives atteinte ailleurs) →
    expliquer la limite. *(pas de débit)*
  - Service en aval indisponible (réservation / paiement / tarification) → « Nous
    n'avons pas pu finaliser votre achat, aucun montant n'a été prélevé »,
    proposer de réessayer ; distinguer « rien ne s'est passé » de « issue
    incertaine ». *(pas de débit)*
  - Réseau perdu pendant la confirmation → l'écran ne présume pas l'échec ; il
    indique que l'issue est inconnue et, au rechargement, montre l'état réel — un
    achat abouti est montré comme fait, jamais reproposé. *(issue montrée
    fidèlement)*
- Après le succès :
  - Afficher le **billet** et le **reçu** : le montant réellement débité (qui
    doit être égal au montant affiché avant la confirmation — toute différence
    est signalée, pas masquée), la devise, une référence de reçu et une référence
    de réservation.
  - Proposer de télécharger / enregistrer le reçu ; également accessible plus
    tard depuis « Mes réservations ».
  - Chemins clairs vers « Mes réservations » et vers l'achat d'une autre place.
  - Indiquer explicitement que la réservation peut être annulée pour un
    **remboursement intégral**, et où.

**5. Mes réservations** (authentifié)

- Objectif : voir ses réservations en cours et passées, récupérer un reçu,
  annuler pour un remboursement.
- Affiché : chaque réservation avec son événement, son siège, son statut
  (confirmée / annulée), le montant payé, les références de reçu et de
  réservation.
- Actions : consulter / re-télécharger un reçu ; annuler une réservation
  confirmée.
- Flux d'annulation :
  - Une étape de confirmation qui énonce la conséquence : le siège est libéré et
    le montant intégral est remboursé sur le moyen de paiement d'origine.
  - En cas de succès : afficher la confirmation de remboursement (montant,
    référence) et le nouveau statut « annulée » de la réservation.
  - Idempotent pour le client : annuler une réservation déjà annulée, ou
    double-soumettre, ne produit jamais un second remboursement et n'affiche
    jamais d'erreur inquiétante — cela affiche « déjà annulée » calmement.
  - Service indisponible → « Nous n'avons pas pu annuler pour l'instant, rien n'a
    changé », proposer de réessayer.

### Décisions d'IHM transverses

- **Fraîcheur** : les écrans alimentés par des projections en retard affichent
  « à jour au <heure> » et un rafraîchissement manuel ; les étapes critiques pour
  la correction (pré-réserver, acheter, annuler) agissent sur le résultat faisant
  autorité et traitent la vue projetée comme un simple indice.
- **Retour confirmé, pas optimiste** : les actions qui changent l'état montrent
  un état « en cours » et ne signalent le succès qu'une fois l'API confirmée —
  jamais un succès optimiste susceptible d'être repris.
- **Comptes à rebours** : le compte à rebours de la pré-réservation est visible
  sur tout écran où une pré-réservation vivante compte ; à l'expiration, l'IHM
  change d'état plutôt que de laisser le client agir sur une pré-réservation
  morte.
- **Reçus** : disponibles immédiatement après l'achat et en permanence depuis
  « Mes réservations » ; le client n'a jamais à garder un onglet ouvert pour
  conserver une preuve.
- **Aucun identifiant technique** n'est montré au client ni demandé (pas
  d'identifiant client, pas d'identifiant de claim, pas de code de statut brut) ;
  les références montrées (réservation, reçu) sont celles qu'un conseiller
  support demanderait.
- **Montants** : toujours dans la devise de l'événement, formatés selon la
  locale, affichés avant le débit, jamais augmentés sans une nouvelle
  confirmation explicite.

### Exigences non fonctionnelles — IHM côté client

- **Sécurité** :
  - L'application ne détient aucun secret durable ni donnée de carte ; les
    données de carte ne sont saisies que sur la surface sécurisée du prestataire
    de paiement, jamais dans l'application.
  - L'API est le seul point d'application des contrôles d'accès et des règles
    métier ; les vérifications de l'IHM sont du confort et sont supposées
    contournables.
  - Aucun jeton OIDC n'est jamais dans le navigateur : l'application ne détient
    qu'un cookie de session opaque `HttpOnly` `Secure` `SameSite` émis par le
    BFF, de sorte qu'un XSS ne peut pas exfiltrer d'identifiant portable. Le
    rafraîchissement des jetons se fait côté serveur dans le BFF. Une
    ré-authentification renforcée (step-up) avant l'étape d'achat est acceptable
    si nécessaire.
  - L'application sert une **Content-Security-Policy stricte** (pas de
    `unsafe-inline` / `unsafe-eval` ; sources explicitement listées) et les
    en-têtes de sécurité standard (HSTS, `nosniff`, `frame-ancestors 'none'`,
    `Referrer-Policy`).
  - `localStorage` / `sessionStorage` ne sont jamais utilisés pour quoi que ce
    soit lié à l'identité ; la session est le cookie et rien d'autre.
  - Tout le trafic en TLS ; l'application refuse de fonctionner en clair.
  - Seules les données client dont un écran a besoin sont récupérées ; rien de
    sensible n'est écrit dans un stockage local durable.
- **Accessibilité — cible WCAG 2.1 AA** :
  - Entièrement utilisable au clavier seul, dans un ordre logique, avec un
    indicateur de focus visible.
  - Chaque changement d'état (prix chargé, pré-réservation expirée, erreur,
    succès) est annoncé aux technologies d'assistance et jamais véhiculé par la
    seule couleur ou position.
  - Le compte à rebours de la pré-réservation est annoncé à des seuils
    significatifs, pas à chaque tic.
  - Le contraste, l'agrandissement du texte à 200 % et la préférence
    « mouvement réduit » sont respectés.
  - Les champs de formulaire sont étiquetés (pas par un texte d'invite), et
    chaque erreur est reliée à son champ.
- **UX et résilience** :
  - Aucun code d'erreur brut, aucune trace de pile, aucune page blanche — chaque
    échec a un message clair et une étape suivante.
  - Chaque état d'écran indique clairement si un montant a été prélevé : « non
    prélevé », « prélevé — voici votre reçu », ou « issue inconnue — vérifiez Mes
    réservations ».
  - Les actions qui débitent ou qui sont destructives exigent une confirmation
    délibérée et clairement libellée ; rien d'irréversible ne se produit sur une
    seule frappe accidentelle.
  - Les actions sont sûres à réessayer : rafraîchissement, bouton retour et
    nouvelles tentatives réseau ne produisent jamais de double pré-réservation,
    double débit ou double remboursement.
  - Fonctionne sur un téléphone comme sur un poste de bureau ; le parcours
    principal (parcourir → pré-réserver → acheter) est utilisable à une main sur
    petit écran.
  - Performance perçue : premier affichage utile rapide sur un téléphone milieu
    de gamme via un réseau de classe 3G ; retour d'interaction sous ~100 ms ; un
    appel d'API lent montre une progression sous ~1 s.
  - Navigateurs supportés : versions majeures courante et précédente des
    principaux navigateurs evergreen ; sinon un message clair « navigateur non
    supporté ».
  - Prêt pour l'internationalisation : tout le texte visible externalisé ; date,
    heure et montants formatés selon la locale ; la mise en page tolère des
    chaînes traduites plus longues.

### Critères de succès — IHM côté client

- Un visiteur anonyme peut trouver un événement, voir la disponibilité et le prix
  réels, puis se connecter et atteindre l'écran de pré-réservation sans perdre sa
  place.
- Un client peut pré-réserver un siège, suivre le compte à rebours, l'acheter, et
  voir un billet et un reçu dont le montant est égal à celui affiché avant
  confirmation — sans jamais saisir d'identifiant technique.
- Si un autre client prend le siège entre la consultation et l'achat, l'écran le
  dit clairement et ramène à la sélection, sans rien débiter.
- Un paiement refusé, une pré-réservation expirée et une panne d'un service en
  aval produisent chacun un message clair indiquant qu'aucun montant n'a été
  prélevé et proposant une étape suivante.
- Perdre le réseau pendant la confirmation ne produit jamais de double débit ; au
  rechargement, l'issue réelle est montrée.
- Annuler une réservation affiche une confirmation de remboursement ; répéter
  l'annulation affiche « déjà annulée » sans second remboursement.
- L'ensemble du parcours d'achat est réalisable au clavier seul et passe un audit
  WCAG 2.1 AA.
- Un client connecté ne peut pas atteindre un écran organisateur ou opérateur ;
  une tentative d'URL directe affiche « vous n'avez pas accès ».
- Une session expirée est rafraîchie par le BFF sans que le client s'en
  aperçoive quand c'est possible ; quand une nouvelle connexion est nécessaire,
  le client revient sur le même écran avec sa sélection et toute pré-réservation
  vivante intactes.
- L'IHM livrée sert une Content-Security-Policy stricte et les en-têtes de
  sécurité standard, et ne détient aucun jeton OIDC — seulement un cookie de
  session opaque émis par le BFF.

## Contraintes non fonctionnelles

- **Runtime / pile** : Java 25 (LTS), Spring Boot 4+, PostgreSQL 18+.
- **Style d'architecture** : un service Spring Boot **en couches** classique —
  contrôleurs HTTP → services applicatifs (la frontière transactionnelle) →
  dépôts d'accès aux données. DTO à la frontière HTTP, types domaine à
  l'intérieur. Rien qu'un développeur Java courant devrait apprendre pour lire
  le code : pas de réactif / WebFlux, pas d'event sourcing, pas de CQRS déployé
  en services séparés, pas de cérémonie hexagonale / ports-adapters, pas de
  génération de code ni de magie d'annotation-processor. Organisation
  *package-by-feature* acceptée ; la forme interne reste en couches.
- **Authentification et identité** : déléguées à un IdP externe conforme OIDC
  (Keycloak). L'application navigateur s'authentifie via un **backend-for-frontend
  (BFF)** intégré au module backend : le flow Authorization Code + PKCE s'exécute
  côté serveur, les jetons OIDC restent côté serveur, et le navigateur ne
  détient qu'un cookie de session opaque `HttpOnly` `Secure` `SameSite` — **aucun
  jeton dans le navigateur**. Le backend valide les jetons et en dérive
  l'identité et les rôles à partir des claims ; il n'implémente ni écran de
  login, ni inscription, ni gestion de mot de passe. L'état de session est tenu
  en mémoire (MVP mono-instance), remplaçable par un store externe (p. ex.
  Redis) au scale-out. Un profil de test peut substituer un principal fondé sur
  des en-têtes pour l'exécution locale et les tests. Tout le trafic en TLS.
- **Garde-fou de persistance** : toutes les transitions d'état sont du SQL gardé
  (requête conditionnelle) écrit à la main, en une seule instruction (pas
  d'ORM / JPA) ; les changements de schéma sont des migrations versionnées.
- **Transactions** : une frontière transactionnelle explicite par cas d'usage,
  au niveau service ; les écritures gardées à une seule instruction sont le
  mécanisme de concurrence — pas de verrous applicatifs, pas de boucles de retry
  `SERIALIZABLE`.
- **Déploiement des modèles de lecture** : les projections disponibilité et
  tarif client sont des tables **de la même base et de la même application**,
  tenues à jour par des écouteurs d'événements in-process (ou un outbox
  transactionnel) — pas un service, un broker ni un datastore séparés.
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
- **Observabilité** : logs structurés avec un identifiant de corrélation par
  requête ; endpoints health / readiness ; métriques de base (Micrometer).
  Suffisant pour exploiter — aucun produit d'APM imposé.
- **Build et exécution** : un seul module Maven, un seul jar déployable, un seul
  processus. Aucune hypothèse d'orchestration au-delà de « un conteneur et un
  PostgreSQL ».
- **Tests** : chaque cas d'usage teste unitairement le chemin nominal et chaque
  échec typé ; des tests d'intégration couvrent le SQL gardé et les projections
  contre un vrai PostgreSQL ; un test de concurrence prouve un seul gagnant par
  siège.
- **Sécurité** — le service doit garantir, et une revue avant release doit
  confirmer :
  - **Accès aux objets (anti-IDOR, garanti)** : toute référence à une
    réservation, une pré-réservation, un reçu est vérifiée contre l'identité
    authentifiée (le `sub` du jeton) et/ou le rôle requis, dans la même
    transaction que l'accès. Un identifiant valide appartenant à un autre client
    ne renvoie jamais ses données (`403` / `404`, jamais le contenu).
  - **Injection SQL** : 100 % du SQL est paramétré ; aucune requête n'est
    construite par concaténation ou interpolation de données de requête ; un
    fragment dynamique (colonne, ordre de tri) passe par une liste blanche codée
    en dur.
  - **Sur-affectation (mass assignment)** : les DTO de requête sont explicites ;
    les champs contrôlés par le serveur (identité, prix, palier, statut,
    horodatages, versions) ne sont jamais lus depuis le corps de la requête.
  - **XSS** : toute sortie est encodée selon son contexte ; aucune donnée non
    fiable n'est rendue en HTML / JS brut.
  - **CSRF** : la session du BFF est portée par un cookie, donc toute requête
    modifiant l'état exige une protection CSRF — jetons CSRF de Spring Security +
    `SameSite` sur le cookie de session (`Strict` pour les endpoints sensibles).
  - **En-têtes** : HSTS, `X-Content-Type-Options: nosniff`, refus d'iframe, CORS
    minimal et explicite. La CSP stricte est une exigence de l'IHM (voir la
    section Interfaces utilisateur).
  - **Secrets** : jamais en code, en logs ni côté client ; injectés par
    configuration uniquement.
  - **Dépendances** : analyse des CVE connues au build ; aucune dépendance avec
    une vulnérabilité critique non traitée à la release.
  - **Compte base de données** : l'application se connecte avec un compte à
    privilèges minimaux — DML sur ses propres tables, pas de DDL en runtime, pas
    de superuser.

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
- Une requête dont le jeton OIDC porte le rôle requis est autorisée ; une
  requête dont le jeton n'a pas ce rôle est refusée en « permission refusée » ;
  un jeton absent ou expiré est traité comme public (ou refusé, pour une
  opération protégée) — jamais comme une erreur serveur.
- Une revue de sécurité avant release contre **OWASP ASVS niveau 1** (ou l'OWASP
  Top 10) ne laisse aucun finding critique ouvert : un identifiant valide d'une
  réservation d'autrui ne renvoie jamais ses données ; aucune entrée ne peut
  altérer une requête SQL ; les en-têtes de sécurité (et la CSP côté IHM) sont
  présents et stricts.
- Aucun chemin d'échec connu ne renvoie une erreur serveur générique.

## Risques, hypothèses et questions ouvertes

- **Hypothèse** : Keycloak (ou un autre fournisseur conforme OIDC) est exploité
  séparément et disponible ; la configuration realm/client — redirect URIs, les
  noms de rôle / groupe qui donnent admin et opérateur, les durées de vie des
  jetons — est provisionnée au déploiement, pas par cette application.
- **Tranché — liaison d'identité** : l'achat et l'annulation agissent sur
  l'identité du principal authentifié (le `sub` du jeton OIDC), jamais sur une
  valeur de client passée dans le corps de la requête. Ceci remplace la question
  ouverte laissée par la rétro-spécification source.
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
