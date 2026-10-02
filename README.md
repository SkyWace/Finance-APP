# FinanceApp

Application desktop **locale** de gestion financière personnelle. Elle répond
d'abord à une question : *« Combien ai-je réellement de disponible, une fois
toutes mes dépenses futures prises en compte ? »*

> Nom provisoire. Il est défini à un seul endroit : `app.name` dans
> `financeapp-desktop/src/main/resources/application.properties`.

- Hors ligne, sans compte en ligne. Synchronisation bancaire facultative
  (prototype, désactivée par défaut) ; sinon, import de relevés.
  Recherche de nouvelles versions facultative, elle aussi désactivée par défaut.
- Données dans une base SQLite locale **chiffrée** (SQLCipher, AES-256),
  protégée par un mot de passe maître (Argon2id) ; aucune télémétrie.
- Conception détaillée (architecture, risques, schéma, étapes) :
  [`docs/CONCEPTION.md`](docs/CONCEPTION.md).

## Fonctionnalités

Barre latérale **organisable**, comme une barre des tâches : **« Organiser les menus »** la déverrouille, puis glissez un menu pour le déplacer (ou clic droit → Monter / Descendre) et **« Verrouiller »** pour qu'elle ne bouge plus. Clic droit sur un menu : « Retirer du menu » ; **« + Ajouter des menus »** le remet à sa place, ou ajoute un menu facultatif (**À valider, Budgets, Objectifs, Prévisions, Simulations, Crédits, Analyses**, retirés par défaut pour garder une navigation simple). Paramètres reste toujours présent. Retirer un menu ne supprime aucune donnée et ne change aucun calcul. Chaque profil garde sa propre disposition.

| Écran | Contenu |
|---|---|
| Tableau de bord | Patrimoine, comptes courants, épargne, **disponible réel** (cliquable), revenus/dépenses du mois, à venir, prévision 30 jours, prochaines et dernières opérations |
| Comptes | Création, solde initial, type, couleur, inclusion dans le disponible, archivage |
| Transactions | **Ventilation** d'une opération sur plusieurs catégories, **étiquettes** (filtre et total par étiquette), **export CSV** (Excel / LibreOffice) des résultats de la recherche ; dépenses, revenus, **virements internes** (neutres pour le patrimoine), statuts prévu / en attente / effectué / annulé, **recherche avancée** (texte, compte, catégorie, période, montant, statut) avec total et moyenne des résultats |
| Calendrier | **Filtre par compte** (solde du compte jour par jour) ; mois en grille : opérations réelles et prévues, solde en fin de journée (réel puis prévu), détail du jour |
| Budgets | Plafond mensuel par catégorie, progression, alertes (proche / atteint / dépassé, en texte et symbole), reste réservé dans le disponible |
| Épargne | Épargne détenue : **Livret A, LDDS, LEP, Livret Jeune, CEL, PEL, assurance-vie, PEA, PER, épargne salariale (PEE, PERCOL…), compte-titres, crypto-actifs**… ; valeur actuelle mise à jour à la main (relevé, valorisation) avec historique ; épargne disponible / à moyen et long terme ; part du patrimoine ; **marge sous le plafond** des livrets réglementés |
| Objectifs | Objectifs d'épargne (montant, échéance), suivi via un compte ou manuel, épargne mensuelle nécessaire |
| Abonnements | **Filtre par compte** ; coût mensuel et annuel, détection des paiements réguliers dans l'historique |
| Analyses | **Filtre par compte** ; revenus/dépenses/épargne du mois et taux d'épargne vs mois précédent, 12 mois, catégories, comparaison (montants + %), **principaux commerçants**, **comparaison de deux périodes quelconques** |
| Import | Relevés **CSV** (assistant de correspondance des colonnes avec aperçu), **OFX / QFX**, **QIF** ; vérification ligne à ligne (nouvelle, doublon, opération prévue réalisée…) ; historique et **annulation d'un import** |
| À valider | Opérations importées avec leur catégorie proposée : valider, corriger, tout valider |
| Synchronisation *(prototype, désactivée par défaut)* | Lecture seule via **Enable Banking** avec votre propre compte (mode restreint gratuit) : authentification chez votre banque, aucun identifiant saisi, aucun serveur FinanceApp ; opérations vérifiées comme un import (doublons, rapprochements, « À valider ») ; révocation et effacement en un clic |
| Règles | Catégorisation automatique locale (« libellé contenant TOTAL → Carburant »), proposée quand vous corrigez une catégorie |
| À venir | **Filtre par compte** (virements compris) ; opérations prévues + occurrences récurrentes ; valider (date et montant réels) ou ignorer |
| Récurrences | **Filtre par compte** ; hebdo, 2 semaines, mensuelle, trimestrielle, annuelle, tous les N jours/semaines/mois ; équivalents mensuel et annuel ; **ventilation** sur plusieurs catégories (loyer + charges…), reprise par chaque occurrence validée |
| Disponible réel | Échéance : fin de semaine, prochaine paie, fin du mois, date personnalisée ; **détail ligne à ligne** du calcul, budgets et objectifs réservés, « si aucune autre dépense variable » |
| Prévisions | Courbe réel (plein) / prévu (pointillés), 7 jours à **48 mois**, point bas, alerte de solde négatif, dépenses courantes estimées (option) |
| Simulations | **What If?** : achat financé à crédit, nouvelle charge ou rentrée, crédit, arrêt d'une récurrence ; disponible, reste à vivre et capacité d'épargne **avant / après**, mois par mois, courbe de solde, objectifs ; ne modifie jamais les données réelles |
| Crédits | Capital restant, mensualité, prochaine échéance, progression, **tableau d'amortissement**, taux estimé si inconnu ; mensualités reliées à une récurrence (comptées une seule fois) |
| Catégories | Catégories par défaut + personnelles, sous-catégories, archivage ; **étiquettes** (usage et totaux, renommer, supprimer) |
| Paramètres | Devise de référence, échéance par défaut, **sécurité** (verrouillage auto, changement de mot de passe, nouvelle clé de récupération), **mode confidentialité**, sauvegardes chiffrées (auto à la fermeture avec rotation, export, restauration), **mises à jour** (recherche des nouvelles versions, désactivée par défaut) |

## Plusieurs utilisateurs

Chaque personne qui utilise l'application sur l'ordinateur a son **propre profil** : ses comptes, ses réglages, ses sauvegardes, **son mot de passe maître et sa clé de récupération**. Un utilisateur ne peut pas ouvrir les données d'un autre (bases chiffrées avec des clés différentes).

- Au démarrage : « Qui utilise FinanceApp ? » puis le mot de passe de l'utilisateur choisi (avec un seul profil, l'écran de mot de passe s'affiche directement).
- Ajouter un utilisateur : bouton à son nom en haut de l'écran (ou *Paramètres → Utilisateur → Changer d'utilisateur*), puis « Ajouter un utilisateur ».
- Changer d'utilisateur fait d'abord la sauvegarde automatique, puis verrouille.
- Un profil ne peut être ouvert que dans **une seule fenêtre à la fois** (verrou sur ses données) : un second lancement affiche « Déjà ouvert » avec « Réessayer ».
- Une installation existante devient le « Profil principal » (renommable dans *Paramètres → Utilisateur*), sans déplacer de fichier ; les profils suivants sont dans `profiles/<identifiant>` du dossier de données.
- Seuls les noms des profils sont lisibles sans mot de passe (`profiles.properties`).
- Supprimer un profil : *Paramètres → Utilisateur → Supprimer ce profil…*, avec confirmation puis **mot de passe maître de ce profil**. Sa base, son trousseau et ses sauvegardes automatiques sont effacés ; les autres utilisateurs et les sauvegardes exportées ailleurs ne sont pas touchés.

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

Thèmes : sombre « Nuit & Saphir » (par défaut) ou clair « Lin & Prune », au choix avec le bouton ☀ / ☾ de
l'en-tête ; le choix est retenu sur l'ordinateur (écran de déverrouillage compris).

Raccourcis : `Ctrl+N` nouvelle opération · `Ctrl+M` masquer les montants ·
`Ctrl+L` verrouiller ·
`Ctrl+1`…`Ctrl+9` navigation (neuf premiers menus, dans l'ordre choisi).

## Mises à jour

*Paramètres → Mises à jour* affiche la version installée. La case « Vérifier les
nouvelles versions à l'ouverture » est **décochée par défaut** : sans elle,
l'application ne se connecte jamais pour cela. Cochée, elle consulte au plus une
fois par jour la liste publique des versions sur GitHub (aucune donnée
financière envoyée) et affiche un bandeau « Voir la nouvelle version » /
« Plus tard ». Rien n'est téléchargé ni installé automatiquement : on installe
le nouveau `.msi` par-dessus l'ancien, les données sont conservées.

## Installer (Windows)

L'installateur `.msi` embarque son propre runtime Java : **rien d'autre à
installer**. Installation par utilisateur (aucun droit administrateur), menu
Démarrer et raccourci sur le bureau (proposé). Une nouvelle version remplace la
précédente ; les données (`%APPDATA%\financeapp`) ne sont jamais touchées par
une mise à jour ou une désinstallation.

**Obtenir l'installateur**

- Sur GitHub : onglet *Actions* → *Installateur Windows* → *Run workflow*, puis
  télécharger l'artefact `FinanceApp-<version>.msi` de l'exécution (le fichier
  `.msi` lui-même, sans zip).
- Sur la page *Releases* du dépôt : chaque version publiée y a son `.msi` et
  le résumé de ses nouveautés.
- Sur un PC Windows (JDK 21, Maven, [WiX Toolset 3.14](https://github.com/wixtoolset/wix3/releases)) :

  ```powershell
  powershell -ExecutionPolicy Bypass -File packaging\windows\build-installer.ps1
  # -> financeapp-desktop\target\installer\FinanceApp-<version>.msi   (-Type exe pour un .exe)
  ```

L'installateur n'est pas signé : Windows SmartScreen peut afficher « Windows a
protégé votre ordinateur » → *Informations complémentaires* → *Exécuter quand
même*. La signature de code nécessite un certificat (voir `docs/CONCEPTION.md`).

Même configuration sous Linux, pour vérifier le paquet sans Windows :
`packaging/linux/build-app-image.sh` (image applicative) ou `… deb`.

**Publier une version**

1. Écrire `docs/releases/vX.Y.Z.md` (nouveautés, installation).
2. Passer la version des `pom.xml` à `X.Y.Z` (sans `-SNAPSHOT`), vérifier
   (`mvn install`), valider « Version X.Y.Z » ; puis passer à la version de
   développement suivante (`X.Y.(Z+1)-SNAPSHOT`).
3. Poser l'étiquette sur le commit « Version X.Y.Z » :
   `git tag vX.Y.Z <commit> && git push origin vX.Y.Z`.
   Le workflow *Installateur Windows* construit le `.msi` et publie la
   *release* avec le texte de `docs/releases/vX.Y.Z.md` (sinon notes
   automatiques de GitHub). Pour corriger ce texte après coup : *Actions* →
   *Notes de release* → *Run workflow* avec l'étiquette.

## Lancer depuis les sources

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
  1 420 − 934 − 300 − 100 = 86), prévisions, virements internes, services,
  lecture CSV/OFX/QIF, doublons et rapprochements, règles de catégorisation,
  scénario complet d'import (le loyer importé réalise l'échéance au lieu
  d'être compté deux fois), tableaux d'amortissement (valeurs de référence),
  taux estimé, prévisions longues avec dépenses courantes, simulation « achat
  voiture » chiffrée au centime, et vérification qu'une simulation ne modifie
  aucune donnée réelle ; premier lancement sans aucun compte ; ventilation
  et étiquettes (totaux par catégorie, budgets, recherche, export CSV),
  récurrences ventilées (répartition au
  prorata à la validation), filtres par compte et épargne, détection des
  abonnements, recherche de nouvelles versions (une fois par jour, version
  ignorée, désactivée par défaut), disposition des menus (ordre, menus
  retirés, reprise de l'ancien réglage, menus d'une version ultérieure).
- `financeapp-infra` : dépôts sur une vraie base SQLite **chiffrée**
  temporaire, atomicité des virements, contraintes d'intégrité, sauvegardes,
  rotation, restauration (y compris depuis une autre installation), mot de
  passe maître, récupération, trousseau altéré ou perdu, migration des données
  V1 en clair, import atomique et annulation (restauration des opérations
  prévues, réimport après annulation), crédits (taux exact, récurrence liée
  unique), scénarios et hypothèses (remplacement atomique, cascade),
  ventilations et étiquettes (stockage, catégories protégées), épargne,
  profils (registre, suppression, verrou d'un seul exemplaire).
- `financeapp-banksync` : client Enable Banking contre un serveur HTTP local
  simulé qui vérifie la signature de chaque jeton ; correspondance des
  opérations, pagination, erreurs, clés refusées ; liste des versions GitHub
  (serveur local : préversions ignorées, liens hors du dépôt refusés).
- `financeapp-desktop` : démarrage complet du contexte Spring (sans
  interface), verrouillage/déverrouillage.

## Architecture

```
financeapp-core      domaine, moteurs (Recurrence, AvailableBalance, Forecast, Import, Categorization, Loan, Simulation), services, ports — aucune dépendance
financeapp-infra     SQLite chiffré + Spring JDBC, migrations Flyway, sauvegardes, mot de passe maître
financeapp-banksync  adaptateur Enable Banking (optionnel, lecture seule) et recherche de nouvelles versions — seul module qui accède au réseau
financeapp-desktop   JavaFX (vues en code, thèmes sombre et clair en CSS) + Spring Boot (injection, configuration)
```

Montants : `BigDecimal` en mémoire (arrondi `HALF_EVEN`, échelle de la
devise), centimes (`INTEGER`) en base. Dates : `java.time`.

## Limites connues

- Une seule devise de référence pour les totaux ; pas de conversion.
- Crédits à taux fixe uniquement.
- Synchronisation bancaire : **prototype** (Enable Banking), à valider avec un
  vrai compte ; étude, choix et limites dans
  [`docs/ETUDE-V5-SYNCHRONISATION-BANCAIRE.md`](docs/ETUDE-V5-SYNCHRONISATION-BANCAIRE.md).
- Installateur Windows non signé (avertissement SmartScreen) ; pas encore de
  paquet macOS (même script jpackage à adapter : `.dmg`, notarisation Apple).
- Pas de mise à jour automatique : l'application signale une nouvelle version
  (si l'option est cochée), l'installation reste manuelle.
