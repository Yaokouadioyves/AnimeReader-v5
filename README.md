# AnimeReader (Android)

Application Android qui capture l'écran pour **lire à voix haute les bulles de manga**, par-dessus
n'importe quelle app de lecture — une bulle flottante « chat-head », un rectangle de sélection
déplaçable, OCR à la volée, traduction et synthèse vocale.

Ce projet est l'implémentation native du prototype **AnimeReader v5** (design Claude). Kotlin +
Jetpack Compose, `minSdk 26`, `compileSdk 34`.

---

## Construire & lancer

> ⚠️ Ce dépôt ne contient pas le binaire `gradle/wrapper/gradle-wrapper.jar` (fichier binaire).
> **Ouvrez le dossier dans Android Studio** (Giraffe+ / AGP 8.5) : la synchronisation Gradle
> génère le wrapper et télécharge les dépendances. Sinon, avec un Gradle 8.9 installé :
> `gradle wrapper` puis `./gradlew assembleDebug`.

1. Android Studio → *Open* → ce dossier.
2. (Optionnel) `cp local.properties.example local.properties` et renseignez vos clés API.
3. *Run* sur un appareil **physique** Android 8.0+ (la capture d'écran et l'overlay ne sont pas
   pleinement testables sur émulateur sans contenu sous la bulle).

Au premier lancement, l'app demande dans l'ordre :
**superposition (dessiner par-dessus)** → **notifications** (Android 13+) → **capture d'écran**
(MediaProjection). Ce sont des autorisations système obligatoires pour ce type d'app.

---

## Le modèle d'overlay (comme demandé dans le design)

- **Démarrer** → seule la **bulle flottante** apparaît au-dessus du contenu.
- **Appui court** sur la bulle → affiche/masque le **rectangle de lecture** + le **dock** ; la lecture démarre.
- **Appui long** sur la bulle → **ouvre l'application**.
- **Arrêter** → depuis la notification, ou le bouton dans l'accueil / les réglages.
- Le rectangle **baisse d'opacité** après ~2,5 s sans interaction.

Boucle de lecture (quand le rectangle est ouvert et en lecture) : capture de la zone → OCR ML Kit →
traduction (si activée) → TTS (si activé) → ajout à l'historique + légende transitoire.

---

## Architecture

```
com.animereader
├── MainActivity            UI Compose + flux de permissions + requête MediaProjection
├── AnimeReaderApp          Application
├── data/                   Settings (DataStore), enums, catalogue (langues/providers), HistoryEntry
├── ocr/ScreenOcr           OCR ML Kit (japonais → latin) sur un Bitmap          [réel]
├── translate/Translators   interface + MlKitTranslator (hors-ligne, réel) + OnlineTranslator (stub)
├── tts/Voices              interface + DeviceTts (hors-ligne, réel) + OnlineVoice (stub→repli)
├── capture/ScreenCapturer  MediaProjection → ImageReader/VirtualDisplay → Bitmap recadré  [réel]
├── service/OverlayService  service foreground : bulle + rectangle + dock + boucle de lecture
└── ui/                     theme (3 directions × clair/sombre), onboarding, accueil, réglages
```

### Ce qui est **fonctionnel** (hors-ligne, sans clé)
- Capture d'écran (MediaProjection) et recadrage sur la zone sélectionnée.
- **OCR** via ML Kit (modèles téléchargés à la première utilisation).
- **Traduction hors-ligne** via ML Kit Translate (modèles téléchargés à la demande).
- **TTS hors-ligne** via le moteur de l'appareil (`android.speech.tts`).
- UI Compose complète : onboarding 3 étapes, accueil, réglages, 3 directions × thème clair/sombre,
  accent et taille de texte, bascule lune/soleil, mode « traduire sans lire ».

### Ce qui est **branché derrière des interfaces** (à compléter)
Les providers IA en ligne sont des **stubs** — l'architecture est prête, il reste à écrire les
appels réseau :
- Traduction : `translate/Translators.kt` → `OnlineTranslator` (GPT-4o / DeepL / Google).
- Voix : `tts/Voices.kt` → `OnlineVoice` (ElevenLabs / Google Cloud TTS / OpenAI). En attendant,
  la voix en ligne **se rabat automatiquement** sur le TTS de l'appareil.

Les clés se lisent via `BuildConfig` (depuis `local.properties`) — **aucun secret n'est en dur**.
Si une traduction en ligne échoue (clé absente / stub), le service **se rabat sur ML Kit hors-ligne**.

---

## Notes / simplifications de cette première version
- L'overlay (bulle + rectangle + dock) est en **Vues Android classiques** (plus robuste pour un
  overlay système) ; le reste de l'UI est en **Compose**. L'overlay suit la direction/thème choisis.
- L'OCR détecte la langue source par heuristique (CJK → japonais, sinon français) ; on peut ajouter
  `com.google.mlkit:language-id` pour une détection robuste.
- L'historique des lectures est collecté (`service.OverlayController.history`) mais n'a pas encore
  d'écran Compose dédié (le design en prévoit un — ajout simple).
- Les icônes de l'UI sont dessinées au `Canvas` ; le logo « drapeaux croisés » du prototype n'est
  pas embarqué (placez un PNG dans `res/` et référencez-le si besoin).
- Aucune chaîne d'outils Android n'était disponible là où le code a été généré : **il n'a pas pu
  être compilé sur place**. Une première synchro Android Studio peut révéler de menus ajustements.
