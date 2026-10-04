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

S'y ajoute `financeapp-banksync` (V5), **seul module qui accède au réseau** :
synchronisation bancaire facultative (Enable Banking) et liste publique des
versions (`GitHubReleaseFeed`), toutes deux désactivées par défaut. Il implémente
des ports du cœur (`BankSyncClientFactory`, `ReleaseFeed`).

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

État du MVP ; les ajouts ultérieurs sont listés après l'arborescence.

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

Ajouts depuis le MVP :

- `financeapp-core` : `budget/`, `goal/` (V2), `imports/`, `categorization/`,
  `stats/` (V3), `loan/`, `simulation/` (V4), `banksync/` (V5), `calendar/`,
  `subscription/`, `export/` (CSV), `tag/` (étiquettes), `update/` (nouvelles
  versions), `text/` ; `settings/MenuLayout` (barre latérale) ;
  `service/NetWorthService` (évolution du patrimoine), `attachment/` (justificatifs).
- `financeapp-infra` : `security/` (mot de passe maître, trousseau, V1.1) ;
  migrations `V3` à `V11` (budgets et objectifs, imports et règles, crédits et
  simulations, synchronisation, produits d'épargne, ventilation et étiquettes,
  récurrences ventilées, étiquettes des récurrences, justificatifs).
- `financeapp-banksync` : client Enable Banking (`EnableBankingClient`,
  `JwtSigner`, `PemKeys`) et `update/GitHubReleaseFeed`.
- `financeapp-desktop` : `ui/security/` (déverrouillage, profils),
  `theme-light.css` ; `packaging/` (jlink, jpackage, icône) et
  `.github/workflows/` (installateur, notes de release) à la racine.

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

- **Thème sombre « Nuit & Saphir »** par défaut (fond `#12161f`, cartes
  `#1a2030`, accent saphir `#6e9bff`, positif `#5dd39e`, négatif
  `#ff8a80`, attention `#f4b860`) et **thème clair « Lin & Prune »**
  (fond `#f6f3ee`, cartes blanches, accent prune `#6b3f7a`, corail
  `#c46a4f` pour les prévisions), au choix depuis l'en-tête. Toutes les
  couleurs sont des tokens `-fa-*` de `theme.css` ; `theme-light.css`
  ne redéfinit que ces tokens. Les états ne sont jamais
  communiqués par la seule couleur : signe `+`/`−`, libellés, icônes.
- **Navigation latérale** : Tableau de bord · Comptes · Transactions · À venir
  · Calendrier · Épargne · Disponible réel · Récurrences · Abonnements · Import
  · Synchronisation · Règles · Catégories · Paramètres, plus les menus
  facultatifs ; ordre et choix des menus modifiables (section 18).
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
- **Raccourcis** : `Ctrl+N` nouvelle transaction, `Ctrl+M` montants masqués,
  `Ctrl+L` verrouillage, `Ctrl+1…9` navigation (menus affichés, dans l'ordre).

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

## 15. V5 — Synchronisation bancaire : étude puis prototype

D'abord étudiée sans implémentation, conformément au cahier des charges. L'étude
(cadre DSP2, options, fournisseurs, architecture envisagée, menaces, points à
trancher) est dans [`ETUDE-V5-SYNCHRONISATION-BANCAIRE.md`](ETUDE-V5-SYNCHRONISATION-BANCAIRE.md).
Recommandation : prototype facultatif via un agrégateur agréé utilisé avec les
clés de l'utilisateur (sans serveur FinanceApp), seulement après validation des
points bloquants ; à défaut, l'import de fichiers reste la seule source.

### Prototype de synchronisation (après décision GO)

Réalisé selon l'option B de l'étude : module optionnel `financeapp-banksync`
(adaptateur Enable Banking, seul module réseau), service
`BankSyncService`, migration `V6__bank_sync.sql` (format `BANK_SYNC` pour les
lots, tables `bank_sync_config`, `bank_connections`, `bank_account_links`,
`bank_sync_fetches`), écran « Synchronisation » désactivé par défaut. Les
opérations récupérées passent par l'aperçu d'import, la détection des doublons,
les rapprochements et « À valider ». Détails, choix et points restant à valider :
§ 11 de l'étude.

## 16. Distribution : installateur Windows (jpackage)

- **Entrée** : le jar « classique » (`Main-Class` = `Launcher`, `Class-Path`
  vers `lib/`) produit par `mvn package`. JavaFX reste sur le classpath (le
  lanceur séparé évite la vérification de module), avec les jars natifs de la
  plateforme de construction : l'installateur Windows est donc construit **sur
  Windows** (jpackage ne produit que des paquets pour son propre système).
- **Runtime embarqué** : `jlink` avec les modules listés dans
  `packaging/jlink-modules.txt` (calculés par `jdeps --print-module-deps` sur
  toutes les dépendances, plus `jdk.localedata` pour les formats français,
  `jdk.charsets`, `jdk.crypto.ec` pour TLS, `jdk.accessibility`, `java.naming`) ;
  image d'environ 110 Mo.
- **Installateur** (`packaging/windows/build-installer.ps1`) : `.msi` (ou
  `.exe`), installation par utilisateur, menu Démarrer, raccourci proposé,
  choix du dossier, **UUID de mise à niveau fixe** (une version remplace la
  précédente), icône `packaging/windows/financeapp.ico` (générée par
  `packaging/icon/IconGenerator.java`, sans dépendance). Nom (`app.name`) et
  version (pom parent, sans `-SNAPSHOT`) lus depuis le projet.
- **Données** : `%APPDATA%\financeapp`, hors du dossier d'installation ;
  jamais supprimées par une mise à jour ou une désinstallation.
- **Intégration continue** : `.github/workflows/windows-installer.yml`
  (runner `windows-2022`, JDK 21 Temurin, WiX 3.14 installé si absent) :
  tests, installateur en artefact, release sur étiquette `v*`.
- **Vérification sans Windows** : `packaging/linux/build-app-image.sh` applique
  la même configuration. Premier lancement contrôlé sur un dossier de données
  vide (mot de passe maître, clé de récupération, tableau de bord, formats
  français) : ce test a révélé un plantage du disponible réel sans aucun compte
  (tri d'une liste immuable), corrigé et couvert par `FirstRunTest`.
- **Publication d'une version** : notes rédigées dans
  `docs/releases/vX.Y.Z.md` ; commit « Version X.Y.Z » (poms sans `-SNAPSHOT`),
  puis version de développement suivante ; étiquette `vX.Y.Z` sur le commit de
  version. Le workflow construit le `.msi` et publie la release avec ce texte
  (`release-notes.yml` permet de le remplacer après coup). Les versions
  publiées (ni brouillon, ni préversion) sont celles que signale la recherche
  de nouvelles versions (section 22).
- **Non couvert** : signature de code (certificat Authenticode requis pour
  éviter l'avertissement SmartScreen), paquet macOS, mise à jour automatique.

## 17. Épargne détenue et filtres par compte

### Produits d'épargne (migration `V7__savings_products.sql`)

- Chaque produit est un **compte** d'un type d'épargne : Livret A, LDDS, LEP,
  Livret Jeune, CEL, livret bancaire, compte épargne (épargne **disponible**) ;
  PEL, assurance-vie, PEA, PER, épargne salariale (PEE, PERCOL…), compte-titres,
  crypto-actifs (épargne **à moyen et long terme**). Les versements et retraits
  restent des virements internes ; l'épargne n'entre pas dans le disponible
  réel par défaut.
- La colonne `accounts.type` est recréée (nouvelle contrainte CHECK) sans
  changer les identifiants ni les clés étrangères ; les comptes existants
  gardent leur type.
- **Valorisations** (`account_valuations`, une par compte et par date) : la
  valeur saisie (relevé, valorisation d'un PEA…) remplace le solde calculé ;
  les opérations comptées **datées après** s'y ajoutent. Sans valorisation, le
  solde reste « solde initial + opérations ». Les intérêts et plus-values ne
  sont donc jamais comptés comme des revenus : ils n'apparaissent que dans le
  patrimoine. Date future et valeur négative refusées ; supprimer une
  valorisation revient à la précédente.
- **Plafonds de versements** (Livret A 22 950 €, LDDS 12 000 €, LEP 10 000 €,
  Livret Jeune 1 600 €, CEL 15 300 €, PEL 61 200 €) : indicatifs, hors intérêts
  capitalisés ; la marge affichée n'est jamais négative.
- `SavingsService.overview()` : produits actifs par liquidité, totaux en devise
  de référence (les autres devises ne sont pas additionnées), part du
  patrimoine financier, évolution entre les deux dernières valeurs.
- Écran **Épargne** (ajout d'un produit avec sa valeur, mise à jour de la
  valeur, historique) ; les objectifs d'épargne passent dans **Objectifs**.

### Filtre par compte

- Un filtre « Compte » partagé (`UiContext.accountFilter`) sur **À venir**,
  **Récurrences**, **Abonnements** (paiements détectés compris), **Analyses**
  et **Calendrier** ; le choix suit l'utilisateur d'un écran à l'autre.
- Calendrier d'un compte (`CalendarService.month(mois, compte)`) : solde de fin
  de journée = solde actuel moins les opérations postérieures (passé), plus les
  opérations prévues (futur) ; vide avant la dernière valeur saisie d'une
  épargne, car il n'est pas reconstituable.
- Vue d'un compte : les virements internes comptent (entrée ou sortie pour ce
  compte), alors que la vue « Tous les comptes » les neutralise.
- Analyses : `StatisticsService` accepte un compte (revenus, dépenses,
  catégories, commerçants, comparaison de périodes).
- Non filtrés : Budgets. Un budget est un plafond par catégorie, tous comptes
  confondus ; le comparer aux dépenses d'un seul compte donnerait des alertes
  trompeuses.

## 18. Barre latérale organisable

- **À valider, Budgets, Objectifs, Prévisions, Simulations, Crédits, Analyses**
  sont des menus facultatifs, retirés par défaut. Tous les autres menus, sauf
  **Paramètres** (toujours présent, sinon les réglages deviendraient
  introuvables), peuvent aussi être retirés.
- « + Ajouter des menus » (bas de la barre) liste tous les menus : cochés =
  affichés. Un clic droit sur un menu propose « Retirer du menu ». Un menu remis
  reprend la place qu'il occupait. « Rétablir les menus d'origine » remet l'ordre
  et le choix par défaut.
- **Verrouillage**, comme une barre des tâches : verrouillée par défaut, la barre
  ne bouge pas. « Organiser les menus » la déverrouille (cadre en pointillés,
  poignée ⇅, texte d'aide) : glisser-déposer d'un menu (avant ou après celui
  survolé, repère tracé), ou « Monter » / « Descendre » au clic droit (clavier,
  sans souris). « Verrouiller » la fige de nouveau.
- Logique dans le cœur (`MenuLayout`, valeur immuable, testée) ; réglages
  enregistrés dans la base chiffrée du profil : `ui.menu_order` (ordre de tous
  les menus), `ui.menu_hidden` (menus retirés), `ui.menus_locked`. Sans ces
  réglages, l'ancien `ui.optional_menus` est repris (menus déjà ajoutés).
  Un menu apparu dans une version ultérieure se place après son voisin par
  défaut (retiré s'il est facultatif) ; un identifiant inconnu est ignoré.
- Retirer n'est qu'un choix d'affichage : aucune donnée supprimée, calculs
  inchangés (budgets et objectifs restent réservés dans le disponible réel).
  Les liens internes ouvrent toujours l'écran (« Valider maintenant », « Tous
  les budgets », « Objectifs d'épargne »).
- Ctrl+1…9 suivent l'ordre des menus affichés.

## 19. Plusieurs utilisateurs (profils)

- **Profil** = un dossier `AppDirectories` complet : base SQLCipher, trousseau
  (clé de la base enveloppée par le mot de passe maître et la clé de
  récupération de cet utilisateur), sauvegardes, journaux. Aucune donnée ni clé
  partagée : l'isolation repose sur le chiffrement existant, sans nouveau code
  cryptographique.
- `ProfileRegistry` (infra) : `profiles.properties` à la racine du dossier de
  l'application, avec nom, dossier relatif, ordre et dernier profil utilisé.
  Aucune donnée financière ni aucun secret. Écriture atomique ; un dossier
  pointant hors de la racine est refusé.
- **Migration** : si la racine contient déjà une base, un trousseau ou une
  restauration en attente, elle devient le profil `main` (« Profil principal »,
  dossier `.`), sans déplacer de fichier. Nouveaux profils : `profiles/<id>`
  (identifiant aléatoire de 8 caractères hexadécimaux).
- **Démarrage** : aucun profil → nom puis création du mot de passe ; un seul →
  écran de mot de passe direct ; plusieurs → choix de l'utilisateur. La
  restauration en attente est appliquée au choix du profil.
- **Changement d'utilisateur** : sauvegarde automatique (si activée), clé
  effacée de la mémoire, fermeture du contexte Spring de l'utilisateur
  précédent ; le contexte du nouvel utilisateur est démarré à son
  déverrouillage. Les réglages (menus facultatifs, verrouillage automatique…)
  sont propres à chaque profil, car stockés dans sa base.
- **Suppression** (*Paramètres → Utilisateur*) : confirmation, puis mot de
  passe maître du profil, vérifié en déverrouillant son trousseau (rien n'est
  modifié en cas d'échec). La base est fermée, puis `ProfileRegistry.delete`
  efface `data/` (base, trousseau, restauration en attente) et `backups/` ;
  si l'un d'eux ne peut pas être effacé, le profil reste listé. Ensuite
  seulement, l'entrée est retirée de `profiles.properties`. Les journaux sont
  effacés au mieux (un fichier encore ouvert sous Windows peut subsister,
  sans donnée financière). Pour le profil principal, la racine, `profiles/` et
  `profiles.properties` sont conservés. Pas de sauvegarde avant suppression.

## 20. Un seul exemplaire par profil, export CSV

### Verrou de profil (`ProfileLock`)
- Verrou exclusif du système (`FileChannel.tryLock`) sur `data/financeapp.lock`,
  pris à l'ouverture d'un profil (avant toute restauration en attente) et tenu
  jusqu'au changement d'utilisateur, à la suppression du profil ou à la
  fermeture. Un second exemplaire, ou une autre session Windows, affiche
  « Déjà ouvert » avec « Réessayer ».
- Libéré automatiquement par le système si l'application s'arrête brutalement :
  pas de verrou orphelin. Testé entre deux processus.
- Chaque profil a son propre verrou : deux personnes peuvent ouvrir chacune leur
  profil dans deux sessions Windows différentes.

### Export CSV (`TransactionCsvExporter`, cœur)
- Écran Transactions, « Exporter (CSV)… » : **tous** les résultats de la
  recherche en cours (filtres compris), pas seulement les lignes chargées.
- Format pour Excel et LibreOffice en français : `;`, virgule décimale,
  `jj/mm/aaaa`, UTF-8 avec BOM, CRLF. Colonnes : date, compte, libellé,
  catégorie, type, statut, montant signé, devise, autre compte (virement),
  commentaire.
- **Injection de formules** : un champ texte commençant par `= + - @`, une
  tabulation ou un retour chariot est préfixé d'une apostrophe (libellés
  importés de relevés). Les montants ne sont pas concernés.
- Le fichier n'est pas chiffré : l'utilisateur en est averti après l'export.
  Rien n'est écrit dans les journaux.

## 21. V0.2.0 — Ventilation et étiquettes

### Ventilation (`SplitLine`, migration `V8__splits_and_tags.sql`)
- Une dépense ou un revenu peut être réparti sur plusieurs catégories
  (`transaction_splits` : position, catégorie éventuelle, montant). La somme
  des lignes est exactement le montant ; chaque ligne est du même sens, au moins
  deux lignes ; un virement interne ne se ventile pas. `category_id` de
  l'opération est alors vide.
- **Un seul mouvement sur le compte** : soldes, disponible réel et prévisions
  utilisent le montant de l'opération, inchangés.
- `Transaction.categoryShares()` (ses lignes, ou une part unique) est la base de
  **tous les totaux par catégorie** : analyses (catégories, comparaisons),
  budgets (dépensé, et part des opérations prévues ventilées).
- Recherche par catégorie : trouve l'opération si une de ses lignes est dans la
  catégorie (sous-catégories comprises) ; les totaux de la recherche ne
  comptent alors que la part concernée.
- Les règles de catégorisation n'écrasent jamais une ventilation. Une catégorie
  utilisée par une ligne ne peut pas être supprimée (contrainte RESTRICT).
- Export CSV : colonne Catégorie détaillée (« Alimentation (90,00) + Maison
  (30,00) »).
- Ventilation des opérations récurrentes : voir section 22.

### Étiquettes (`Tag`, `TagService`)
- `tags` (nom unique sans tenir compte de la casse, 30 caractères, sans
  virgule) et `transaction_tags`. Saisie libre dans l'opération (« vacances,
  travaux ») : les étiquettes sont créées à la volée, et les existantes sont
  proposées en un clic.
- Filtre de l'écran Transactions par étiquette, avec ses totaux ; usage de
  chaque étiquette (nombre d'opérations, dépensé, reçu) dans l'écran Catégories,
  renommage, suppression (retirée des opérations, qui sont conservées).
- Export CSV : colonne « Étiquettes ».

### Stockage
- Ventilation et étiquettes sont écrites dans la même transaction SQL que
  l'opération (remplacement complet), et relues par paquets de 500 opérations.
  Suppression d'une opération ou annulation d'un import : effacement en
  cascade.

## 22. V0.2.3 — Récurrences ventilées, nouvelles versions

### Récurrences ventilées (migration `V9__recurring_splits.sql`)
- `recurring_splits` (règle, position, catégorie éventuelle, montant positif) ;
  mêmes règles que pour une opération : au moins deux lignes, même devise,
  somme égale au montant de la règle, pas de ventilation d'un virement.
  `category_id` de la règle est alors vide. Suppression de la règle : effacement
  en cascade ; une catégorie utilisée par une ligne ne peut pas être supprimée.
- Les occurrences à venir et les opérations prévues portent la ventilation :
  budgets (part prévue) et analyses la comptent par catégorie.
- Validation d'une occurrence : si le montant réel diffère, les lignes sont
  **réparties au prorata** (arrondi à la devise, la dernière ligne absorbe
  l'écart) ; l'opération créée reste modifiable.
- Abonnements : seule la part des lignes dans les catégories d'abonnement est
  comptée (« part abonnement »).

### Recherche de nouvelles versions (`UpdateService`, `GitHubReleaseFeed`)
- **Désactivée par défaut** (`update.check_enabled`). Activée dans
  *Paramètres → Mises à jour*, elle se fait au plus une fois par jour, au
  démarrage ; « Vérifier maintenant » est toujours possible.
- Une requête HTTPS GET vers l'API publique des versions du dépôt
  (`app.update-repository`, vide = aucune connexion). Si l'API ne répond pas
  (délai de 20 s dépassé, limite de requêtes, hôte filtré), une requête HEAD sur
  la page publique `github.com/<dépôt>/releases/latest` : la version est lue dans
  sa redirection (`…/releases/tag/vX.Y.Z`, refusée si elle sort du dépôt).
  HTTP/1.1 (certains antivirus et proxys bloquent HTTP/2) et proxy du système
  (`java.net.useSystemProxies`). Rien n'est envoyé hormis ce qu'implique la
  requête elle-même (adresse IP, en-tête `User-Agent` générique) : **aucune
  donnée financière, aucun identifiant**.
- Les brouillons et préversions sont ignorés ; le lien affiché n'est accepté que
  s'il pointe vers la page des versions du dépôt. Rien n'est téléchargé ni
  installé automatiquement : un bandeau propose « Voir la nouvelle version »
  (navigateur) ou « Plus tard » (cette version n'est plus signalée).
- Une erreur réseau au démarrage est silencieuse ; elle est affichée en texte
  après « Vérifier maintenant ».

## 23. V0.2.5 — Étiquettes des récurrences, évolution du patrimoine

### Étiquettes des récurrences (migration `V10__recurring_tags.sql`)
- `recurring_tags` (règle, étiquette), effacée en cascade avec la règle ou
  l'étiquette (la règle est conservée). `RecurringRule.tagIds` ; pas
  d'étiquette sur un virement récurrent (comme pour la ventilation).
- Chaque occurrence validée ou ignorée crée une opération portant les
  étiquettes de la règle : filtres et totaux par étiquette les comptent sans
  saisie. Les échéances d'un crédit gardent les étiquettes de leur règle.
- Saisie commune aux opérations et aux récurrences (`TagField` : texte libre
  séparé par des virgules, étiquettes existantes proposées en un clic).

### Évolution du patrimoine (`NetWorthService`, écran Épargne)
- Patrimoine des comptes actifs de la devise de référence en **fin de mois**,
  puis aujourd'hui ; répartition comptes courants / épargne.
- Solde d'un compte à une date : dernière valeur constatée à cette date plus les
  opérations postérieures (jusqu'à la date) ; à défaut, solde initial plus les
  opérations jusqu'à la date. Un compte ne compte qu'à partir de sa date
  d'ouverture ; opérations prévues et annulées exclues ; virements neutres.
- Le dernier point reprend les soldes actuels : il est égal au patrimoine du
  tableau de bord. Une seule lecture des opérations comptées.
- Courbe : trait plein épais (patrimoine), tirets (épargne), pointillés
  (comptes courants), légende et texte explicatif ; période 12 mois, 24 mois,
  5 ans ou depuis l'ouverture du premier compte ; évolution signée depuis le
  premier point. Comptes dans une autre devise : non comptés, signalés.
- Limite : les valeurs passées d'un placement ne sont connues qu'aux dates
  saisies (entre deux, la valeur précédente plus les versements).

## 24. V0.3.0 — Justificatifs joints, comptes regroupés

### Justificatifs (`Attachment`, `AttachmentService`, migration `V11__attachments.sql`)
- Table `attachments` (opération, nom, type, taille, date d'ajout, contenu
  `BLOB`), effacée en cascade avec l'opération. Le contenu est **dans la base
  chiffrée** : chiffré sur le disque, inclus dans chaque sauvegarde (`VACUUM
  INTO`) et restauré avec elle, sans gestion de clé supplémentaire. Les listes
  ne lisent que les métadonnées ; le contenu est lu à la demande.
- Formats acceptés reconnus **au contenu** (signature en début de fichier) et
  non à l'extension : PDF, JPEG, PNG, GIF, WebP, HEIC. 10 Mo maximum par
  fichier, 20 par opération. Nom nettoyé (sans dossier ni caractères
  interdits, 120 caractères, extension du format réel).
- Saisie dans la fenêtre de l'opération (`AttachmentsPane`) : bouton ou
  glisser-déposer ; ajouts et retraits appliqués **à l'enregistrement**
  (« Annuler » les annule) ; si l'enregistrement des justificatifs échoue pour
  une nouvelle opération, l'opération n'est pas gardée.
- Consultation : PNG, JPEG, GIF affichés dans l'application, **sans fichier
  sur le disque**. PDF, WebP, HEIC : copie déchiffrée dans
  `data/ouverts/<aléatoire>/` du profil, ouverte avec l'application du
  système ; le dossier est vidé au verrouillage, à la fermeture, au
  changement d'utilisateur et à l'ouverture du profil (un fichier encore
  ouvert ailleurs est effacé à la purge suivante). « Enregistrer… » : copie
  non chiffrée choisie par l'utilisateur.
- Les noms et contenus ne sont jamais écrits dans les journaux.
- Défaire un import supprime ses opérations, donc leurs justificatifs : la
  confirmation indique combien seront supprimés.
- Liste des opérations : « · 2 justificatifs » après le libellé (une requête
  groupée par page). Paramètres → Sauvegardes : nombre et taille totale.
- Limite : les justificatifs alourdissent la base et chaque sauvegarde
  conservée (rotation).

### Comptes regroupés
- Écran Comptes : sections **Comptes courants**, **Épargne**, **Espèces et
  autres** (groupe du type de compte), chacune avec son nombre de comptes et
  son sous-total par devise, puis le total général ; bouton « Ajouter une
  épargne » (même fenêtre que l'écran Épargne).

## 25. Version web (dossier `web/`)

- **Objectif** : la même application dans un navigateur, sans renoncer au principe
  « données locales par défaut ». Le site est **statique** (HTML, JS, CSS) : il n'existe
  ni serveur d'application ni base en ligne ; l'hébergeur ne voit aucune donnée.
- **Pile** : TypeScript, React 19, Vite. Aucune bibliothèque de graphiques ni de
  cryptographie (SVG et WebCrypto du navigateur). Aucune ressource externe.
- **Domaine** (`web/src/domain`) : portage fidèle du cœur Java : montants en centimes
  entiers, dates civiles sans fuseau, moteur de récurrences (occurrences calculées depuis
  la date de départ, fins de mois), opérations à venir (retards 14 jours), disponible
  réel (mêmes sections et mêmes règles), prévision jour par jour, prochaine paie.
  Les scénarios chiffrés du cahier des charges (1 600 €, 86 €) sont testés à l'identique.
- **Coffre** (`web/src/store/vault.ts`) : IndexedDB. Clé de données AES-256-GCM aléatoire
  enveloppée par le mot de passe (PBKDF2-SHA-256, 600 000 itérations, sel 16 octets) et
  par une clé de récupération de 160 bits ; données chiffrées en un bloc (AES-GCM, IV
  aléatoire, identifiant du profil en données authentifiées), réécrit après chaque
  modification. En mémoire, la clé n'est pas exportable ; elle ne l'est que le temps de
  l'envelopper à nouveau (changement de mot de passe, récupération).
- **Session** : un profil par onglet (Web Locks), verrouillage manuel ou après
  inactivité, enregistrement en série et erreur d'enregistrement affichée.
- **Sauvegarde** : fichier JSON contenant le profil chiffré tel quel ; l'import ajoute un
  profil et n'écrase jamais un profil existant.
- **Sécurité du site** : politique de sécurité du contenu stricte (aucune connexion
  sortante), refus d'affichage dans un cadre, en-têtes fournis pour l'hébergement
  (`web/public/_headers`, exemples nginx et Apache dans `web/README-WEB.md`). HTTPS
  obligatoire (WebCrypto).
- **Export desktop → web** (`WebExportService` dans le cœur, `WebBackupWriter` dans
  l'infrastructure) : le desktop produit directement une sauvegarde au format web
  (même schéma : PBKDF2-HMAC-SHA-256 600 000 itérations sur le mot de passe normalisé
  NFC en UTF-8, AES-256-GCM, identifiant du profil en données authentifiées, clé de
  récupération au même format), avec la seule cryptographie du JDK ; le site la restaure
  sans code spécifique. Interopérabilité vérifiée par un test web qui ouvre un fichier
  produit par Java (mot de passe accentué, clé de récupération). Soldes actuels conservés
  au centime (solde initial ajusté, valeurs d'épargne comprises) ; comptes d'une autre
  devise, crédits, simulations, justificatifs non repris ; ventilation ramenée à la
  catégorie principale (détail en commentaire). Budgets et objectifs : voir section 26.
- **Limites** : données liées au navigateur et à l'appareil (sauvegardes à télécharger),
  pas de synchronisation automatique entre appareils (échange par fichier, section 26).
  Fonctions non portées : abonnements, analyses, crédits, simulations, ventilation,
  justificatifs, OFX/QIF.
- **Intégration continue** : `.github/workflows/web.yml` (tests, construction, site en
  artefact `financeapp-web`).

## 26. Budgets et objectifs sur le web, retour web → desktop

### Budgets et objectifs d'épargne sur le site
- `web/src/domain/budgets.ts` : portage de `BudgetEngine`, `BudgetService`,
  `SavingsGoalCalculator` et `SavingsGoalService`. Même périmètre (catégorie et
  sous-catégories, opérations effectuées ou en attente, dépenses prévues du mois sans double
  comptage, retards rattachés au mois en cours), même réservation dans le disponible réel
  (reste au prorata des jours, mois suivants au prorata de la limite ; effort mensuel des
  objectifs par mois entamé), mêmes libellés de lignes et même ordre.
- **Arrondis identiques** : `Money.divide` du desktop arrondit d'abord à 4 décimales de
  centime puis au centime (HALF_EVEN deux fois) ; `divideMoney` reproduit ce double
  arrondi (un arrondi unique donnerait parfois un centime d'écart).
- **Parité vérifiée** : `WebParityTest` (infra) construit un jeu de données (budgets avec
  sous-catégories, dépenses prévues et récurrentes, objectifs liés à un compte, manuels,
  sans échéance, archivés, atteints), exporte les données et les résultats du desktop
  (disponible réel sur cinq échéances, budgets sur trois mois, objectifs) ;
  `web/src/domain/parity.test.ts` refait les calculs et doit trouver les mêmes montants
  au centime.
- L'export desktop → web reprend désormais budgets et objectifs : le disponible réel du
  site est le même que celui de l'application (une opération ventilée garde sa catégorie
  principale : le suivi d'un budget peut alors différer).
- Écrans *Budgets* (mois choisi, alertes, barre et pourcentage écrits, état avec symbole)
  et *Objectifs d'épargne* (versement, archivage), aperçu des budgets sur le tableau de bord.

### Import dans l'application des saisies faites sur le site
- `WebBackupReader` (infra) : vérifie le format, lit le nom du profil sans mot de passe,
  puis déchiffre avec le mot de passe du profil web (même schéma que `WebBackupWriter`,
  JDK uniquement ; itérations bornées, taille de fichier limitée). Lecture JSON par
  `Json.parse` (cœur, sans dépendance, profondeur bornée, nombres entiers seulement).
- `WebImportService` (cœur) — **rien d'ambigu n'est importé en silence** :
  - chaque compte du site est associé à un compte de l'application (même nom par
    défaut), créé, ou ignoré ;
  - une opération exportée par l'application garde son identifiant sur le site : même
    identifiant, même compte et même montant ou libellé → inchangée : ignorée ; prévue
    ici et effectuée sur le site : passe à « effectuée » (rapprochement, annulable) ;
    modifiée sur le site : signalée, non importée (à reporter à la main) ;
  - échéance récurrente validée ou ignorée sur le site : rattachée à la même occurrence
    de la règle (même identifiant et même libellé), sinon importée comme opération simple ;
    déjà traitée ici : doublon signalé ;
  - opération identique déjà présente (compte, date, montant, libellé) : ignorée ; même
    montant à ±3 jours : doublon possible, décoché ; opération prévue de même montant et
    libellé équivalent à ±10 jours : réalisée ;
  - deux opérations identiques saisies sur le site restent deux opérations (chaque
    opération de l'application ne correspond qu'à une seule) ;
  - virement vers un compte non importé : dépense ou revenu, à confirmer ;
  - catégories : code stable, sinon même chemin, sinon créées ; étiquettes par nom.
- Enregistrement par `ImportRepository` (un lot par compte, format `WEB`, migration
  `V12__web_import.sql`) : défaire l'import depuis l'écran *Imports* supprime les
  opérations créées et remet « prévues » les opérations réalisées. Les opérations
  importées ne passent pas par l'Inbox « à valider » (l'utilisateur les a saisies lui-même).
- **Vérification de bout en bout** : `web/src/store/webBackupForDesktop.test.ts` part des
  données exportées par le desktop, simule des saisies sur le site (achats identiques,
  nouvelle catégorie, échéance validée, échéance ignorée, opération prévue réalisée,
  opération modifiée, virement, nouveau compte, opération prévue) et produit une vraie
  sauvegarde WebCrypto ; `WebImportTest` la déchiffre, l'importe et retrouve exactement
  les soldes et le disponible réel du site ; un second import n'ajoute rien ; défaire
  l'import rend l'état initial.
