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
| Budgets, calendrier, épargne, abonnements, stats, recherche | Pilotage | Moyenne | V2 |
| Import CSV, règles de catégorisation, Inbox, doublons | Gain de temps | Élevée | V3 |
| Crédits, amortissement, simulations *What If* | Décision | Élevée | V4 |
| Synchronisation bancaire (DSP2 via prestataire agréé) | Confort | Très élevée + réglementaire | V5 (étude) |
| Mot de passe maître + chiffrement base | Confidentialité | Élevée | Dès que le MVP est stable (V1.1) |

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
| Argon2id | ✅ **V1.1** | Via Bouncy Castle (`Argon2BytesGenerator`), jamais d'implémentation maison. Paramètres cibles : m = 64 Mio, t = 3, p = 1, sel 16 o aléatoire, clé 32 o. |
| Chiffrement base | ✅ **V1.1** | Candidat : `io.github.willena:sqlite-jdbc` (fork de xerial compatible SQLCipher, API JDBC identique → changement localisé dans `SqliteDataSourceFactory`). Alternative étudiée : chiffrer le fichier entier au repos (AES-GCM via JCA) — rejetée : fenêtre en clair sur disque pendant l'usage. |

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

Après le MVP : V1.1 sécurité (mot de passe maître Argon2id, SQLCipher,
verrouillage auto), puis V2 → V5 selon la roadmap du brief. Packaging
Windows via `jpackage` (runtime embarqué, pas d'installation de Java).
