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

## Connexion à Vinted et logo (version 0.4.0)

Décision de l'utilisateur (6 novembre 2026) : faire les trois voies sûres et tester en bêta
un fonctionnement « comme Bleam ». Pas de connexion par identifiants stockés ni d'API privée.

- **Notifications Vinted** (`notifications/VintedNotificationListener`) : lecture locale des
  notifications du paquet `fr.vinted` uniquement, classement (vente, offre, message, favori),
  article reconnu par son nom, montant extrait, purge à 60 jours. Écran « Activité Vinted »
  (icône cloche) avec actions vers la fiche ou l'assistant. Contenu exact des notifications
  Vinted non documenté : classement par mots-clés, à ajuster sur des exemples réels.
  APK hors Play Store : Android peut demander d'« Autoriser les paramètres restreints ».
- **Liens** : champ « Lien de l'annonce Vinted » par fiche, ouverture de l'annonce dans
  l'application Vinted, réception d'un lien partagé depuis Vinted (« Partager » → Vinted Assistant).
- **Export RGPD** (`data/importer/VintedExportParser`) : lecture tolérante du ZIP (JSON, CSV),
  articles importés en brouillon sans doublon, rapport de structure copiable sans données
  personnelles pour adapter l'import au format réel.
- **Bêta « Vinted intégré »** (`ui/vinted/`) : vinted.fr dans un WebView, connexion faite par
  l'utilisateur. Sur appui : remplissage titre, description, prix ; photos de la fiche fournies
  directement au sélecteur de fichiers de la page ; « Lier cette page » ; diagnostic de la page
  (champs et boutons, sans valeurs ni mot de passe) pour écrire ensuite des automatisations
  ciblées. Avertissement CGU à accepter. Aucun clic automatique, aucune publication seule,
  aucune protection contournée, pas de messages en masse. Téléphone déverrouillé seulement.
- **Logo** : icône adaptative originale (étiquette ambre, flèche montante, fond indigo)
  avec version monochrome.
- Base v3 (migration automatique 2 → 3). 12 tests sur émulateur, tests JVM de l'import.

## Import Vinted, achats fournisseur, stock pièce par pièce (versions 0.5.0 à 0.8.0)

- **0.5 / 0.6** : import du dressing depuis la bêta (annonces en ligne et vendues, photos,
  favoris, vues), ventes passées créées sans date connue (comptées « depuis le début », pas dans
  le mois), mots-clés séparés de la description. Base v4 puis v5.
- **0.7** : module Achats (`data/PurchaseRepository`, `domain/Purchases.kt`). Lecture des pages
  de commande Nihaojewelry, coût de revient = prix payé + part du port et des frais au prorata
  de la valeur, rattachement des fiches, réimport qui met à jour sans perdre les rattachements.
  Base v6. 0.7.1 : version affichée, arrondis de quelques centimes ignorés.
- **0.8** : stock pièce par pièce (`domain/StockLedger.kt`, calcul pur testé en JVM).
  - Un même bijou (nom et variante, sans accents ni casse, plus le fichier de sa photo
    fournisseur) est regroupé d'une commande à l'autre ; chaque commande forme un lot avec son
    propre coût de revient. La prochaine fiche prend le lot le plus ancien qui a des pièces libres.
  - Chaque pièce achetée est classée : pas encore en vente (aucune fiche), brouillon, en ligne
    ou réservée, vendue, retirée. Les fiches sans achat rattaché sont listées à part.
  - Remise en vente (`PurchaseRepository.duplicateForSale`) : nouvelle fiche brouillon copiée
    (texte, prix, photos), rattachée au lot libre, coût de ce lot. L'ancienne fiche et sa vente
    ne changent pas. Refus si le lot n'a plus de pièce libre.
  - Import du dressing : une fiche sans lien Vinted, en stock, de titre identique est reconnue
    comme l'annonce publiée (évite un doublon après une remise en vente).
  - Écran Stock refait : boutons « Vinted intégré » (déplacé depuis les réglages) et « Achats
    fournisseur », bilan « Mon stock », vues avec compteurs, rattachement dans les deux sens avec
    les choix les plus ressemblants en premier. Pas de changement de schéma.

- **0.9** : base v7 (migration automatique 6 → 7, valeurs par défaut : les commandes déjà
  enregistrées sont « reçues »).
  - Code produit fournisseur (`sku`, lu sur la ligne « Sku: » de Nihaojewelry). Clé d'un bijou :
    code + variante, sans les codes internes (lettres et chiffres mêlés, 6 caractères ou plus).
    Sans code (commandes lues avant la 0.9) : ancienne clé nom + photo. Au réimport, les
    rattachements suivent le code produit, puis la position.
  - Commande reçue ou en cours de livraison (`received`, `delivery_eta`). Nouvelle commande en
    livraison si le site annonce une livraison ou si elle a moins de 30 jours ; achat saisi à la
    main reçu d'office ; état conservé au réimport. Pièces en livraison comptées à part, sans
    fiche ni remise en vente possibles avant « J'ai reçu cette commande ».

- **0.10** : export Vinted RGPD réel (ZIP de pages HTML, novembre 2026).
  - `VintedHtmlExport` lit `listings/`, `orders/` et `deleted_items/index.html` (blocs
    `<div class="cell" itemscope>`, valeurs en `itemprop`). Numéro d'annonce tiré du dossier des photos.
  - `ExportSales` : chaque article nommé dans une commande est rattaché à l'annonce de même titre mise
    en ligne avant la commande (la plus ancienne en cas d'annonces identiques). Commande sans titre :
    rattachée seulement sans ambiguïté. Commandes annulées et antérieures à la première annonce ignorées.
    Sur l'export réel : 25 ventes, 165,10 €, 0 commande non rattachée, 8 annonces en vente.
  - Import : fusion par numéro d'annonce, ou titre identique sur une fiche sans lien ou liée à une annonce
    supprimée (republication). Champs vides complétés, ventes datées avec le montant réel, ventes
    manquantes ajoutées, photos de l'archive pour les fiches qui n'en ont pas. Réimport sans effet.
  - Export des données de l'application (`DataExport`, `DataAudit`) : ZIP `rapport.txt` + `donnees.json`,
    sans photos, pseudos d'acheteurs ni messages, partagé par le menu Android (cache privé, FileProvider).

- **0.10.1 / 0.10.2** : lecture des commandes Nihaojewelry fiabilisée. Dates et libellés avec espaces
  insécables. Produits offerts reconnus par le drapeau « Essai gratuit » ou par un total égal au port.
  Produits repérés par leur ligne « Sku: » et non plus par leur photo : une photo pas encore chargée
  faisait sauter le produit (2 pendentifs perdus sur 24 dans la commande d'août, reproduit sous Chromium).
  Contrôle du nombre de pièces annoncé par le site. Au réimport, un produit nouvellement lu ne reprend
  jamais par position le rattachement d'un autre.

- **0.10.3** : la page Nihaojewelry s'affiche en anglais dans l'application (« Order Qty », « Aug 20, 2026 »).
  Dates au format anglais (mois en premier, AM/PM), libellés de livraison anglais. Variante coupée avant
  « Order Qty », « Weight », prix. Photo : plus grande image à peu près carrée de la ligne (le bandeau
  « SHIP » est écarté). Produit sans titre : titre du lien ou de l'image, sinon « Produit <Sku> ».
  Réimport : le code produit prime sur le nom pour garder les rattachements.

- **0.10.4** : rattachements rétablis depuis un export de l'application (Achats → « Rétablir les
  rattachements d'un export », par référence de commande et code produit). Commande « en livraison »
  seulement si le site annonce une livraison. Photo : images de moins de 50 px, non carrées, icônes,
  panier, bandeaux et `data:` écartés. « Envoyer la page » : copie de la page fournisseur pour
  diagnostic, sans scripts ni styles, adresse, e-mails et téléphones retirés.

- **0.10.5** : retour à la lecture d'origine des commandes (photo produit visible → ligne), éprouvée sur la
  vraie page ; la lecture par ligne « Sku: » ne sert plus qu'à rattraper les produits manqués. Icônes et
  bandeaux écartés. Rétablissement des rattachements : choix de fichier libre et erreur détaillée.

- **0.10.6** : date de commande modifiable à la main (calendrier) et conservée au réimport si la page reste
  illisible. Fiche rattachée : commande et coût affichés, « Changer d'achat ». Choix de l'achat par
  commande (lot) et non plus par bijou, pour distinguer deux commandes du même bijou.

- **0.11** : fichier de corrections (`data/Corrections.kt`) préparé à partir d'un export : dates de commande,
  fiches à supprimer, rattachements à poser ou déplacer. Chaque étape vérifie le titre (et le statut) de la
  fiche visée et la place libre, en simulant l'effet des étapes précédentes ; aperçu ✓/✗ et confirmation.

- **0.11.1** : lecture des commandes réécrite d'après la vraie page (copies du 7 novembre) : une
  `.product-item` par produit, photo `img.img`, nom sans le badge « FREE TRIAL », code et variante de la
  ligne « SKU: », quantité commandée moins annulée, prix payé, date du bloc « Order Date », totaux du
  bloc `.pay-data`. L'image cachée `freeShip-img` (le « SHIP ») était prise pour la photo. Le coupon est déjà
  déduit des prix unitaires payés : pas de frais négatifs. Lecture générique gardée en secours.
  Validé sous Chromium sur les deux pages réelles (13 et 24 produits, dates, photos, noms).

- **0.12** : publication assistée, étape 1. « Remplir » enchaîne titre, description, prix puis la catégorie :
  ouverture du menu Vinted, recherche (« bracelet », « collier »…) dans « Trouver une catégorie », choix du
  résultat le plus sûr (type, genre, « bijou » ; enfants et autre genre écartés), validation « Fait ». En
  cas de doute, rien n'est choisi et le détail (options vues, relevé d'affichage) s'affiche. Correctif
  d'affichage de la boîte des catégories (opacité et visibilité forcées). Le bouton « Ajouter » reste à
  toucher par l'utilisateur.

- **0.13** : publication assistée, étape 2. « Remplir » ajoute les photos (ouverture du sélecteur Vinted,
  servi directement par l'application ; sauté si des photos sont déjà là), puis, après la catégorie, les
  détails relevés le 8 novembre : « Pour qui ? », Marque, État, Couleur (2 au plus), Matériau. Valeurs
  candidates par champ (`DetailsGuess`), la première qui existe dans le menu est cochée ; un champ déjà rempli
  n'est jamais modifié ; les manques sont affichés avec les options vues.

- **0.13.2** : « Partir d'une fiche existante » : copie d'une fiche du même modèle (toutes couleurs, même vendue).
- **0.14** : « Améliorer » une photo (`data/files/PhotoEnhancer`, `domain/PhotoLayout`) : détourage ML Kit Subject
  Segmentation (gratuit, sur le téléphone, modèle via Google Play services téléchargé une fois), bijou centré
  à 80 % sur un fond carré blanc cassé 1080 px, ombre douce, réglage de lumière plafonné (gain ≤ 1,3).
  Le bijou n'est pas redessiné : vraie photo, arrière-plan seul remplacé. Version ajoutée en première
  position, originale conservée. Pas de photo générée par IA (règles Vinted : photos du vrai objet).

- **0.15** : ventes enregistrées d'office. Notification Vinted de vente : fiche reconnue même au titre coupé
  (début de 20 caractères au moins), plus ancienne des fiches identiques, aucune devinette si deux titres
  différents se valent ; prix lu s'il est plausible (entre la moitié et le prix affiché), sinon prix affiché
  signalé « à corriger » ; statut « À expédier » ; notification de confirmation ouvrant l'onglet Ventes.
  Import du dressing : une fiche en vente dans l'application et vue vendue devient une vente datée du jour,
  « À expédier », au prix affiché signalé à corriger (et non plus une vente sans date « Livrée »).

- **0.15.1** : catégorie déduite du titre (`domain/JewelryCategory`) pour les fiches et ventes importées sans catégorie.

- **0.16** : fiabilité et suivi au quotidien.
  - Sauvegarde complète (`data/Backup`) : base copiée par `VACUUM INTO` (instantané cohérent, Android 11+),
    photos, bordereaux, réglages, dans un ZIP avec `manifest.json`. Automatique chaque semaine (WorkManager,
    batterie et stockage suffisants, rien si l'application est vide) dans Téléchargements/VintedAssistant
    via MediaStore, sans permission ; les 4 plus récentes gardées. Envoi à la demande (Drive, e-mail).
  - Restauration : lecture dans le cache, contrôle du format, d'`integrity_check` et de la version du schéma
    (refus d'une sauvegarde plus récente que l'application ; plus ancienne : migrations Room habituelles),
    protection contre les chemins hors dossier, copie de sécurité de l'état actuel, fichiers puis base
    (renommage), redémarrage.
  - Délai d'expédition (`domain/Shipping`) : 5 jours ouvrés après la vente (aide Vinted), week-ends et jours
    fériés français exclus (Pâques par l'algorithme de Meeus). Date limite sur chaque vente « À expédier »,
    bouton « Colis déposé », encart sur l'accueil, notification vers 9 h si un colis doit partir aujourd'hui
    ou au prochain jour ouvré. Les ventes sans date connue n'ont pas de délai.
  - Prix conseillé des articles qui stagnent : prix médian des ventes de modèles proches (même type de bijou,
    60 % de mots communs, deux ventes au moins) s'il est plus bas, sinon la baisse en pourcentage ; plancher respecté.
  - Accueil : « À vérifier » (contrôles de `DataAudit`, dont les ventes sans achat rattaché), fiches ouvrables.

- **0.16.1** : « Remplir » ouvre d'abord le formulaire « Vendre » s'il n'est pas affiché et attend qu'il soit
  chargé ; la catégorie attend son champ jusqu'à 8 s et le cherche aussi par son libellé. En cas d'échec,
  le diagnostic donne la page et la liste des champs (identifiants techniques et libellés, aucune donnée saisie).

- **0.16.2** : catégorie : la boîte de Vinted s'ouvrait vide (hauteur 0) le temps de charger sa liste.
  Le script attend maintenant les lignes (5 s) puis les résultats de la recherche (6 s), et chaque niveau
  (3 s). En cas d'échec, le diagnostic donne le nombre de lignes, le texte visible de la boîte et ses classes.

- **0.16.3** : ventes enregistrées avant le rattachement de leur fiche à un achat : coût repris de la fiche
  (au démarrage et à chaque rattachement, pour toute vente à coût nul). « À vérifier » signale aussi les
  ventes sans coût et les ventes sans fiche.

- **0.16.4** : détails Vinted : la marque n'est cochée que si le nom est exactement celui de la fiche (sinon
  « Sans marque ») : « Vintage » avait donné « Vintage Love », une vraie marque. Les menus attendent leurs options
  (5 s à l'ouverture, 4 s après une recherche) : Couleur et Matériau étaient lus avant d'être chargés.

- **0.16.5** : après « Neuf sans étiquette », Vinted ouvre une fenêtre d'information (« L'article ne doit jamais
  avoir été utilisé… ») qui masquait Couleur et Matériau. Toute fenêtre sans options est refermée (bouton
  « J'ai compris », « OK »… ou fermeture) avant et après chaque champ ; le bouton touché est noté dans le détail.

- **0.16.6** : « Rattacher à un achat » sur une vente sans fiche (lot Vinted, pièce envoyée à la place d'une
  autre) : choix de la pièce sortie du stock parmi les produits reçus qui ont encore des pièces ; création
  d'une fiche « Vendu » rattachée, coût d'achat repris sur la vente, pièce envoyée notée dans la vente.

- **0.17** : republication en série (onglet Republier, « Tout republier en série »). Pour chaque annonce du plan :
  formulaire « Vendre » rempli (photo principale décalée chaque semaine pour ne jamais republier à l'identique),
  l'utilisateur touche « Ajouter » ; la nouvelle annonce est reconnue à son adresse (suivi aussi de la navigation
  interne de Vinted via `doUpdateVisitedHistory`), la fiche est notée republiée et reliée au nouveau lien ;
  l'ancienne annonce s'ouvre, l'utilisateur touche « Supprimer » ; passage à la suivante quand il la quitte.
  Boutons de secours « Elle est en ligne », « Suivante », « Arrêter ». Aucun délai simulé, aucune action à la
  place de l'utilisateur sur « Ajouter » et « Supprimer » : rien qui cherche à tromper la détection de Vinted.

- **0.18** : réponses préparées (`domain/QuickReply`, `notifications/ReplyNotifier`). Notification Vinted de message,
  d'offre ou de favori sur un article reconnu : notification « Réponse prête » avec le texte (modèle de réponse,
  conseil d'offre selon le plancher, offre au favori selon la baisse réglée), boutons « Copier » et « Ouvrir Vinted ».
  Envoi toujours fait par l'utilisateur. Un message sans titre d'article reconnaissable ne donne pas de réponse.
  Série de republication : les fiches sans lien vers l'annonce actuelle sont écartées (l'ancienne ne pourrait pas
  être supprimée, doublon garanti).

- **0.18.1** : prix : « 6,00 € » s'affichait mais Vinted répondait « Le champ prix doit être supérieur ou égal à 1.0 »
  (valeur affichée, pas enregistrée par React). Saisie désormais « comme au clavier » (`execCommand('insertText')`),
  repli sur l'ancienne méthode ; le prix n'est plus posé au début du remplissage, seulement à la fin ; la vérification
  lit aussi le message d'erreur sous le champ et retente avec la virgule.

- **0.19** : pièces commandées jamais reçues (base v8, colonne `purchase_items.missing`, migration automatique 7 → 8).
  Achats : « Pas reçu ? » sur chaque produit d'une commande reçue ; les pièces signalées sortent du stock, les frais
  de port sont répartis à nouveau sur les seules pièces reçues (coût des fiches en stock mis à jour, ventes passées
  inchangées) ; le signalement survit à une nouvelle lecture de la commande. CI : schémas Room publiés en artefact.

- **0.20** : mise en vente express (`domain/ExpressListing`, `ui/screens/ExpressScreen`). Stock, « Pas encore en
  vente », « Tout mettre en vente » : une fiche brouillon par bijou qui a des pièces reçues sans fiche. Copie d'une
  fiche du même bijou (photos comprises) ou d'une autre couleur du même modèle (photos retirées, couleur vidée) ;
  sans modèle, fiche partie du nom fournisseur, sans photo fournisseur, catégorie déduite, prix médian des ventes
  proches. Écran pièce par pièce : appareil photo ou galerie, « Améliorer la 1re », titre, prix, rédaction par l'IA.
  Les fiches prêtes (photos et prix) partent ensuite dans Vinted intégré en série : l'utilisateur touche
  « Ajouter », l'annonce est reliée à la fiche, puis la suivante. Photo principale décalée seulement en republication.

- **0.21** : conseiller de réassort (`domain/Restock`). Accueil, « À racheter » : bijoux vendus au moins deux fois
  dont il reste une pièce au plus (livraison en cours comprise), classés par bénéfice total, avec délai moyen de
  vente, coût, prix et marge par pièce. Projection du chiffre d'affaires du mois au rythme actuel, comparée au
  mois précédent.

## Étapes suivantes

- Ajuster le remplissage bêta et le classement des notifications avec le diagnostic et des
  exemples réels transmis par l'utilisateur.
- Chiffrement optionnel des sauvegardes (mot de passe), si elles sont envoyées hors du téléphone.
- Mise à jour des versions (AGP, Kotlin, Compose) dans un commit séparé, une fois l'usage validé.
