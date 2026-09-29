# FinanceApp

Application desktop **locale** de gestion financière personnelle. Elle répond
d'abord à une question : *« Combien ai-je réellement de disponible, une fois
toutes mes dépenses futures prises en compte ? »*

> Nom provisoire. Il est défini à un seul endroit : `app.name` dans
> `financeapp-desktop/src/main/resources/application.properties`.

- Hors ligne, sans compte en ligne, sans connexion bancaire.
- Données dans une base SQLite locale ; aucune télémétrie.
- Conception détaillée (architecture, risques, schéma, étapes) :
  [`docs/CONCEPTION.md`](docs/CONCEPTION.md).

## Fonctionnalités (MVP V1)

| Écran | Contenu |
|---|---|
| Tableau de bord | Patrimoine, comptes courants, épargne, **disponible réel** (cliquable), revenus/dépenses du mois, à venir, prévision 30 jours, prochaines et dernières opérations |
| Comptes | Création, solde initial, type, couleur, inclusion dans le disponible, archivage |
| Transactions | Dépenses, revenus, **virements internes** (neutres pour le patrimoine), statuts prévu / en attente / effectué / annulé, filtres, recherche |
| À venir | Opérations prévues + occurrences récurrentes ; valider (date et montant réels) ou ignorer |
| Récurrences | Hebdo, 2 semaines, mensuelle, trimestrielle, annuelle, tous les N jours/semaines/mois ; équivalents mensuel et annuel |
| Disponible réel | Échéance : fin de semaine, prochaine paie, fin du mois, date personnalisée ; **détail ligne à ligne** du calcul |
| Prévisions | Courbe réel (plein) / prévu (pointillés), 7 j à 12 mois, point bas, alerte de solde négatif |
| Catégories | Catégories par défaut + personnelles, sous-catégories, archivage |
| Paramètres | Devise de référence, échéance par défaut, **mode confidentialité**, sauvegardes (auto à la fermeture avec rotation, export, restauration) |

Raccourcis : `Ctrl+N` nouvelle opération · `Ctrl+M` masquer les montants ·
`Ctrl+1`…`Ctrl+9` navigation.

## Lancer

Prérequis : Java 21+ et Maven 3.9+.

```bash
./run.sh            # Linux / macOS : construit puis lance
run.bat             # Windows
./run.sh --test     # exécute d'abord les tests
```

Manuellement :

```bash
mvn package                                   # construit + tests
java -jar financeapp-desktop/target/financeapp-desktop.jar
```

Essai avec un dossier de données jetable :

```bash
java -Dapp.data-dir=/tmp/financeapp-essai -jar financeapp-desktop/target/financeapp-desktop.jar
```

Emplacement normal des données : `%APPDATA%\financeapp` (Windows),
`~/Library/Application Support/financeapp` (macOS),
`~/.local/share/financeapp` (Linux) — sous-dossiers `data/`, `backups/`, `logs/`.

## Tests

```bash
mvn test
```

- `financeapp-core` (Java pur, sans base ni UI) : `Money` et arrondis,
  récurrences (fins de mois, années bissextiles, intervalles), disponible réel
  (scénarios du cahier des charges : 1 000 + 1 800 − 700 − 200 − 300 = 1 600 ;
  1 420 − 934 − 300 − 100 = 86), prévisions, virements internes, services.
- `financeapp-infra` : dépôts sur une vraie base SQLite temporaire,
  atomicité des virements, contraintes d'intégrité, sauvegardes, rotation,
  restauration.
- `financeapp-desktop` : démarrage complet du contexte Spring (sans interface).

## Architecture

```
financeapp-core      domaine, moteurs (Recurrence, AvailableBalance, Forecast), services, ports — aucune dépendance
financeapp-infra     SQLite + Spring JDBC, migrations Flyway, sauvegardes
financeapp-desktop   JavaFX (vues en code, thème sombre CSS) + Spring Boot (injection, configuration)
```

Montants : `BigDecimal` en mémoire (arrondi `HALF_EVEN`, échelle de la
devise), centimes (`INTEGER`) en base. Dates : `java.time`.

## Limites connues du MVP

- Pas encore de mot de passe maître ni de chiffrement de la base (prévu V1.1 :
  Argon2id + SQLCipher, voir la conception).
- Une seule devise de référence pour les totaux ; pas de conversion.
- Ne pas ouvrir deux instances sur le même dossier de données.
- Budgets, calendrier, objectifs d'épargne, import CSV, crédits et
  simulations : versions suivantes (V2 à V4).
- Installateur Windows (`jpackage`) non encore fourni : le jar + `lib/`
  produits par `mvn package` en sont l'entrée prévue.
