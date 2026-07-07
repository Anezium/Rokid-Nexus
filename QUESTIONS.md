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
> Réponse :

**Q2.** Surface au-dessus d'une app native (ex. toast nav pendant NewPipe) : le back
rend la main à l'app native en dessous, c'est bien ça ?
> Réponse :

### Interruptions
**Q3.** Qui décide qu'un message est « user-actionable » ? Reco : le plugin déclare
une *classe* (`ambient` / `toast` / `actionable`) + override utilisateur par plugin
dans le phone hub + rate-limit hub-side. OK, ou plus simple pour commencer ?
> Réponse :

**Q4.** Deux toasts en même temps : file d'attente, ou le dernier écrase ?
> Réponse :

### Confiance & vie privée (public = incontournable)
**Q5.** Consentement par plugin granulaire par capacité ? Reco : 3 capacités
approuvables séparément — surfaces / mic (audio lease) / proxy HTTP (le plugin fait
sortir du trafic par ta data).
> Réponse :

**Q6.** Indicateur mic sur le HUD quand l'audio lease est actif (le « green dot »
Android). Quasi obligatoire pour du public. OK pour le spec ?
> Réponse :

### Onboarding sans ADB — probablement LE blocker release publique
**Q7.** L'ADB « arm once » sert à quoi exactement — activer l'a11y service ? Existe-t-il
un chemin sans ADB (écran Settings accessible sur les lunettes où l'utilisateur active
le service lui-même, comme le helper Tasker-Bridge) ? Si non → ça plafonne la release
publique, à savoir maintenant.
> Réponse :

### Distribution
**Q8.** Launcher payload actuel = `{id, displayName}`. Pour du public : icône, ordre,
favoris ? Et la boucle découverte : bouton « installer des plugins » dans le phone hub
→ RokidBrew, + tag « Nexus-compatible » sur RokidBrew ?
> Réponse :

**Q9.** `:bus-client` publié où pour les devs tiers — JitPack ? Maven Central ?
Et règle « kind de surface inconnu » : ignorer + toast « mise à jour requise » ?
> Réponse :

### États d'erreur
**Q10.** Phone injoignable / SPP down : l'utilisateur lambda voit quoi sur les
lunettes ? Écran « reconnexion… » du hub, ou rien ?
> Réponse :

### Bonus (nouvelles, apparues en relisant)
**Q11.** Le système de widgets du launcher Rokid : API ouverte aux apps tierces ?
(Décide si le widget « Nexus glance » est possible.)
> Réponse :

**Q12.** Le menu contextuel doit-il aussi lister/lancer les apps natives lunettes
(Scouter, NewPipe…) pour être LE point d'entrée unique ? (Porter ≠ lancer.)
> Réponse :

---

## 8. Prochaine étape

Une fois les réponses données : consolider en **VISION.md** — vision produit, modèle
de couches (ambiant / toast / surface / apps natives), modèle de confiance, et roadmap
réordonnée en conséquence (l'onboarding sans ADB et l'arbitrage d'affichage remontent
probablement).
