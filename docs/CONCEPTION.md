# FinanceApp — Conception (étapes 1 à 10)

> Nom provisoire : **FinanceApp**. Le nom d'affichage est défini à un seul
> endroit : `app.name` dans
> `financeapp-desktop/src/main/resources/application.properties`. Le nom du
> dossier de données (`app.id`) est volontairement distinct : renommer le
> produit ne déplace pas les données déjà enregistrées.

Ce document précède le code. Il fixe le périmètre du MVP, les choix
techniques et leurs justifications, le schéma SQLite et l'ordre de
développement.

---

## 1. Reformulation du besoin

Une application **desktop, locale, hors-ligne** de gestion financière
personnelle. Ce n'est ni une banque ni un agrégateur : l'utilisateur saisit
(puis, plus tard, importe) ses opérations. La question centrale :

> « Combien ai-je **réellement** de disponible, une fois toutes mes dépenses
> futures prises en compte ? »

Le produit doit donc savoir, à tout instant :

- ce que l'utilisateur **a** (solde réel par compte, patrimoine) ;
- ce qui **va encore être débité/crédité** (opérations prévues, récurrentes) ;
- ce qui est **réellement disponible** jusqu'à une échéance donnée, avec le
  détail de chaque déduction ;
- comment le solde **va évoluer** (prévision).

## 2. Analyse des fonctionnalités

| Fonctionnalité | Valeur | Complexité | Version |
|---|---|---|---|
| Comptes multiples, soldes | Fondation | Faible | MVP |
| Transactions (revenu/dépense/virement, statuts) | Fondation | Moyenne | MVP |
| Catégories + sous-catégories par défaut / perso | Fondation | Faible | MVP |
| Opérations récurrentes (occurrences virtuelles) | Clé du « disponible réel » | Moyenne | MVP |
| Disponible réel explicable | **Cœur produit** | Moyenne | MVP |
| Prévision 30 jours (réel vs prévu) | Cœur produit | Moyenne | MVP |
| Dashboard | Usage quotidien | Moyenne | MVP |
| Sauvegarde locale + restauration + rotation | Sécurité des données | Moyenne | MVP |
| Mode confidentialité (masquage des montants) | Faible coût, fort usage | Faible | MVP (bonus) |
| Budgets, calendrier, épargne, abonnements, stats, recherche | Pilotage | Moyenne | **V2 — livrée** (section 12) |
| Import CSV, règles de catégorisation, Inbox, doublons | Gain de temps | Élevée | V3 |
| Crédits, amortissement, simulations *What If* | Décision | Élevée | V4 |
| Synchronisation bancaire (DSP2 via prestataire agréé) | Confort | Très élevée + réglementaire | V5 (étude) |
| Mot de passe maître + chiffrement base | Confidentialité | Élevée | **V1.1 — livrée** (section 11) |

Points de modélisation déterminants dès le MVP (coûteux à changer ensuite) :

1. **Montants signés dans la perspective du compte** + type explicite :
   les virements internes sont exclus des revenus/dépenses par le *type*,
   pas par une convention de signe fragile.
2. **Virement = deux lignes liées** (`transfer_group`) : le solde d'un compte
   reste une simple somme de ses lignes, et l'impact patrimoine est
   mécaniquement nul.
3. **Récurrences non matérialisées** : une règle génère des occurrences à la
   volée ; seule une occurrence validée (ou ignorée) devient une ligne en
   base, liée par `(recurring_id, occurrence_date)`.
4. **Argent en entiers (centimes) en base, `BigDecimal` en mémoire.**

## 3. Architecture

Application **monolithique modulaire**, locale. Pas de serveur, pas de
microservices : rien ne le justifie.

```
┌───────────────────────── financeapp-desktop ─────────────────────────┐
│  JavaFX UI (pages, dialogues, CSS)      Spring Boot (DI, config)      │
│  ui.* ──► services du core (via beans)                                │
└───────────────┬──────────────────────────────────────┬───────────────┘
                │                                      │
┌───────────────▼──────────── financeapp-core ─────────▼───────────────┐
│  Domaine (records immuables, Money, enums)                            │
│  Moteurs purs : RecurrenceEngine, AvailableBalanceEngine,             │
│                 ForecastEngine                                        │
│  Services applicatifs : AccountService, TransactionService, …         │
│  Ports (interfaces) : AccountRepository, TransactionRepository, …     │
│  ► AUCUNE dépendance : ni Spring, ni JavaFX, ni JDBC                  │
└───────────────▲──────────────────────────────────────────────────────┘
                │ implémente les ports
┌───────────────┴──────────── financeapp-infra ────────────────────────┐
│  SQLite (xerial sqlite-jdbc) + Spring JDBC (JdbcClient)               │
│  Flyway (migrations), BackupService, AppDirectories                   │
└──────────────────────────────────────────────────────────────────────┘
```

- **Le domaine ne dépend pas de JavaFX** (garanti par le build : le module
  `core` n'a aucune dépendance de compilation).
- Les calculs financiers sont testables en JUnit pur, avec une `Clock`
  injectée et des dépôts en mémoire.
- Chaque moteur est une classe **sans état ni I/O** : entrées → résultat.
  Les services font l'orchestration (lecture des dépôts, appel du moteur).

### Choix techniques justifiés

| Choix | Décision | Justification |
|---|---|---|
| Java 21 | ✅ | LTS, records, `switch` exhaustif, pattern matching. |
| Spring Boot 4.1 (sans web) | ✅ *core seulement* | Injection de dépendances, configuration externalisée (`app.name`…), cycle de vie propre. Pas de starter web, pas d'auto-config DataSource/Flyway : la base SQLite est configurée explicitement (pragmas, chemin calculé au démarrage, restauration en attente appliquée **avant** l'ouverture). Coût : ~1 s de démarrage, acceptable. |
| Spring Data JPA / Hibernate | ❌ | SQLite n'a qu'un dialecte communautaire ; `BigDecimal` y serait stocké en `REAL` (flottant) sans précaution ; les requêtes utiles ici sont des agrégations SQL (`SUM` par compte/mois) plus lisibles en SQL ; démarrage et mémoire plus lourds. |
| Spring JDBC (`JdbcClient`) | ✅ | SQL explicite, mapping manuel maîtrisé (centimes ↔ `BigDecimal`), transactions via `TransactionTemplate` pour les virements. |
| SQLite (xerial) | ✅ | Fichier unique, zéro installation, WAL pour lecture concurrente, `VACUUM INTO` pour des sauvegardes cohérentes à chaud. |
| Flyway | ✅ | Migrations versionnées indispensables pour une base utilisateur qui vivra des années. SQLite supporté par `flyway-core`. |
| JavaFX 21 LTS | ✅ | Aligné sur Java 21. Graphiques natifs (`LineChart`, `PieChart`, `BarChart`) suffisants pour le MVP. |
| ControlsFX | ❌ (pour l'instant) | Aucun besoin MVP que JavaFX ne couvre pas. Réévalué si besoin (ex. `CheckComboBox` pour les filtres V2). |
| FXML | ❌ | Vues construites en code : refactoring sûr, pas de réflexion, cohérent avec le reste du dépôt. |
| Argon2id | ✅ V1.1 | Via Bouncy Castle (`Argon2BytesGenerator`), jamais d'implémentation maison. m = 64 Mio, t = 3, p = 1, sel 16 o aléatoire, clé 32 o (~0,3 s). |
| Chiffrement base | ✅ V1.1 | `io.github.willena:sqlite-jdbc` (fork de xerial embarquant SQLite3MultipleCiphers, natifs Windows/macOS/Linux), format SQLCipher v4 (AES-256-CBC + HMAC-SHA512 par page). Alternative étudiée : chiffrer le fichier entier au repos (AES-GCM via JCA) — rejetée : fenêtre en clair sur disque pendant l'usage. |

## 4. Risques techniques

| Risque | Mitigation |
|---|---|
| Erreurs d'arrondi monétaire | `BigDecimal` exclusivement ; stockage en **centimes (`INTEGER`)** ; échelle = `Currency.getDefaultFractionDigits()` ; arrondi `HALF_EVEN` centralisé dans `Money`. Tests dédiés. |
| Double comptage des virements | Type `TRANSFER` exclu des revenus/dépenses ; deux lignes atomiques (transaction SQL). |
| Explosion de lignes par les récurrences | Occurrences calculées à la volée sur une fenêtre bornée ; seules les occurrences validées sont persistées. |
| Dérive des dates mensuelles (31 → 28 → 28…) | Occurrence *n* calculée depuis la date de départ (`start.plusMonths(n)`), jamais depuis l'occurrence précédente. |
| Corruption / perte de la base | WAL + `busy_timeout` ; sauvegardes par `VACUUM INTO` vérifiées (`PRAGMA integrity_check`) ; noms horodatés (jamais d'écrasement) ; rotation qui ne supprime qu'**après** vérification de la nouvelle copie ; copie de sécurité avant toute restauration. |
| Restauration pendant que la base est ouverte | Restauration **différée** : fichier validé puis mis en attente, appliqué au prochain démarrage avant l'ouverture de la base. |
| Suppression détruisant l'historique | Comptes et catégories **archivés**, jamais supprimés s'ils sont utilisés (`ON DELETE RESTRICT`). |
| Fuite de données dans les logs | Aucun montant ni libellé logué ; logs techniques uniquement (identifiants, durées, erreurs). |
| UI figée | Calculs (disponible, prévision) exécutés hors thread JavaFX via `UiAsync`. `TableView` virtualisée. |
| Multi-devises | MVP : une devise de référence (EUR par défaut) ; les comptes dans une autre devise sont affichés mais exclus des totaux (signalé à l'écran). Pas de conversion implicite. |
| Démarrage Spring + JavaFX | Contexte Spring démarré dans `Application.init()`, lanceur séparé (`Launcher`) pour fonctionner hors module-path (jar/jpackage). |

## 5. Arborescence Maven

```
financeapp/
├── pom.xml                          (parent : versions, plugins)
├── run.sh                           (build + lancement)
├── docs/CONCEPTION.md
├── financeapp-core/                 (Java pur)
│   └── src/main/java/com/financeapp/core/
│       ├── money/         Money
│       ├── account/       Account, AccountType
│       ├── category/      Category, CategoryKind
│       ├── transaction/   Transaction, TransactionType, TransactionStatus
│       ├── recurring/     RecurringRule, Frequency, RecurrenceEngine
│       ├── planning/      PlannedItem (opération à venir unifiée)
│       ├── available/     AvailableBalanceEngine, Horizon, résultat explicable
│       ├── forecast/      ForecastEngine, Forecast, ForecastPoint
│       ├── dashboard/     DashboardSummary
│       ├── settings/      Settings (clés typées)
│       ├── port/          interfaces de dépôts
│       └── service/       services applicatifs
├── financeapp-infra/
│   └── src/main/
│       ├── java/com/financeapp/infra/
│       │   ├── db/        SqliteDataSourceFactory, DatabaseMigrator, Jdbc*Repository
│       │   ├── backup/    BackupService, BackupFile
│       │   └── storage/   AppDirectories
│       └── resources/db/migration/  V1__schema.sql, V2__default_categories.sql
└── financeapp-desktop/
    └── src/main/
        ├── java/com/financeapp/desktop/
        │   ├── Launcher, FinanceFxApplication, AppProperties, AppConfiguration
        │   └── ui/  MainWindow, pages/*, dialogs/*, common/*
        └── resources/  application.properties, logback-spring.xml, css/theme.css
```

## 6. Schéma SQLite (V1)

Conventions : dates ISO-8601 en `TEXT` (`yyyy-MM-dd`, tri lexical = tri
chronologique), montants en **centimes signés** (`INTEGER`), booléens
`INTEGER 0/1`, clés `INTEGER PRIMARY KEY`, `PRAGMA foreign_keys = ON`.

```sql
accounts(id, name, type, currency, initial_balance_minor, opening_date,
         icon, color, include_in_available, archived, sort_order,
         created_at, updated_at)

categories(id, parent_id → categories, name, kind [EXPENSE|INCOME|BOTH],
           icon, color, archived, sort_order, system_code UNIQUE)
  -- unicité (parent, nom) insensible à la casse

recurring_transactions(id, account_id → accounts, to_account_id → accounts,
           type, label, amount_minor (>0), category_id → categories,
           frequency, interval_count, start_date, end_date, tracked_from, certain, active,
           note, created_at, updated_at)

transactions(id, account_id → accounts, date, label, amount_minor (signé),
             type, status, category_id → categories, note,
             transfer_group, recurring_id → recurring_transactions,
             occurrence_date, created_at, updated_at)
  -- CHECK cohérence signe/type ; UNIQUE (recurring_id, occurrence_date, account_id)
  -- index : (account_id, date), (date), (category_id), (status, date), (transfer_group)

settings(key PRIMARY KEY, value)
```

Tables prévues ensuite (migrations dédiées, **pas créées tant qu'inutiles**) :
`budgets`, `savings_goals`, `subscriptions` (V2), `tags`, `transaction_tags`
(V2), `categorization_rules`, `import_batches` (V3), `loans` (V4).

Suppression : `ON DELETE RESTRICT` sur comptes et catégories (on archive) ;
`ON DELETE SET NULL` sur `recurring_id` (supprimer une règle conserve
l'historique des occurrences validées).

## 7. Modèles du MVP

| Modèle | Rôle |
|---|---|
| `Money` | Montant + devise, `BigDecimal` à l'échelle de la devise, `HALF_EVEN`. |
| `Account` / `AccountType` | Compte, type (courant, joint, livret, épargne, espèces, prépayée, autre), inclus ou non dans le disponible. |
| `Category` / `CategoryKind` | Arbre à deux niveaux, catégories système + personnelles. |
| `Transaction` / `TransactionType` / `TransactionStatus` | Ligne d'un compte. `status.countsInBalance()` : effectué + en attente. |
| `RecurringRule` / `Frequency` | Règle de récurrence (hebdo, 2 sem., mensuelle, trimestrielle, annuelle, personnalisée = tous les *n* jours/semaines/mois). |
| `PlannedItem` | Opération à venir unifiée : transaction prévue **ou** occurrence de récurrence. |
| `AvailableBalanceResult` | Résultat explicable : lignes groupées (solde, dépenses, revenus, épargne, réservations). |
| `Forecast` / `ForecastPoint` | Série datée, drapeau réel/prévu, point bas. |
| `DashboardSummary` | Indicateurs du tableau de bord. |

Définitions retenues :

- **Solde actuel** d'un compte = solde initial + Σ lignes *effectuées* et
  *en attente* (une opération en attente est déjà engagée).
- **Opérations à venir** = transactions *prévues* (y compris en retard) +
  occurrences de récurrences non encore validées ni ignorées.
- **Disponible réel** (comptes inclus dans le disponible — par défaut
  courants, joints, espèces, prépayées) :

  ```
  solde actuel
  − dépenses prévues jusqu'à l'échéance
  − virements prévus vers l'épargne (comptes hors périmètre)
  − réservations (budgets restants, objectifs d'épargne — V2)
  + revenus prévus jugés certains (option)
  = disponible réel
  ```

  Une règle récurrente porte une date de suivi (`tracked_from`, par défaut
  sa date de création) : une règle « salaire le 28 » créée le 29 ne fait pas
  apparaître le salaire de la veille comme un revenu à venir. Une occurrence
  non validée reste « en retard » (et comptée) pendant 14 jours au plus.

  Échéances : fin de semaine, prochaine paie (prochaine occurrence du plus
  gros revenu récurrent — revenu exclu, dépenses de la veille incluses),
  fin du mois, date personnalisée.

## 8. Services métier

| Service | Responsabilités |
|---|---|
| `AccountService` | Créer/modifier/archiver, soldes (`balanceOf`, `balances`). |
| `CategoryService` | Arbre, création, renommage, archivage, suppression si inutilisée. |
| `TransactionService` | Créer/modifier/supprimer, **virements atomiques**, validation (signe, statut). |
| `RecurringService` | CRUD règles, occurrences à venir, **valider** / **ignorer** une occurrence. |
| `PlanningService` | Liste unifiée des `PlannedItem` sur une période. |
| `AvailableBalanceService` | Résout l'échéance, rassemble les entrées, appelle `AvailableBalanceEngine`. |
| `ForecastService` | Historique réel + projection via `ForecastEngine`. |
| `DashboardService` | Agrège les indicateurs. |
| `SettingsService` | Paramètres typés (devise, échéance par défaut, sauvegarde auto…). |
| `BackupService` (infra) | Export, sauvegarde auto avec rotation, validation, restauration différée. |

## 9. Design de l'interface

- **Thème sombre** par défaut, palette sobre (fond `#11151c`, cartes
  `#1a2029`, accent bleu `#4c8dff`, positif vert `#3ecf8e`, négatif
  corail `#ff6b6b`, attention ambre `#f5b942`). Les états ne sont jamais
  communiqués par la seule couleur : signe `+`/`−`, libellés, icônes.
- **Navigation latérale** : Tableau de bord · Comptes · Transactions · À venir
  · Récurrences · Disponible · Prévisions · Catégories · Paramètres. Les
  entrées V2+ (Budgets, Calendrier…) s'ajouteront au même endroit.
- **Tableau de bord** : rangée de cartes (Patrimoine, Comptes courants,
  Épargne, **Disponible réel** cliquable, Revenus du mois, Dépenses du mois,
  À venir), graphique de prévision 30 jours, liste des prochaines opérations,
  dernières transactions. Rien de plus.
- **Disponible réel** : sélecteur d'échéance, montant en grand, détail ligne
  à ligne de chaque déduction.
- **Prévisions** : `LineChart`, série « réel » (pleine) et « prévu »
  (pointillée), 7 j / 30 j / 90 j, point bas signalé.
- **Mode confidentialité** : `Ctrl+M` (ou bouton) remplace tous les
  montants par `•••••• €`.
- **Raccourcis** : `Ctrl+N` nouvelle transaction, `Ctrl+1…9` navigation.

## 10. Étapes du MVP

| # | Étape | Livrable vérifiable |
|---|---|---|
| 1 | Squelette Maven 3 modules, `Money` + tests d'arrondi | `mvn test` vert |
| 2 | Modèles domaine, `RecurrenceEngine` + tests | Occurrences fin de mois, bornes |
| 3 | `AvailableBalanceEngine` + tests scénarios | Scénario 1000/1800/700/200/300 |
| 4 | `ForecastEngine` + tests | Série, point bas, réel vs prévu |
| 5 | Ports + services + tests (dépôts mémoire), virements | Impact patrimoine nul |
| 6 | Infra SQLite : Flyway, dépôts JDBC, catégories par défaut + tests | Tests d'intégration sur fichier temporaire |
| 7 | Sauvegarde / rotation / restauration différée + tests | Rotation, fichier invalide refusé |
| 8 | Bootstrap Spring + JavaFX, thème, navigation | Fenêtre qui démarre |
| 9 | Pages Comptes, Transactions, Catégories | Saisie quotidienne possible |
| 10 | Pages Récurrences, À venir | Valider/ignorer une occurrence |
| 11 | Disponible réel, Prévisions, Tableau de bord | Réponse à la question centrale |
| 12 | Paramètres (sauvegardes, confidentialité), script de lancement | Utilisable au quotidien |

Après le MVP : V1.1 sécurité (section 11), puis V2 → V5 selon la roadmap du
brief. Packaging
Windows via `jpackage` (runtime embarqué, pas d'installation de Java).

---

## 11. V1.1 — Mot de passe maître et chiffrement

### Modèle de clés

```
mot de passe ──Argon2id(sel₁, 64 Mio, t=3)──► KEK ──AES-256-GCM──┐
clé de récupération ─Argon2id(sel₂)──────────► KEK' ─AES-256-GCM─┼──► DEK (256 bits aléatoires)
                                                                  │        │
                         data/keystore.properties ◄───────────────┘        ▼
                                                    base SQLCipher v4 (clé brute) + sauvegardes
```

- **La DEK n'est jamais dérivée du mot de passe.** Changer de mot de passe =
  ré-envelopper la DEK : pas de rechiffrement, toutes les sauvegardes restent
  lisibles.
- **Aucun mot de passe stocké**, ni en clair ni sous forme d'empreinte : c'est
  l'échec du tag GCM qui signale un mot de passe incorrect.
- Les enveloppes sont liées à leur usage et à l'identifiant de la clé (AAD) :
  un trousseau modifié est rejeté.
- **Clé de récupération** : 160 bits aléatoires en Base32 (`XXXX-XXXX-…`),
  affichée une seule fois ; régénérable (l'ancienne devient inutilisable).
- Paramètres Argon2 enregistrés dans le trousseau et **bornés à la lecture**
  (un trousseau forgé ne peut pas réclamer des Go de mémoire).
- Seules briques cryptographiques : Bouncy Castle (Argon2id), JCA (AES-GCM),
  SQLite3MultipleCiphers (SQLCipher). Aucun algorithme implémenté à la main.

### Cycle de vie

| Étape | Comportement |
|---|---|
| Démarrage | Aucun accès aux données avant le mot de passe. Contrôle que le pilote SQLite sait chiffrer (`sqlite3mc_version()`), sinon arrêt. |
| Premier lancement | Création du mot de passe (≥ 10 caractères, indicateur de robustesse textuel), puis affichage unique de la clé de récupération. |
| Données V1 en clair | Chiffrées à la création du mot de passe : base **et** anciennes sauvegardes. Copie cohérente → `rekey` en journal DELETE → vérification (intégrité, nombre d'objets, en-tête) → remplacement atomique. Le trousseau est écrit **avant** : une interruption reprend au déverrouillage suivant. |
| Déverrouillage | Argon2id hors thread JavaFX ; délai croissant (2 à 30 s) après 3 échecs. |
| Verrouillage | Bouton, `Ctrl+L` (depuis n'importe quelle fenêtre) ou inactivité (5 min par défaut, réglable, 0 = jamais). La DEK est remise à zéro en mémoire, la source de données refuse toute connexion, les dialogues ouverts sont fermés, les raccourcis sont neutralisés. |
| Trousseau perdu | Détecté (base chiffrée sans trousseau) ; import d'un fichier `.key` de sauvegarde. |

### Sauvegardes

Une sauvegarde = `nom.db` (copie chiffrée par `VACUUM INTO`, même clé) +
`nom.db.key` (copie du trousseau, sans secret en clair). Restaurer une
sauvegarde d'une autre installation demande le mot de passe en vigueur lors de
sa création ; son trousseau remplace alors le trousseau local. La copie de
sécurité avant restauration est une copie brute des fichiers chiffrés (aucune
clé nécessaire au démarrage).

### Limites connues

- Le pilote JDBC reçoit la clé sous forme de chaîne hexadécimale le temps
  d'ouvrir chaque connexion, et JavaFX fournit les mots de passe sous forme de
  `String` : ces copies ne peuvent pas être effacées explicitement en Java
  (les `byte[]`/`char[]` que l'application contrôle le sont).
- Pas d'effacement sécurisé des anciens fichiers en clair de la V1 (impossible
  à garantir, notamment sur SSD).
- Les journaux techniques démarrés avant le déverrouillage ne vont que sur la
  console.
- Mot de passe **et** clé de récupération perdus = données irrécupérables (par
  conception).

---

## 12. V2 — Pilotage

### Nouveaux modèles et tables (migration `V3__budgets_and_savings_goals.sql`)

| Modèle | Table | Points clés |
|---|---|---|
| `Budget` | `budgets` | Plafond mensuel d'une catégorie **et de ses sous-catégories** ; un seul budget actif par catégorie (index unique partiel) ; option « réserver dans le disponible ». |
| `SavingsGoal` | `savings_goals` | Montant visé, échéance facultative ; épargne suivie via le **solde d'un compte** (livret dédié) ou **à la main** (versements/retraits) ; option « réserver l'effort mensuel ». |

Les abonnements ne sont pas une table : ce sont les récurrences de dépense de la
catégorie *Abonnements* (`system_code = SUBSCRIPTIONS`) et de ses
sous-catégories. Les paiements détectés que l'utilisateur écarte sont mémorisés
dans `settings` (`subscriptions.dismissed`).

### Moteurs (Java pur, testés)

- **`BudgetEngine`** : progression (dépensé / limite, %, reste) et état
  `OK` / `Proche de la limite` (≥ 80 %) / `Atteint` / `Dépassé`, chacun avec un
  libellé et un symbole. **Réserve** pour le disponible réel : reste du mois
  courant *moins les dépenses déjà prévues dans la catégorie* (jamais comptées
  deux fois), au prorata des jours couverts par l'échéance, plus un prorata du
  budget des mois suivants si l'échéance les atteint.
- **`SavingsGoalCalculator`** : reste, %, mois restants (du mois suivant au mois
  de l'échéance inclus), épargne mensuelle nécessaire (exemple du brief :
  1 750 € sur 15 mois = 116,67 €), objectif atteint / échéance dépassée.
- **`RecurringPaymentDetector`** : libellé normalisé (casse, accents, chiffres,
  préfixes bancaires), intervalle stable (hebdomadaire → annuel), montant stable
  (± 15 %), non encore enregistré, non interrompu. Rien n'est créé sans
  validation.
- **`StatisticsEngine`** : bilans mensuels (revenus, dépenses, épargné, taux
  d'épargne), moyenne sur les mois complets, répartition par catégorie racine,
  comparaison de périodes **avec montants absolus et pourcentages** (pas de
  pourcentage depuis zéro).

### Intégration au disponible réel

`AvailableBalanceService` reçoit des `ReservationProvider` (budgets, objectifs).
Chaque réservation apparaît comme une ligne du détail. La ligne **« Si aucune
autre dépense variable »** (vue *Fin de mois* du brief) donne le disponible hors
réservations.

### Écrans ajoutés

Calendrier (grille mensuelle, solde réel puis prévu en fin de journée, détail
du jour) · Budgets · Épargne · Abonnements (coût mensuel **et annuel**,
détection) · Analyses (trajectoire du mois vs mois précédent, revenus/dépenses
sur 12 mois, catégories, comparaison) · Recherche avancée dans Transactions
(catégorie, période personnalisée, montant min./max., totaux calculés sur tous
les résultats : nombre, total dépensé, moyenne, revenus).

### Reporté

Étiquettes (tags) et leur filtre ; budgets non mensuels ; comparaison de
périodes arbitraires (V3, « analyses avancées »).

## 13. V3 — Import de relevés et catégorisation automatique

L'utilisateur télécharge lui-même son relevé depuis l'espace en ligne de sa
banque ; l'application ne se connecte à rien et ne demande aucun identifiant
bancaire. Le fichier est lu localement.

### Formats et lecture (Java pur, testé)

| Composant | Rôle |
|---|---|
| `CsvReader` | Détection de l'encodage (UTF-8 avec ou sans BOM, sinon Windows-1252) et du séparateur (`;` `,` tabulation `|`, le plus régulier sur les premières lignes) ; guillemets RFC 4180. |
| `CsvMappingGuesser` | Proposition de correspondance : lignes de préambule (« Téléchargement du… », « Compte courant… ») détectées par les mots-clés d'en-tête (Date, Libellé, Montant, Débit, Crédit…), sinon par la première ligne contenant une date lisible ; format de date détecté parmi `dd/MM/yyyy`, `yyyy-MM-dd`, `dd-MM-yyyy`, `dd.MM.yyyy`, `dd/MM/yy`, `yyyy/MM/dd`, `d/M/yyyy`, `MM/dd/yyyy` ; montant en une colonne signée ou en deux colonnes Débit / Crédit. |
| `CsvRowConverter`, `AmountText` | Conversion en `ImportedRow` ; montants « 1 234,56 », « -1.234,56 », « 1,234.56 », « (12,00) », « 12,5- », « 12,00 € ». Une ligne illisible devient une ligne **invalide** affichée avec son motif, jamais ignorée en silence. |
| `OfxParser` | OFX 1.x (SGML, balises non fermées) et 2.x (XML) ; `FITID` conservé comme identifiant bancaire. QFX = OFX. |
| `QifParser` | Blocs `D`/`T`/`P`/`M` terminés par `^` ; dates européennes puis américaines, apostrophe des années acceptée (`28/09'26`). |

### Rapprochement avant import (`ImportPlanner`)

Chaque ligne reçoit un statut, affiché en texte (jamais par la seule couleur) :

| Statut | Règle | Coché par défaut |
|---|---|---|
| Nouvelle | aucune correspondance | oui |
| Réalise une opération prévue | même montant exact, date à ± 5 jours (± 10 jours si le libellé confirme) | oui |
| Réalise une échéance récurrente | montant à ± 10 %, date à ± 5 jours (± 10 si libellé), même sens | oui |
| Déjà présente | même `FITID`, ou même montant à ± 3 jours avec libellé équivalent | **non** |
| Doublon possible | même montant à ± 3 jours, libellé différent | **non** |
| Deux fois dans le fichier | même date, montant et libellé normalisé | **non** |
| Illisible | date ou montant non reconnu | impossible |

Seules les opérations **effectuées ou en attente** peuvent être des doublons ;
les opérations prévues sont *réalisées* (statut effectué, date réelle), pas
dupliquées. Une occurrence récurrente réalisée crée une opération liée à
`(recurring_id, occurrence_date)` : l'échéance disparaît de « À venir » et du
disponible réel, qui ne compte donc jamais deux fois le loyer.

### Enregistrement et annulation

`ImportService.commit` enregistre en **une seule transaction SQL** le lot
(`import_batches`), les opérations créées (`import_batch_id`, `external_id`,
`needs_review = 1`) et les rapprochements avec l'**état précédent** de chaque
opération prévue (`import_reconciliations`). « Défaire cet import » supprime les
opérations du lot et restaure les opérations prévues ; le fichier peut ensuite
être réimporté. Un index unique `(account_id, external_id)` empêche d'importer
deux fois la même opération OFX.

Migration : `V4__imports_and_categorization_rules.sql`.

### Catégorisation automatique (`CategorizationEngine`, local)

1. **Règles** de l'utilisateur (`categorization_rules`) : « si le libellé
   contient *TOTAL* → Transport › Carburant », restreignable aux dépenses ou
   aux revenus. Comparaison sur le libellé normalisé (`LabelNormalizer` :
   casse, accents, chiffres, préfixes bancaires `CB`, `PRLV SEPA`…, noms de
   mois) et **sur des mots entiers** (« TOTAL » ne correspond pas à
   « TOTALEMENT »). Le motif le plus long l'emporte.
2. **Historique** : à défaut de règle, la catégorie utilisée pour le même
   libellé normalisé, si elle représente au moins 75 % d'au moins 2 opérations.
3. Opération prévue ou récurrente rapprochée : sa catégorie prime.

Quand l'utilisateur corrige une catégorie (Inbox ou Transactions), l'application
propose « Toujours classer les opérations contenant « X » dans « Y » ? » avec le
mot-clé significatif pré-rempli et modifiable. Aucune règle n'est créée sans
confirmation. « Appliquer aux opérations sans catégorie » ne modifie jamais une
catégorie déjà choisie.

### Inbox « À valider »

Les opérations importées comptent immédiatement dans les soldes ; seule leur
catégorie reste à confirmer. L'écran liste la catégorie proposée et son
origine (règle, historique, opération prévue), permet de corriger, de valider
une à une ou de valider en bloc celles qui ont une catégorie. Le nombre en
attente est affiché dans la navigation et sur le tableau de bord.

### Analyses avancées

- **Principaux commerçants** du mois : libellés regroupés par forme normalisée
  (nombre d'opérations, total, moyenne).
- **Comparer deux périodes quelconques** (ex. septembre 2025 vs septembre
  2026) par catégorie, avec montants absolus et pourcentages.

### Limites

- Pas de connexion bancaire (V5 : étude uniquement).
- Relevés multi-comptes dans un même fichier : importer compte par compte.
- Les règles portent sur le libellé uniquement (pas sur le montant).

## 14. V4 — Crédits, simulations « What If? », prévisions longues

Migration : `V5__loans_and_simulations.sql` (tables `loans`, `simulations`,
`simulation_items`).

### Crédits (`Loan`, `LoanCalculator`, `LoanService`)

- Données : nom, capital emprunté, taux nominal annuel (facultatif), durée en
  mois, date de la 1re mensualité, mensualité (facultative), assurance
  mensuelle, compte débité, catégorie. Le taux est stocké en texte décimal
  exact (`"4.35"`), les montants en centimes.
- **Tableau d'amortissement calculé, jamais stocké** : mensualités constantes,
  taux mensuel = taux annuel / 12, mensualité
  `C·t / (1 − (1 + t)^−n)` arrondie au centime, intérêts du mois = capital
  restant × t arrondis au centime (HALF_EVEN), la dernière mensualité solde
  l'écart d'arrondi. Valeurs de référence vérifiées par les tests :
  10 000 € à 5 % sur 12 mois → 856,07 € ; 200 000 € à 3 % sur 240 mois →
  1 109,20 € et 66 206,43 € d'intérêts.
- **Taux inconnu** : estimé par dichotomie à partir de la mensualité (affiché
  « taux estimé »). Une mensualité saisie incompatible avec le taux est refusée
  avec la mensualité calculée.
- Capital restant, part remboursée, prochaine échéance : d'après le calendrier
  du crédit à la date du jour (mensualités échues = remboursées).
- **Pas de double comptage** : les mensualités passent par une récurrence liée
  (créée par le crédit, ou une récurrence existante choisie par l'utilisateur,
  alignée alors sur le crédit : montant assurance comprise, dates). C'est elle
  qui alimente « À venir », le disponible réel et les prévisions ; elle s'arrête
  à la dernière mensualité. Une récurrence ne peut appuyer qu'un seul crédit
  (index unique). Supprimer un crédit conserve la récurrence et l'historique.

### Prévisions 12 / 24 / 48 mois

- Horizons ajoutés : 24 et 48 mois (graphique échantillonné au-delà de 400
  points, point bas conservé ; axe en mois).
- **Dépenses courantes estimées** (option, cochée par défaut sur l'écran
  Prévisions) : moyenne des dépenses effectuées des 3 derniers mois complets,
  hors récurrences, répartie jour par jour (chaque mois complet reçoit
  exactement le montant mensuel). Sont exclues les dépenses liées à une
  occurrence **et** celles qui ressemblent à une récurrence active du même
  compte (montant à ± 10 %, libellé équivalent) : un loyer passé saisi à la main
  ou importé n'est pas compté deux fois. Le tableau de bord garde la prévision
  « opérations connues uniquement ».

### Simulations « What If? » (`SimulationEngine`, `SimulationService`)

Hypothèses : dépense/rentrée ponctuelle, montant mensuel (durée facultative),
crédit (capital, taux et/ou mensualité, durée ; le capital est supposé versé au
vendeur), arrêt d'une récurrence existante à une date. Modèle « Achat financé à
crédit » : prix − apport = capital emprunté, apport ponctuel, frais mensuels
(assurance, carburant, entretien).

Le moteur est une **fonction pure** : il reçoit une copie des opérations à
venir, des soldes, de l'estimation des dépenses courantes et des objectifs, et
ne lit ni n'écrit rien d'autre. Les scénarios sont enregistrés dans leurs
propres tables, sans clé vers les transactions. Des tests vérifient que
transactions, récurrences, soldes et disponible réel sont identiques après
calcul.

Résultats, sur les N mois complets qui suivent le mois en cours (12, 24 ou 48) :

| Indicateur | Définition (moyenne mensuelle, comptes du disponible) |
|---|---|
| Revenus | revenus récurrents et prévus |
| Charges fixes | dépenses récurrentes, mensualités |
| Reste à vivre | revenus − charges fixes |
| Dépenses ponctuelles | opérations prévues ponctuelles (apport lissé sur la période) |
| Dépenses courantes | estimation ci-dessus |
| Épargne programmée | virements nets vers des comptes hors disponible |
| Disponible mensuel | reste à vivre − ponctuelles − courantes − épargne programmée |
| Capacité d'épargne | disponible + épargne programmée |

S'y ajoutent : l'impact moyen et le **mois type** (écart le plus fréquent,
hors lissage des dépenses ponctuelles), le détail mois par mois, la courbe du
solde prévu sans / avec (pointillés gris / trait plein), point bas et solde
final, le résumé des crédits simulés, et la comparaison de la capacité
d'épargne avec l'effort mensuel demandé par les objectifs en cours (« couvert »
ou « il manquerait X €/mois », en texte et symbole).

### Limites

- Le capital restant suit le calendrier du crédit, pas les paiements réels
  (remboursement anticipé : modifier le crédit).
- Taux fixe uniquement ; pas de différé ni de taux variable.
- Les simulations raisonnent sur les comptes inclus dans le disponible et dans
  la devise de référence.
