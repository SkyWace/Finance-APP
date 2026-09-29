# FinanceApp

Application desktop **locale** de gestion financière personnelle. Elle répond
d'abord à une question : *« Combien ai-je réellement de disponible, une fois
toutes mes dépenses futures prises en compte ? »*

> Nom provisoire. Il est défini à un seul endroit : `app.name` dans
> `financeapp-desktop/src/main/resources/application.properties`.

- Hors ligne, sans compte en ligne, sans connexion bancaire.
- Données dans une base SQLite locale **chiffrée** (SQLCipher, AES-256),
  protégée par un mot de passe maître (Argon2id) ; aucune télémétrie.
- Conception détaillée (architecture, risques, schéma, étapes) :
  [`docs/CONCEPTION.md`](docs/CONCEPTION.md).

## Fonctionnalités (V1 → V2)

| Écran | Contenu |
|---|---|
| Tableau de bord | Patrimoine, comptes courants, épargne, **disponible réel** (cliquable), revenus/dépenses du mois, à venir, prévision 30 jours, prochaines et dernières opérations |
| Comptes | Création, solde initial, type, couleur, inclusion dans le disponible, archivage |
| Transactions | Dépenses, revenus, **virements internes** (neutres pour le patrimoine), statuts prévu / en attente / effectué / annulé, **recherche avancée** (texte, compte, catégorie, période, montant, statut) avec total et moyenne des résultats |
| Calendrier | Mois en grille : opérations réelles et prévues, solde en fin de journée (réel puis prévu), détail du jour |
| Budgets | Plafond mensuel par catégorie, progression, alertes (proche / atteint / dépassé, en texte et symbole), reste réservé dans le disponible |
| Épargne | Objectifs (montant, échéance), suivi via un compte ou manuel, épargne mensuelle nécessaire |
| Abonnements | Coût mensuel et annuel, détection des paiements réguliers dans l'historique |
| Analyses | Revenus/dépenses/épargne du mois et taux d'épargne vs mois précédent, 12 mois, catégories, comparaison (montants + %) |
| À venir | Opérations prévues + occurrences récurrentes ; valider (date et montant réels) ou ignorer |
| Récurrences | Hebdo, 2 semaines, mensuelle, trimestrielle, annuelle, tous les N jours/semaines/mois ; équivalents mensuel et annuel |
| Disponible réel | Échéance : fin de semaine, prochaine paie, fin du mois, date personnalisée ; **détail ligne à ligne** du calcul, budgets et objectifs réservés, « si aucune autre dépense variable » |
| Prévisions | Courbe réel (plein) / prévu (pointillés), 7 j à 12 mois, point bas, alerte de solde négatif |
| Catégories | Catégories par défaut + personnelles, sous-catégories, archivage |
| Paramètres | Devise de référence, échéance par défaut, **sécurité** (verrouillage auto, changement de mot de passe, nouvelle clé de récupération), **mode confidentialité**, sauvegardes chiffrées (auto à la fermeture avec rotation, export, restauration) |

## Sécurité (V1.1)

- **Mot de passe maître** demandé à chaque ouverture ; aucun mot de passe
  n'est stocké. Une **clé de récupération** est remise une seule fois à la
  création : conservez-la, c'est le seul recours en cas d'oubli.
- **Base et sauvegardes chiffrées** sur le disque. Les données d'une version
  précédente (non chiffrées) sont chiffrées automatiquement lors de la création
  du mot de passe.
- **Verrouillage** : bouton, `Ctrl+L`, ou automatique après inactivité. La clé
  est alors effacée de la mémoire.
- Chaque sauvegarde `.db` est accompagnée d'un fichier `.key` (sans secret en
  clair) : gardez-les ensemble pour pouvoir restaurer sur un autre ordinateur.

Détails et limites : [`docs/CONCEPTION.md`](docs/CONCEPTION.md), section 11.

Raccourcis : `Ctrl+N` nouvelle opération · `Ctrl+M` masquer les montants ·
`Ctrl+L` verrouiller ·
`Ctrl+1`…`Ctrl+9` navigation (neuf premiers écrans).

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

Si vous avez déjà construit une version antérieure, faites un `mvn clean
package` (l'application refuse de démarrer si un ancien pilote SQLite sans
chiffrement traîne dans `lib/`).

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
- `financeapp-infra` : dépôts sur une vraie base SQLite **chiffrée**
  temporaire, atomicité des virements, contraintes d'intégrité, sauvegardes,
  rotation, restauration (y compris depuis une autre installation), mot de
  passe maître, récupération, trousseau altéré ou perdu, migration des données
  V1 en clair.
- `financeapp-desktop` : démarrage complet du contexte Spring (sans
  interface), verrouillage/déverrouillage.

## Architecture

```
financeapp-core      domaine, moteurs (Recurrence, AvailableBalance, Forecast), services, ports — aucune dépendance
financeapp-infra     SQLite chiffré + Spring JDBC, migrations Flyway, sauvegardes, mot de passe maître
financeapp-desktop   JavaFX (vues en code, thème sombre CSS) + Spring Boot (injection, configuration)
```

Montants : `BigDecimal` en mémoire (arrondi `HALF_EVEN`, échelle de la
devise), centimes (`INTEGER`) en base. Dates : `java.time`.

## Limites connues du MVP

- Une seule devise de référence pour les totaux ; pas de conversion.
- Ne pas ouvrir deux instances sur le même dossier de données.
- Import CSV, catégorisation automatique, crédits et simulations : versions
  suivantes (V3, V4). Étiquettes (tags) non encore disponibles.
- Installateur Windows (`jpackage`) non encore fourni : le jar + `lib/`
  produits par `mvn package` en sont l'entrée prévue.
