# QUESTIONS.md — Vision produit Nexus, état de la discussion

Doc de travail pour continuer la session de questions/réponses sur la vision produit.
Réponds directement dans la section « Questions ouvertes » en bas, en vrac, ça suffit.
Objectif final : consolider tout ça dans un `VISION.md` propre à côté du BUSSPEC.

---

## 1. La vision (validée)

**Nexus = le Even Realities Hub de l'écosystème Rokid.**

- Un seul ancrage côté lunettes (glasses hub + overlay launcher). L'utilisateur
  n'installe plus jamais rien sur les lunettes.
- Toute la valeur vit dans des apps/plugins **téléphone** qui se branchent sur le bus :
  installer un APK phone → le plugin apparaît sur les lunettes.
- Le seamless vient de : wake-on-message (pas besoin que l'app tourne), session CXR-L
  unique possédée par le hub (plus de guerre pour le lien), surfaces déclaratives
  (zéro déploiement lunettes).
- Douleur d'origine : « trop relou de lancer app par app sur les glasses ».

## 2. Décisions déjà prises

| Sujet | Décision |
|---|---|
| Cible | **Release publique** (pas juste power users) |
| Plugins tiers | **Les deux** : ouverts (n'importe quel APK + consentement) ET curés via RokidBrew |
| Ambiant vs launcher | **Pas de launcher complet.** Menu contextuel overlay sur geste (triple-tap ou autre) pour lancer les plugins. Le launcher + widgets Rokid restent en place. Widget « Nexus glance » dans le launcher Rokid = bonus à explorer si leur API widget est ouverte aux apps tierces |
| Interruptions | User-actionable → passe au-dessus ; sinon → toast |
| Apps natives lunettes (Scouter, NewPipe…) | Hors Nexus (grosses apps à ouvrir). MAIS le menu contextuel pourrait les **lancer** (a11y peut démarrer des activities) — porter ≠ lancer, à confirmer |
| iOS | Android only assumé pour le moment |
| Host app | Hi Rokid **Global uniquement** pour l'instant, China à voir plus tard |

## 3. Analyse des ports (candidats plugins)

**Simples, à faire en premier :**
1. **Tasker-Bridge** — le plus simple. Déjà le modèle « logique phone + menu HUD ».
   Tout son transport custom (BLE wake, RFCOMM, helper APK CXR-L) disparaît, remplacé
   par le bus. Reste : lister les tâches Tasker, surface menu, lancer sur input.
   Valide le multi-plugins du launcher.
2. **Plugin transit façon [even-transit](https://github.com/langerhans/even-transit)** —
   contenu purement textuel (ligne, direction, minutes) → kind `card` tel quel.
   even-transit est motorisé par Motis, et Transitous EST une instance Motis : le
   critère d'acceptance Round A incluait déjà un fetch Transitous via `/http/request`.
   Chemin data déjà validé. Complexité ~Tasker-Bridge.
3. **Rokid-Scribe** — premier vrai consommateur de l'audio lease (Round B slice 2) :
   mic lunettes → STT phone (code déjà écrit dans Scribe/Relay/openwhispr). Surface
   minimale (enregistrer / statut / durée).
4. **Rokid-Relay** — déjà sur la roadmap BUSSPEC (Round C+). Prérequis mic levé,
   mais reste notification listener + direct reply + machine à états. Moyen.

**Dégradé ou plus tard :**
- **Rokid-GMaps** — possible en texte dans une `card` tout de suite ; le vrai HUD nav
  demande un nouveau kind de surface `nav` (glyphes de manœuvre + distance), rendu
  localement par le glasses hub. Ajout de protocole modeste.
- **Live Studio** — nécessite un **camera/media lease** calqué sur l'audio lease v1 :
  hub démarre le flux CXR (`MEDIA_STREAM_SERVICE`), livre au leaseholder phone en AIDL
  local (zéro bus, comme le mic). Encodeur RTMP reste côté phone. À valider : leases
  audio+vidéo simultanés, plafond binder local 512 KiB par frame. Round C+.

**Mauvais fit plugin :** Rokid-Shell, NewPipe, DragonBallScouter, OverlayRec (natives
lunettes), Rokid-APKs (install APK = travail hub, pas plugin), R08-Access-Bridge
(périphérique d'entrée — voir question inputs), FoodFacts/Live-Studio sans camera lease.

## 4. Idées d'apps futures (par palier de capacités)

**Dès aujourd'hui** (card/timed-lines + input + proxy HTTP + mic) :
- **Sous-titres live / traduction temps réel** — killer app de l'audio lease :
  mic lunettes → STT phone → surface. Accessibilité + traduction de conversation.
- Assistant vocal (mic → LLM → card).
- Téléprompteur (`timed-lines` est littéralement fait pour ça).
- Glanceables : agenda, météo, RSS, bourse/crypto, scores sportifs.
- Listes interactives : recettes pas-à-pas, checklists, flashcards Anki.
- Domotique : Home Assistant en menu (structurellement = Tasker-Bridge).

**Avec petit enrichissement du protocole surface** (glyphes, jauges, bitmap 1-bit ?) :
- Nav turn-by-turn (kind `nav`).
- HUD sport (vélo/course : allure, cadence, FC via BLE phone).
- Suivi glycémie CGM (Dexcom/Libre) — santé, très demandé.
- Contrôles musique enrichis (progression, volume).

**Avec camera lease** : Live Studio, FoodFacts (scan produits), assistant visuel
(« qu'est-ce que je regarde ? »), OCR/traduction de panneaux.

**Point structurel** : plugins = APK phone ordinaires sur `:bus-client`, zéro install
lunettes → Nexus est une *plateforme* pour devs tiers. Distribution via RokidBrew :
le store distribue des plugins Nexus. La boucle écosystème se ferme.

## 5. Le projet Lens-traduction (gros morceau voulu)

Objectif : Google Lens-like — traduction sur retour caméra affiché dans les lunettes,
gratuit, temps réel.

**Deux modes, les deux prouvés par Rokid-DragonBallScouter :**
- **Sur aperçu caméra** (= mode `LIVE CAM LOCK` du Scouter) : preview caméra native
  lunettes + overlay traduit dessiné dessus. Alignement trivial. **Certain.**
- **Aligné sans aperçu** (= mode `ANGULAR HUD` du Scouter) : projection angulaire
  pseudo-AR dans le display. Faisable en « bien placé » (pas pixel-perfect) :
  projection angulaire existante + écran de calibration par utilisateur + ancrage IMU
  entre deux mises à jour OCR. Le README du Scouter note déjà que l'alignement varie
  selon device/fit.

**Architecture split compute — PAS besoin du camera lease CXR :**
1. Lunettes : caméra native + rendu overlay (le Scouter prouve l'accès caméra natif).
   Zéro ML sur le SoC lunettes.
2. Frames JPEG (~60-120 Ko en 640×480) → phone via frames binaires SPP (Round B,
   plafond 2 MiB). ~1 frame/s à ~1,5 Mbit/s réels : suffisant, le texte est statique.
3. Phone : ML Kit Text Recognition v2 (OCR) + ML Kit Translation — **gratuit,
   on-device, offline** (~50 langues, modèles ~30 Mo). Étape clé : stabilisation
   (dédupliquer les blocs entre frames, ne retraduire que ce qui change).
4. Retour : blocs traduits + coordonnées normalisées → lunettes dessinent, et font
   vivre l'overlay localement entre deux réponses (IMU / gel pendant le mouvement).

Latence ~1 s par rafraîchissement de scène, overlay fluide car rendu local.
Coût récurrent : zéro.

**Friction avec le modèle plugin** : demande du code côté lunettes. Le modèle plugin
n'interdit PAS le code lunettes — il définit ce qui est « gratuit » (surface = zéro
install). Une app lunettes cliente du bus est déjà légale (`:glasses-client-probe` le
fait). Deux voies : (a) le glasses hub gagne un kind `lens` (ouvre la caméra lui-même,
rend les blocs poussés par le phone) ; (b) app lunettes autonome sur `:bus-client` +
« install/bootstrap of glasses apps via Hi Rokid » (Round C+) pour l'install auto.
La voie (b) recycle le code caméra+overlay du Scouter.

## 6. Points d'architecture soulevés (à trancher / au spec)

1. **Arbitrage d'affichage** — dès 2 plugins vivants : priorités, pile de surfaces,
   couche toast. Absent du protocole v1. Premier problème du 2e plugin livré.
2. **Modèle de confiance — contradiction latente** : le BUSSPEC liste « custom
   signature permission » en futur, mais une permission signature TUE les plugins
   tiers. Pour une plateforme ouverte → modèle « notification listener » : n'importe
   quelle app demande, l'utilisateur approuve dans le phone hub.
3. **Onboarding sans ADB** — le glasses hub est « armed once via ADB ». Pour du
   public c'est un mur. L'item Round C+ « install/bootstrap of glasses apps » est
   plus stratégique que sa place ne le suggère. Tasker-Bridge a déjà résolu des
   morceaux (upload/install CXR-L, CDM, sélection host app).
4. **Inputs** — vocabulaire actuel = keycodes touchpad. R08-Access-Bridge pourrait
   devenir un *fournisseur d'input du système Nexus* entier (chaque plugin en profite)
   plutôt qu'une app standalone. Voix un jour (audio lease dispo).
5. **Cohabitation** — le hub possède l'unique session CXR-L : que se passe-t-il quand
   l'utilisateur ouvre Hi Rokid ou Live Studio standalone ? Réponse UX à définir.
6. **Versioning surfaces** — kind inconnu (vieux glasses hub, plugin récent) :
   règle de dégradation gracieuse à mettre au spec.

## 7. Questions ouvertes — À RÉPONDRE (en vrac, ça suffit)

### Geste et overlay
**Q1.** Le triple-tap : quels gestes du touchpad Rokid sont libres (non consommés par
l'OS / assistant vocal / media) ? Et l'overlay actuel (commit `58ec3b9`), il s'invoque
comment aujourd'hui ?
> Réponse : **Triple-tap, déjà implémenté et fonctionnel.** Le service a11y intercepte
> les key events bruts (`FLAG_REQUEST_FILTER_KEY_EVENTS`) : chaque contact touchpad
> émet KEYCODE 83 (NOTIFICATION) avant que l'OS classifie le geste (single tap → ENTER,
> double → BACK, swipes → paires DPAD). `TripleTapDetector` : 3 contacts en < 600 ms =
> TRIGGER, puis 800 ms de suppression des BACK/ENTER résiduels de la classification OS.
> Les gestes OS (single/double tap, swipes) restent pass-through — pas de conflit.
> Voir `TouchpadGestureDetectors.kt` + `RokidBusAccessibilityService.kt`.

**Q2.** Surface au-dessus d'une app native (ex. toast nav pendant NewPipe) : le back
rend la main à l'app native en dessous, c'est bien ça ?
> Réponse : **Oui, confirmé dans le code.** Back sur une surface → `hideLocal()` + la
> touche est consommée (`SurfaceController.handleKeyEvent`, ligne 104) : l'app native
> ne reçoit PAS ce back, le suivant lui revient. L'input est aussi rapporté au phone
> via `/surface/input` pour que le plugin ferme son état.

### Interruptions
**Q3.** Qui décide qu'un message est « user-actionable » ? Reco : le plugin déclare
une *classe* (`ambient` / `toast` / `actionable`) + override utilisateur par plugin
dans le phone hub + rate-limit hub-side. OK, ou plus simple pour commencer ?
> Réponse : **Décidé : les 3 classes au spec dès maintenant** (`ambient` / `toast` /
> `actionable` déclarées par le plugin) + override utilisateur par plugin dans le phone
> hub + rate-limit hub-side. Implémentation phasée : la v1 traite `actionable` comme
> `toast` tant que l'arbitrage d'affichage (§6.1) n'est pas livré. Les plugins tiers
> codent contre le bon vocabulaire dès le début, zéro breaking change plus tard.

**Q4.** Deux toasts en même temps : file d'attente, ou le dernier écrase ?
> Réponse : **Le dernier écrase** (comportement Android natif). Le rate-limit hub-side
> rend les collisions rares ; une file courte pourra venir plus tard sans breaking change.

### Confiance & vie privée (public = incontournable)
**Q5.** Consentement par plugin granulaire par capacité ? Reco : 3 capacités
approuvables séparément — surfaces / mic (audio lease) / proxy HTTP (le plugin fait
sortir du trafic par ta data).
> Réponse : **Décidé : granulaire, 3 capacités approuvables séparément** (surfaces /
> mic / proxy HTTP), modèle « notification listener » : n'importe quelle app demande,
> l'utilisateur approuve dans le phone hub. La « custom signature permission » du
> BUSSPEC est abandonnée — contradiction §6.2 levée.

**Q6.** Indicateur mic sur le HUD quand l'audio lease est actif (le « green dot »
Android). Quasi obligatoire pour du public. OK pour le spec ?
> Réponse : **Oui, au spec** — indicateur mic sur le HUD obligatoire tant que l'audio
> lease est actif.

### Onboarding sans ADB — probablement LE blocker release publique
**Q7.** L'ADB « arm once » sert à quoi exactement — activer l'a11y service ? Existe-t-il
un chemin sans ADB (écran Settings accessible sur les lunettes où l'utilisateur active
le service lui-même, comme le helper Tasker-Bridge) ? Si non → ça plafonne la release
publique, à savoir maintenant.
> Réponse : **Vérifié sur device (2026-07-07).** L'arm ADB fait 3 choses : install de
> l'APK, `settings put secure enabled_accessibility_services` (+ `accessibility_enabled 1`),
> grant `BLUETOOTH_CONNECT`. Bonne nouvelle : l'écran Accessibilité stock
> (`com.android.settings/.Settings$AccessibilitySettingsActivity`) **se lance par intent
> sur les lunettes, s'affiche et liste les services téléchargés** (testé, screenshot) →
> l'activation a11y a un chemin sans ADB, navigable au touchpad, comme le helper
> Tasker-Bridge. `BLUETOOTH_CONNECT` = dialog runtime standard. Le seul trou restant
> est l'**install de l'APK sans ADB** (précédent Tasker-Bridge : upload/install via
> CXR-L). Décision roadmap : le chantier bootstrap complet (install via Hi Rokid/CXR-L
> + écran a11y guidé + grant runtime) devient **le gate de la beta publique**, après
> les 2 premiers plugins. Les Rounds B/C restent ADB (power users).

### Distribution
**Q8.** Launcher payload actuel = `{id, displayName}`. Pour du public : icône, ordre,
favoris ? Et la boucle découverte : bouton « installer des plugins » dans le phone hub
→ RokidBrew, + tag « Nexus-compatible » sur RokidBrew ?
> Réponse : **v1 : texte + ordre/favoris** gérés dans le phone hub (le payload launcher
> gagne un champ d'ordre) ; les icônes (glyphes 1-bit) viendront plus tard — sur un HUD
> monochrome 640×480 le texte est roi, inutile de figer un format d'asset trop tôt.
> Boucle découverte : **oui** — bouton « installer des plugins » dans le phone hub →
> RokidBrew, + tag « Nexus-compatible » côté store.

**Q9.** `:bus-client` publié où pour les devs tiers — JitPack ? Maven Central ?
Et règle « kind de surface inconnu » : ignorer + toast « mise à jour requise » ?
> Réponse : **JitPack maintenant** (zéro setup, le repo GitHub suffit — les devs tiers
> ont le SDK immédiatement), migration Maven Central quand l'AIDL sera stable. Kind
> inconnu : **ignorer + toast « mise à jour requise »** côté lunettes — au spec.

### États d'erreur
**Q10.** Phone injoignable / SPP down : l'utilisateur lambda voit quoi sur les
lunettes ? Écran « reconnexion… » du hub, ou rien ?
> Réponse : **Silence tant que l'utilisateur ne demande rien** (le lien BT peut flapper,
> pas question de spammer le display) ; au triple-tap, l'overlay launcher affiche
> « Téléphone déconnecté — reconnexion… » à la place de la liste. Facile : l'état vide
> (« No phone plugins synced ») existe déjà comme point d'accroche. Aujourd'hui : rien
> du tout, silence total.

### Bonus (nouvelles, apparues en relisant)
**Q11.** Le système de widgets du launcher Rokid : API ouverte aux apps tierces ?
(Décide si le widget « Nexus glance » est possible.)
> Réponse : Pas encore investigué (l'exploration du launcher décompilé a été annulée).
> Sans impact sur la roadmap : le widget « Nexus glance » reste un bonus conditionnel.
> À creuser plus tard dans `E:\Tools\Rokid\Rokid-Launcher`.

**Q12.** Le menu contextuel doit-il aussi lister/lancer les apps natives lunettes
(Scouter, NewPipe…) pour être LE point d'entrée unique ? (Porter ≠ lancer.)
> Réponse : **Oui, en phase 2** (après les 2 premiers plugins) : section « Apps » sous
> les plugins dans le menu — query `PackageManager` + `startActivity` depuis le service
> a11y. Le menu devient LE point d'entrée unique, ce qui règle la douleur d'origine
> aussi pour les apps natives.

---

## 8. Prochaine étape

~~Une fois les réponses données : consolider en **VISION.md**.~~ **Fait (2026-07-07)** :
toutes les questions tranchées (sauf Q11, bonus reporté), consolidées dans
[VISION.md](VISION.md) — vision produit, modèle de couches, modèle de confiance,
roadmap réordonnée (onboarding sans ADB = gate de la beta publique, arbitrage
d'affichage = Round D).
