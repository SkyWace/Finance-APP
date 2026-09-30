# V5 — Étude : synchronisation bancaire

> Statut : **étude préalable, aucune implémentation.** Conformément au cahier
> des charges (« Ne pas implémenter cette partie sans étude spécifique
> préalable »), ce document sert à décider *si* et *comment* FinanceApp pourrait
> récupérer automatiquement les opérations bancaires. Il ne constitue pas un
> avis juridique : les points réglementaires sont à faire valider avant toute
> mise en production.
>
> Faits vérifiés en septembre 2026. Les offres des fournisseurs changent vite
> (voir GoCardless, § 5) : **revérifier au moment de décider**.

## 1. Objet et périmètre

- **Dans le périmètre** : lecture seule des comptes (soldes, opérations) —
  service d'*information sur les comptes* (AIS) au sens de la DSP2.
- **Hors périmètre, définitivement** : toute initiation de paiement (PIS),
  tout virement. FinanceApp n'est pas une banque.
- **Contraintes du cahier des charges, non négociables** :
  - ne jamais demander ni stocker les identifiants bancaires ;
  - données locales par défaut, aucune télémétrie financière ;
  - fonctionnalité **facultative** : l'application doit rester entièrement
    utilisable sans elle (import de fichiers V3 inchangé) ;
  - ne jamais importer silencieusement des opérations ambiguës.

## 2. Résumé et recommandation

1. FinanceApp **ne peut pas** se connecter directement aux API des banques :
   c'est réservé aux prestataires agréés/enregistrés (AISP) disposant de
   certificats eIDAS. Devenir AISP est disproportionné pour ce projet.
2. La seule voie compatible avec le « local-first » est un **agrégateur agréé
   utilisé avec les propres clés de l'utilisateur** (« apportez votre clé »),
   sans aucun serveur FinanceApp : les données vont de la banque à l'agrégateur
   puis directement à l'ordinateur de l'utilisateur.
3. Aujourd'hui, le candidat le plus adapté est **Enable Banking en mode
   « restricted production »** : gratuit pour un usage individuel non
   commercial sur ses propres comptes, authentification par clé privée RSA
   conservée localement, consentement jusqu'à 180 jours.
4. **Recommandation : GO conditionnel** pour un *prototype* isolé (module
   optionnel, désactivé par défaut), à condition que les points bloquants du
   § 10 soient levés : conditions d'utilisation du fournisseur, redirection
   OAuth vers une application de bureau, couverture de vos banques.
   Sinon, rester sur l'import de fichiers (déjà complet depuis la V3).

## 3. Cadre réglementaire

| Sujet | Ce qu'il faut retenir |
|---|---|
| DSP2 (directive (UE) 2015/2366), transposée dans le Code monétaire et financier | L'accès aux comptes pour le compte d'un client est un service de paiement (AIS). Il faut être **enregistré comme AISP** (en France auprès de l'ACPR, avec assurance de responsabilité civile professionnelle) ou passer par un prestataire qui l'est. |
| Interfaces dédiées (RTS, règlement délégué (UE) 2018/389) | Les banques exposent des API ; le prestataire doit **s'identifier** (certificats eIDAS QWAC/QSealC). La collecte des identifiants par un tiers (« screen scraping ») n'est plus la voie normale. |
| Authentification forte (SCA) | L'utilisateur s'authentifie **chez sa banque** (redirection ou application bancaire). Le consentement AIS dure **jusqu'à 180 jours** (règlement délégué (UE) 2022/2360, applicable depuis le 25/07/2023), puis doit être renouvelé. |
| Accès sans l'utilisateur | Au plus **4 consultations par jour et par compte** quand l'utilisateur n'est pas présent (RTS, art. 36). |
| RGPD | L'agrégateur traite des données financières de l'utilisateur : il faut une information claire et un consentement explicite, sans transfert vers FinanceApp. |
| DSP3 / RSP (règlement services de paiement) | **Accord politique provisoire le 27/11/2025**, textes pas encore en vigueur. Le RSP s'appliquera directement ; la DSP3 aura une application visée vers 2028. Objectif affiché : lever les obstacles des banques à l'open banking. À suivre, sans impact bloquant attendu sur une intégration via agrégateur. |
| FIDA (accès aux données financières) | Proposition de 2023, **toujours en négociation** (retrait envisagé puis écarté début 2025). Pourrait étendre l'accès à d'autres données (épargne, assurance) ; aucune dépendance à prévoir. |

Conséquence directe : les identifiants ne transitent **jamais** par FinanceApp.
L'utilisateur s'authentifie sur le site ou l'application de sa banque ; seul un
identifiant de session/consentement revient à l'application.

## 4. Options étudiées

| Critère | A. Statu quo : import de fichiers | B. Agrégateur, clé de l'utilisateur (sans serveur) | C. Agrégateur via un serveur FinanceApp | D. FinanceApp devient AISP |
|---|---|---|---|---|
| Données locales | ✓ totalement | ✓ banque → agrégateur → PC | ✗ transitent par un serveur | ✗ serveur + conformité lourde |
| Identifiants bancaires | jamais | jamais (SCA chez la banque) | jamais | jamais |
| Serveur à opérer | non | **non** | oui (sécurité, disponibilité, RGPD) | oui |
| Statut réglementaire requis | aucun | aucun pour FinanceApp (l'agrégateur est l'AISP ; à confirmer, § 10) | contrat B2B + sous-traitance RGPD | enregistrement ACPR, eIDAS, assurance |
| Coût | nul | nul en mode personnel (Enable Banking) | abonnement agrégateur | très élevé |
| Effort pour l'utilisateur | exporter un relevé | créer un compte développeur, lier ses comptes (une fois), renouveler tous les 180 j | faible | faible |
| Dépendance à un tiers | aucune | forte (conditions pouvant changer) | forte | banques |
| Verdict | à conserver dans tous les cas | **recommandée pour un prototype** | contraire au local-first, écartée | disproportionnée, écartée |

## 5. Fournisseurs (état en septembre 2026)

| Fournisseur | Usage individuel sans contrat | Remarques |
|---|---|---|
| **Enable Banking** (Finlande) | **Oui** : mode « restricted production », gratuit, usage individuel non commercial, limité aux comptes liés par l'utilisateur dans le portail | Authentification de l'application par **clé privée RSA** (jeton signé, 24 h max) ; consentement jusqu'à 180 jours selon la banque ; déjà utilisé par des logiciels auto-hébergés (Firefly III). Couverture des banques françaises à vérifier compte par compte. |
| GoCardless Bank Account Data (ex-Nordigen) | **Non** : inscriptions fermées depuis juillet 2025, orientation vers les grands comptes | Exemple concret du risque de dépendance : l'offre gratuite utilisée par Actual Budget a disparu pour les nouveaux utilisateurs. |
| Powens (ex-Budget Insight), Bridge, Tink, Salt Edge, TrueLayer, Yapily… | Non (offres B2B sous contrat) | Pertinents uniquement pour l'option C, écartée. |

Choisir un fournisseur ne doit pas enfermer l'application : l'architecture
proposée isole le fournisseur derrière une interface (§ 6).

## 6. Architecture proposée (si GO)

```
financeapp-core      port BankFeed (lecture seule) → List<ImportedRow>
                     réutilise ImportPlanner / ImportService (V3) : doublons,
                     rapprochements, Inbox « À valider », annulation d'un lot
financeapp-banksync  NOUVEAU module optionnel : adaptateur Enable Banking,
                     client HTTPS (java.net.http), seul module autorisé à
                     accéder au réseau ; absent du classpath = fonction absente
financeapp-desktop   écran « Synchronisation » (désactivé par défaut),
                     consentement explicite, état des liaisons, échéances
```

Principes :

- **Même chemin que l'import de fichiers.** Une synchronisation produit des
  `ImportedRow` (identifiant bancaire de l'opération → `external_id`) et passe
  par l'aperçu, la détection des doublons et l'Inbox. Aucune opération ambiguë
  n'est importée sans validation ; un lot synchronisé s'annule comme un import.
- **Opérations « en attente »** côté banque : importées au statut « en
  attente » ou ignorées jusqu'à comptabilisation (à trancher au prototype ;
  l'identifiant change parfois entre les deux états chez certaines banques).
- **Secrets** : clé privée RSA de l'application et identifiants de session
  stockés **chiffrés dans la base** (clé de données V1.1), jamais en clair sur
  le disque, jamais dans les journaux. Le verrouillage de l'application rend
  la synchronisation impossible.
- **Consentement** : ouverture du navigateur système vers la banque (SCA) ;
  retour vers l'application par redirection. Pour une application de bureau,
  la bonne pratique est une redirection vers `127.0.0.1` sur un port local
  éphémère (RFC 8252) ; **à vérifier** : le fournisseur doit accepter cette
  URL, sinon repli sur un copier-coller de l'URL de retour.
- **Fréquence** : manuelle ou au démarrage, au plus 4 fois par jour et par
  compte ; aucune tâche en arrière-plan quand l'application est fermée.
- **Réseau** : liste blanche d'hôtes du fournisseur, TLS du JDK, aucune autre
  destination ; aucune télémétrie.
- **Transparence** : l'écran indique qu'en activant la fonction, les données
  des comptes liés sont traitées par le fournisseur (nom, pays, lien vers ses
  conditions), et la date d'expiration de chaque consentement.

## 7. Modèle de menaces (résumé)

| Menace | Mesure |
|---|---|
| Vol de la clé privée ou des sessions sur le disque | stockage chiffré (SQLCipher) ; inutilisables sans le mot de passe maître |
| Fuite dans les journaux | aucun jeton, clé, IBAN complet ni montant dans les logs (règle existante étendue au module) |
| Hameçonnage du flux de consentement | l'authentification se fait uniquement sur le domaine de la banque, dans le navigateur système (jamais dans une fenêtre intégrée) |
| Interception réseau | HTTPS obligatoire, validation standard des certificats, hôtes en liste blanche |
| Réponse malformée ou malveillante de l'API | mêmes validations que l'import de fichiers ; montants en `BigDecimal` ; taille et nombre d'opérations bornés |
| Doublons fichier + synchronisation | identifiant bancaire + détection existante (même montant ± 3 jours, libellé équivalent) |
| Changement des conditions du fournisseur | module optionnel, adaptateur remplaçable, import de fichiers toujours disponible |

## 8. Limites connues

- Couverture et qualité variables selon les banques (libellés, opérations en
  attente, profondeur d'historique variable, souvent 90 jours).
- Renouvellement du consentement tous les 180 jours au plus (SCA).
- Mise en place par l'utilisateur plus technique qu'un import de fichier
  (compte développeur chez le fournisseur).
- Dépendance à un tiers gratuit dont l'offre peut disparaître.

## 9. Plan par étapes (uniquement après décision GO)

1. **Validation préalable** (sans code) : lecture des conditions d'utilisation
   d'Enable Banking (usage individuel, redistribution d'un logiciel qui
   l'utilise), liste de vos banques couvertes, question juridique du § 10.
2. **Prototype technique hors application** : lier un compte de test, vérifier
   la redirection locale, le format des opérations, l'identifiant stable.
   Critère de sortie : 30 jours d'opérations récupérées sans doublon ni perte.
3. **Module `financeapp-banksync`** derrière le port `BankFeed`, tests avec un
   faux serveur (aucun appel réseau dans les tests).
4. **Écran et consentement**, désactivé par défaut ; documentation utilisateur.
5. **Revue de sécurité** dédiée avant diffusion.

## 10. Points bloquants et décisions attendues

| # | Question | Qui tranche |
|---|---|---|
| 1 | GO / NO GO sur l'option B (sinon : import de fichiers seul, rien à faire) | vous |
| 2 | Vos banques sont-elles couvertes par Enable Banking ? | vous (liste du fournisseur) |
| 3 | Un logiciel distribué qui laisse chaque utilisateur employer **son propre** compte Enable Banking est-il conforme aux conditions du fournisseur et ne fait-il pas de FinanceApp un prestataire de services de paiement ? | conseil juridique / fournisseur |
| 4 | La mise en place « compte développeur + clé » est-elle acceptable pour les utilisateurs visés, ou réserve-t-on la fonction aux utilisateurs avancés ? | vous |
| 5 | Opérations « en attente » : importées ou ignorées jusqu'à comptabilisation ? | à décider au prototype |

Tant que ces points ne sont pas levés, **aucune ligne de code de
synchronisation ne doit être écrite**, conformément au cahier des charges.

## Sources

- DSP2 : directive (UE) 2015/2366 ; RTS : règlement délégué (UE) 2018/389 ;
  modification du délai de 90 à 180 jours : règlement délégué (UE) 2022/2360.
- GoCardless, inscriptions fermées :
  <https://bankaccountdata.gocardless.com/new-signups-disabled>
- Enable Banking, comptes liés et mode restreint :
  <https://enablebanking.com/docs/api/linked-accounts/> ;
  FAQ (clé RSA, 180 jours, 4 accès/jour) : <https://enablebanking.com/docs/faq/>
- Utilisation par Firefly III :
  <https://docs.firefly-iii.org/tutorials/data-importer/eb/>
- DSP3/RSP, accord provisoire du 27/11/2025 :
  <https://www.hoganlovells.com/en/publications/final-texts-for-psd3-and-psr-awaited-as-european-parliament-and-council-of-eu-announce-provisional> ;
  <https://www.nortonrosefulbright.com/en/knowledge/publications/cedd39c6/psd3-and-psr-from-provisional-agreement-to-2026-readiness>
- FIDA, statut : <https://www.finapi.io/en/fida-regulation-status-pending/>
