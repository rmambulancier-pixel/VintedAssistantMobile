# Journal technique Vinted Boost

Dépôt de référence : `rmambulancier-pixel/VintedAssistantMobile`.
La branche `vinted-assistant-mobile-v0.1` est conservée intacte comme point de retour.
Le travail se fait sur `dev/reprise` ; fusion dans `main` uniquement par pull request validée.

## Principes validés (6 novembre 2026)

- Aucune action automatisée sur Vinted : pas de bot, pas de service d'accessibilité, pas de serveur connecté au compte.
- Republication **assistée** : l'application prépare et planifie, l'utilisateur fait le dernier geste.
- Budget de fonctionnement : 0 € par mois. Pas de serveur.
- IA sur l'appareil (Gemini Nano via ML Kit) avec repli sans IA.

## Étape A : réparation du build

Constat de l'audit : les deux builds GitHub d'octobre ont échoué à l'installation du SDK Android,
avant la compilation. Aucune APK n'a jamais été produite.

Corrections :

1. Ajout du wrapper Gradle 8.14.3 (`gradlew`) : build reproductible, même version partout.
2. Workflow : appel de `sdkmanager` par son chemin complet, build via `./gradlew`,
   exécution des tests unitaires, déclenchement sur les branches `dev/**`.
3. `kotlinOptions` (refusé depuis Kotlin 2.2) remplacé par `kotlin { compilerOptions { … } }`.

Versions inchangées à ce stade (AGP 8.13.0, Kotlin 2.2.20, Compose BOM 2025.10.00) :
on obtient d'abord un build vert, la mise à jour des versions fera l'objet d'un commit séparé.

Résultat : build vert sur GitHub Actions le 6 novembre 2026, première APK produite.

## Étape B : base Room et reprise des données (version 0.2.0)

- Room 2.8.4 + KSP 2.2.20-2.0.4. Base `vinted_boost.db`, table `articles`, schéma v1
  versionné dans `app/schemas` (sert de référence pour écrire et tester les migrations).
- Montants stockés en **centimes** (`Long`) : plus d'erreurs d'arrondi des `Double`.
- Reprise des articles de la v0.1 (`LegacyInventoryImporter`) :
  - une seule fois, marquée par `legacy_imported_to_room_v1` ;
  - idempotente (mêmes identifiants, insertion qui ignore les doublons) ;
  - l'ancienne copie dans les SharedPreferences n'est **pas** effacée ;
  - JSON illisible : rien n'est marqué comme fait, message affiché, données conservées.
- Règle : jamais de migration destructive (`fallbackToDestructiveMigration` interdit).
- Écran branché sur un ViewModel : saisie conservée à la rotation, confirmation avant suppression.
- Tests unitaires : montants (saisie, affichage, débordement), validation, lecture du format v0.1.

Non couvert par les tests automatiques à ce stade (pas d'émulateur dans la CI) :
requêtes Room réelles et import de bout en bout. À vérifier sur le Pixel avec la recette ci-dessous.

### Recette sur le téléphone (v0.1 vers v0.2)

1. Avec la v0.1 installée, saisir deux articles (dont un avec centimes, ex. 19,99).
2. Installer la v0.2 **par-dessus**, sans désinstaller.
3. Attendu : message « 2 article(s) repris », mêmes montants, marge totale identique.
4. Fermer et rouvrir l'application : pas de nouveau message, pas de doublon.
5. Ajouter, supprimer (avec confirmation), tourner l'écran pendant la saisie.
6. Redémarrer le téléphone : les données sont toujours là.

## Lots 2 à 6 (version 0.3.0)

Choix confirmé : **republication assistée**, aucune action automatique sur Vinted.
Un éventuel mode automatique (service d'accessibilité) reste une décision de l'utilisateur,
non développée : contraire aux CGU (section 6), et inopérant téléphone verrouillé.
Refus définitif : modifier les photos pour échapper à la détection de Vinted.

### Organisation du code

| Dossier | Rôle |
|---|---|
| `domain/` | Règles métier pures, sans Android : montants, statuts, finances, tableau de bord, plan de republication, négociation, modèles de texte. Testées sur JVM. |
| `data/local/` | Room v2 : `articles`, `article_photos`, `sales`, `listing_events`. Migration automatique 1 → 2. |
| `data/files/` | Photos (redressées, 1600 px max, JPEG) et bordereaux dans l'espace privé, partage par FileProvider. |
| `data/` | `ArticleRepository` (transactions), `SettingsStore` (réglages bornés). |
| `ai/` | Gemini Nano via ML Kit Prompt API (bêta) + repli sur les modèles de texte. |
| `reminders/` | Rappel quotidien WorkManager, ouvre l'onglet Republier. |
| `ui/` | 5 onglets (Accueil, Stock, Republier, Ventes, Assistant), fiche article, réglages. Navigation par état. |

### Fonctions

- **Lot 2, Stock et Finances** : fiche complète (photos, marque, taille, catégorie, état, couleur,
  prix plancher), statuts, recherche et filtres ; bénéfice net = vente − coût − frais − cotisations
  (taux réglable, 0 par défaut).
- **Lot 3, Annonces** : rédaction par modèle ou par IA, copie du titre et de la description,
  envoi des photos vers Vinted par le menu de partage, « C'est publié » / « C'est republié ».
- **Lot 4, Republication assistée** : plan du jour (âge minimal, quota quotidien, plus anciens
  d'abord), préparation en un geste, journal, rappel quotidien, baisses de prix suggérées.
- **Lot 5, Assistant** : réponses aux acheteurs (modèle ou IA), conseil de négociation sous
  prix plancher avec message prêt, message aux favoris. Copie, jamais d'envoi automatique.
- **Lot 6, Ventes et pilotage** : vente avec instantané du coût, expédition (à expédier,
  expédiée, livrée, annulée), bordereaux, partage groupé des bordereaux, tableau de bord
  (mois, total, stock, délai de vente, articles qui stagnent, catégories).

### Tests

- 39 tests JVM (domaine, lecture des réponses IA, heure du rappel).
- Tests sur émulateur Android 14 en CI : migration d'une base v1 réelle, opérations du dépôt
  (prix, publication, vente, annulation, suppression, reprise v0.1).
- Non automatisable ici : IA Gemini Nano (absente de l'émulateur), partage vers Vinted,
  notifications. À vérifier sur le Pixel.

### Limites connues

- L'IA n'est validée par Google qu'en anglais : consignes en anglais, réponse demandée en
  français, qualité à juger. Elle ne fonctionne qu'application ouverte (contrainte Android).
- Vinted n'expose pas la liste des favoris : le message est préparé, l'envoi se fait dans Vinted.
- L'heure du rappel n'est pas garantie à la minute (WorkManager).
- Pas encore de sauvegarde exportable : prochaine priorité.

## Étapes suivantes

- Sauvegarde et restauration (export chiffré données + photos).
- Mise à jour des versions (AGP, Kotlin, Compose) dans un commit séparé, une fois l'usage validé.
