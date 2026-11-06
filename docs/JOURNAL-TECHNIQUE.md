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

## Étapes suivantes

- Étape B : passage du stockage SharedPreferences à Room, avec reprise des articles existants.
- Puis lots 2 à 7 du rapport d'audit.
