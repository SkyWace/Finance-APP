# FinanceApp — version web

La même application que la version desktop, dans le navigateur : disponible réel,
prévisions, opérations récurrentes, comptes, catégories, étiquettes.

**Les données financières ne quittent jamais le navigateur.** Le site n'est qu'un
ensemble de fichiers statiques : aucun serveur d'application, aucune base de données
en ligne, aucun compte à créer chez un tiers, aucune télémétrie. Héberger le site ne
donne accès à aucune donnée.

## Sécurité

- Chaque profil est chiffré dans le navigateur (IndexedDB) en **AES-256-GCM**, avec une
  clé de données aléatoire enveloppée par le **mot de passe maître** (PBKDF2-SHA-256,
  600 000 itérations) et par une **clé de récupération** remise une seule fois.
  Toute la cryptographie est celle du navigateur (WebCrypto).
- Aucun mot de passe n'est stocké. Seul le nom du profil est lisible sans mot de passe.
- **Verrouillage** : bouton, ou automatique après inactivité (5 minutes par défaut).
  La clé et les données quittent alors la mémoire de l'application.
- Un profil ne s'ouvre que dans **un seul onglet** à la fois.
- Politique de sécurité du contenu stricte : aucune ressource externe, aucune connexion
  à un autre site ; le site refuse de s'afficher dans le cadre d'une autre page.
- **Sauvegarde chiffrée** téléchargeable (*Paramètres → Sauvegardes*), restaurable depuis
  l'écran d'accueil, sur n'importe quel navigateur. Elle n'écrase jamais un profil existant.

À savoir : les données vivent dans **ce navigateur, sur cet appareil**. Vider les
données du site, réinitialiser le navigateur ou passer sur un autre ordinateur les fait
disparaître : téléchargez régulièrement une sauvegarde. Le site demande au navigateur de
conserver ses données même en cas de manque de place, mais il peut refuser.

La version web et la version desktop ne partagent pas leurs données : ce sont deux
applications indépendantes, avec les mêmes calculs.

## Développer

Prérequis : Node.js 22+.

```bash
cd web
npm ci
npm run dev        # http://localhost:5173
npm test           # tests (calculs, chiffrement, CSV)
npm run build      # site statique dans web/dist/
```

## Héberger

`npm run build` produit le dossier `web/dist/` : il suffit de **copier son contenu** sur
n'importe quel hébergement de fichiers statiques. Le site fonctionne dans n'importe quel
sous-dossier (chemins relatifs).

**Obligatoire : HTTPS.** Le chiffrement du navigateur (WebCrypto) n'est disponible que
sur une adresse `https://` (ou `http://localhost`). Tous les hébergeurs ci-dessous
fournissent le certificat gratuitement.

Le fichier construit est aussi disponible sans rien installer : sur GitHub, onglet
*Actions* → *Site web* → dernière exécution → artefact `financeapp-web`.

### Netlify ou Cloudflare Pages

Déposez le dossier `dist/` (ou reliez le dépôt avec : dossier de base `web`, commande
`npm ci && npm run build`, dossier publié `dist`). Les en-têtes de sécurité du fichier
`_headers` sont appliqués automatiquement.

### GitHub Pages

Publiez le contenu de `dist/`. GitHub Pages n'applique pas le fichier `_headers` : la
politique de sécurité incluse dans la page reste active, et le site refuse quand même
de s'afficher dans le cadre d'un autre site.

### Serveur à vous (nginx)

```nginx
server {
    listen 443 ssl http2;
    server_name finance.example.fr;
    root /var/www/financeapp;
    index index.html;

    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'" always;
    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
    add_header Strict-Transport-Security "max-age=31536000" always;

    location /assets/ { add_header Cache-Control "public, max-age=31536000, immutable" always; }
    location = /index.html { add_header Cache-Control "no-cache" always; }
}
```

### Apache (`.htaccess`)

```apache
Header always set Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'"
Header always set X-Frame-Options "DENY"
Header always set X-Content-Type-Options "nosniff"
Header always set Referrer-Policy "no-referrer"
```

Mettre à jour le site : remplacer les fichiers par ceux d'un nouveau `dist/`. Les données
des utilisateurs, stockées dans leur navigateur, ne sont pas touchées.

## Fonctionnalités de cette première version

Tableau de bord, comptes (comptes courants, épargne, espèces), transactions (dépenses,
revenus, virements internes, statuts, catégories, étiquettes, recherche et filtres,
export CSV), à venir (valider ou ignorer une échéance), récurrences, disponible réel
(fin de semaine, prochaine paie, fin du mois, date choisie, détail ligne à ligne),
prévisions jusqu'à 24 mois, catégories, import de relevé CSV avec aperçu, plusieurs
profils, thèmes sombre et clair, mode confidentialité, utilisable sur téléphone.

Pas encore dans la version web : budgets, objectifs, abonnements, analyses, crédits,
simulations, ventilation, justificatifs, import OFX/QIF.
