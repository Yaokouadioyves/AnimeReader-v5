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
2. *Run* sur un appareil **physique** Android 8.0+ (la capture d'écran et l'overlay ne sont pas
   pleinement testables sur émulateur sans contenu sous la bulle).

Au premier lancement, l'app demande dans l'ordre :
**superposition (dessiner par-dessus)** → **notifications** (Android 13+) → **capture d'écran**
(MediaProjection). Ce sont des autorisations système obligatoires pour ce type d'app.

### Mode en ligne (Gemini) — clé saisie dans l'app

Le mode en ligne utilise l'**API Gemini gratuite** de Google. La clé se renseigne **directement
dans les réglages de l'app** (champ « clé Gemini ») — elle est stockée **uniquement sur l'appareil**
(DataStore), **pas** dans le dépôt ni dans `local.properties`. Sans clé, l'app fonctionne
entièrement **hors-ligne** (voir plus bas).

> `local.properties` / `BuildConfig` portent encore des champs `OPENAI/DEEPL/GOOGLE/ELEVENLABS`
> hérités des providers « stub » (voir [Code historique non câblé](#code-historique-non-câblé)).
> Ils ne sont **pas** sur le chemin actif et peuvent rester vides.

---

## Le modèle d'overlay (comme demandé dans le design)

- **Démarrer** → seule la **bulle flottante** apparaît au-dessus du contenu.
- **Appui court** sur la bulle → affiche/masque le **rectangle de lecture** + le **dock** ; la lecture démarre.
- **Appui long** sur la bulle → **ouvre l'application**.
- **Arrêter** → depuis la notification, ou le bouton dans l'accueil / les réglages.
- Le rectangle **baisse d'opacité** après ~2,5 s sans interaction.

Boucle de lecture (quand le rectangle est ouvert et en lecture) : capture de la zone → OCR →
traduction → TTS (si activé) → ajout à l'historique + sous-titre transitoire. La boucle attend que
l'OCR se **stabilise** (même texte sur 2 captures) et déduplique les bulles déjà lues (similarité
de bigrammes + inclusion) avant de parler.

---

## Lecture des scans français / anglais

Inspirée de l'analyse de « Translate On Screen », puis améliorée pour les scans latins :

1. **OCR selon la langue des scans** (réglage « Langue des scans », français/anglais par défaut) :
   modèle latin direct, image seulement agrandie (pas de niveaux de gris/contraste forcé).
2. **Regroupement en bulles par lignes** : même taille de lettrage (≤ 1,7×), écart vertical
   inférieur à une ligne, chevauchement horizontal avec alignement centré/gauche/droite ; lignes
   coupées en deux par l'OCR recollées. Ordre de lecture par rangées (réglage « Sens de lecture »).
3. **Nettoyage** (`ScanText`) : mots coupés par un tiret recollés (« ATTEN- / DRE ») mais composés
   gardés (« PEUT- / ÊTRE »), MAJUSCULES → casse normale (meilleures traductions, voix plus
   naturelle), confusions « l/| → I », « 0 → O », filigranes et crédits de scan ignorés.
4. **Traduire seulement ce qui doit l'être** (`LangGuess` + langue détectée par Google) : une bulle
   déjà dans la langue cible est lue telle quelle, une bulle anglaise est traduite même si la cible
   est le français ; la voix suit la langue du texte réellement lu.
5. **Une requête par page** : Gemini/Mistral (vision, images numérotées, réponse remise en ordre
   par numéro), puis Google Traduction sans clé pour toutes les bulles restantes en un seul appel.

## Architecture

```
com.animereader
├── MainActivity            UI Compose + flux de permissions + requête MediaProjection
├── AnimeReaderApp          Application
├── data/                   Settings (DataStore), enums, catalogue (langues/providers), HistoryEntry
├── capture/ScreenCapturer  MediaProjection → ImageReader/VirtualDisplay → Bitmap recadré      [réel]
├── ocr/
│   ├── ScreenOcr           OCR ML Kit, modèle choisi selon la langue des scans (latin/japonais) [réel]
│   ├── BubbleClusters      regroupement des lignes en bulles + ordre de lecture (manga/BD)    [réel]
│   ├── ScanText            nettoyage du texte des scans (majuscules, tirets, filigranes)      [réel]
│   └── MangaImage          prétraitement (upscale ; niveaux de gris/contraste pour le japonais)[réel]
├── pipeline/BubbleReader   décisions OCR→traduction : cache, vision par lot, secours texte    [réel]
├── translate/
│   ├── GoogleWebTranslator Google Traduction sans clé, une requête par page + langue détectée [réel]
│   ├── LangGuess           langue d'une bulle (fr/en/…): traduire ou lire tel quel, voix      [réel]
│   └── Translators         MlKitTranslator (hors-ligne, réel) + OnlineTranslator (stub, inactif)
├── tts/
│   ├── Voices              VoiceProvider + DeviceTts (TTS appareil, hors-ligne, réel)         [réel]
│   └── EdgeVoice           voix neurale en ligne via Edge-TTS (WebSocket OkHttp, streaming)   [réel]
├── internal/
│   ├── GeminiClient        client Gemini : OCR+traduction (vision) et TTS streaming (SSE)     [réel]
│   ├── ApiKeys             clés des stubs, lues depuis BuildConfig (chemin inactif)
│   └── TaskAwait           pont coroutine ↔ Google Play Tasks
├── service/OverlayService  service foreground : bulle + rectangle + dock + boucle de lecture
└── ui/                     theme (3 directions × clair/sombre), onboarding, accueil, réglages
```

### Ce qui est **fonctionnel hors-ligne** (sans clé)
- **Capture d'écran** (MediaProjection) et recadrage sur la zone sélectionnée.
- **OCR** via ML Kit (japonais + latin, modèles téléchargés à la première utilisation), avec
  assemblage des lignes en ordre de lecture (vertical droite→gauche pour le japonais).
- **Traduction hors-ligne** via ML Kit Translate (modèles téléchargés à la demande).
- **TTS hors-ligne** via le moteur de l'appareil (`android.speech.tts`).
- UI Compose complète : onboarding, accueil, réglages, 3 directions × thème clair/sombre,
  accent et taille de texte, bascule lune/soleil, mode « traduire sans lire ».

### Ce qui est **fonctionnel en ligne** (avec une clé Gemini)
Contrairement aux versions précédentes, les providers en ligne ne sont **plus des stubs** :

- **OCR + traduction en un seul appel vision Gemini** (`GeminiClient.ocrTranslate`,
  modèle `gemini-2.5-flash-lite`) : l'image JPEG de la zone est envoyée, Gemini lit les bulles
  (japonais vertical, furigana, lettrage stylisé), corrige l'OCR et renvoie un JSON
  `{original, translation}`. Activé quand **méthode de traduction = En ligne** et clé Gemini présente.
- **Voix neurale en ligne** via **Edge-TTS** (`EdgeVoice`, Microsoft « Read Aloud », gratuit, sans
  clé) : MP3 en streaming sur WebSocket, lecture `MediaPlayer`, latence ~1 s. Activée quand
  **méthode de voix = En ligne**.
- **Robustesse free-tier** : cache LRU des résultats Gemini, **garde de débit** (~17 req/min) et
  **cooldown de 45 s après un HTTP 429**. En cas d'échec/quota/clé absente, le service **se rabat
  automatiquement** sur l'OCR+traduction ML Kit hors-ligne et le TTS de l'appareil. Les erreurs
  sont remontées à l'écran (Toast) pour le diagnostic.

> ⚠️ `EdgeVoice` s'appuie sur un service non documenté de Microsoft Edge — **usage personnel
> uniquement**. Pour un usage commercial, basculer sur un moteur embarqué (Piper) ou un provider payant.

### Code non câblé restant
- `translate/Translators.kt → OnlineTranslator` — **stub** (`NotImplementedError`) pour
  OpenAI / DeepL / Google, alimenté par `ApiKeys`/`BuildConfig`. Atteignable via
  `Translators.create(ONLINE)` mais le service ne passe **jamais** par cette branche aujourd'hui
  (le chemin en ligne actif est Gemini). `GeminiClient.ttsStream` (voix Gemini TTS en streaming)
  reste disponible mais n'est plus appelé depuis le retrait de `GeminiVoice`.

---

## Notes / simplifications de cette version
- L'overlay (bulle + rectangle + dock + sous-titre) est en **Vues Android classiques** (plus robuste
  pour un overlay système) ; le reste de l'UI est en **Compose**. L'overlay suit la direction/thème choisis.
- La langue d'une bulle est devinée sur l'appareil (`LangGuess` : mots fréquents, élisions,
  accents) ; en cas de doute, c'est la langue détectée par Google Traduction qui tranche.
- L'historique des lectures est collecté (`OverlayController.history`, 40 dernières) mais n'a pas
  encore d'écran Compose dédié.
- Les icônes de l'UI sont dessinées au `Canvas` ; le logo « drapeaux croisés » du prototype n'est
  pas embarqué.
- Aucune chaîne d'outils Android n'était disponible là où le code a été généré : **il n'a pas pu
  être compilé sur place**. Une première synchro Android Studio peut révéler de menus ajustements.
</content>
</invoke>
