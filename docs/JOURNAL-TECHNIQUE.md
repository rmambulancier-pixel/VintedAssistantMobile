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

## Étapes suivantes

- Lot 2 du rapport d'audit : fiche article complète (photos, catégorie, état, frais) et finances.
