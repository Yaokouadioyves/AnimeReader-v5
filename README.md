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

**Versions publiées** : la CI construit un APK *release* (téléphones arm64 seulement, ~30 Mo au lieu
de ~120 Mo) signé avec **une clé fixe** (secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) : chaque nouvelle version s'installe par-dessus la
précédente sans perdre les réglages. Numéro de version = numéro de build CI (5.N). Chaque fusion
dans `main` publie l'APK sur la page **Releases** du dépôt ; l'appli y renvoie (Réglages → Mises à
jour). L'original de la clé est conservé hors dépôt par le propriétaire.

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

Boucle de lecture (quand le rectangle est ouvert et en lecture), pensée pour la **lecture
verticale** (le contenu monte quand on lit vers le bas) : capture ~3 fois par seconde pendant le
défilement (intervalle réglable quand la page est immobile, OCR coupé après quelques captures
immobiles) → OCR → bulles → **suivi bulle par bulle** (`ScrollTracker`) → traduction → TTS dans
une coroutine à part, pour que la capture continue de suivre le défilement.

- Une bulle **entière** est lue dès que son texte est identique sur 2 captures, puis jamais relue.
- Une bulle **coupée en bas** du rectangle est attendue (elle remonte entière à la capture
  suivante) ; si la page s'arrête là, elle est lue seulement si son texte finit une phrase.
- Une bulle **coupée en haut** a déjà été lue plus bas : ignorée.
- Une bulle **plus haute que le rectangle** est reconstituée au fil des captures par les lignes
  communes, et lue quand sa fin apparaît.

« Coupée ou entière » (`BubbleEdges`) : texte à moins d'≈1,2 hauteur de ligne d'un bord = peut-être
coupée ; on remplit alors l'intérieur clair de la bulle depuis le texte (« pot de peinture », sur
une grille qui garde le pixel le plus sombre pour ne pas perdre un contour fin) : remplissage
enfermé = bulle entière, sortie par un bord = coupée de ce côté, fuite d'un côté à l'autre (texte
sans contour, fréquent en webtoon) = indécis, la marge du texte décide.

### Traduction écrite sur les bulles

Réglage « Affichage de la traduction » (ou bouton 文A du dock) : **Sur les bulles** (par défaut)
ou **Masquée** (la page reste en version originale, seule la voix lit). Il n'y a plus de
barre de sous-titres.

- Dès que la page s'arrête, chaque bulle entière (ou coupée en bas mais finissant une phrase) est
  traduite et **écrite par-dessus son texte**, sur un fond de la couleur de la bulle (couleur la
  plus fréquente autour du lettrage ; encre blanche sur bulle sombre). La taille du texte part de
  celle du lettrage d'origine, puis réduit, élargit (≤ 1,3×) et en dernier recours agrandit le
  cadre (`OverlayLayout`, testé JVM). La bulle lue à voix haute est entourée.
- **Un toucher n'importe où** efface les traductions (petite fenêtre invisible qui reçoit
  `ACTION_OUTSIDE`) ; à défaut, la capture surveille la page **autour** des traductions (cases
  masquées, plus une case de marge) et ne les efface que si au moins 12 % de cette zone change
  sur deux captures de suite : une barre de défilement qui s'estompe, une image qui finit de se
  charger ou une pub animée ne les font plus disparaître. Elles reviennent à l'arrêt
  suivant, instantanément pour les bulles déjà traduites (cache).
- Pendant qu'elles sont affichées, l'OCR est suspendu (la capture voit notre propre dessin) ; après
  effacement, 250 ms de pause et tout texte identique à une traduction affichée est ignoré.
- Le rectangle et les traductions sont **une seule fenêtre à 80 % d'opacité** : Android 12+ ne
  laisse passer les touchers à travers une fenêtre superposée que jusqu'à 0,8 — au-delà, un
  défilement commencé sur une bulle traduite serait bloqué. Le texte d'origine reste donc
  très légèrement visible sous la traduction.

---

## Lecture des scans français / anglais

Inspirée de l'analyse de « Translate On Screen », puis améliorée pour les scans latins :

1. **OCR latin** (le modèle japonais a été retiré : scans français/anglais uniquement) : image
   seulement agrandie (pas de niveaux de gris/contraste forcé).
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
│   ├── ScreenOcr           OCR ML Kit (modèle latin), lignes positionnées dans la capture      [réel]
│   ├── BubbleClusters      regroupement des lignes en bulles + ordre de lecture (manga/BD)    [réel]
│   ├── BubbleEdges         bulle entière ou coupée par un bord (marge + contour) — testé JVM   [réel]
│   ├── ScanText            nettoyage du texte des scans (majuscules, tirets, filigranes)      [réel]
│   └── MangaImage          prétraitement (agrandissement des petites lettres)                 [réel]
├── pipeline/
│   ├── BubbleReader        décisions OCR→traduction : cache, vision par lot, secours texte    [réel]
│   └── ScrollTracker       quelles bulles lire pendant le défilement vertical — testé JVM     [réel]
├── overlay/
│   ├── OverlayLayout       place/taille des traductions, couleur de bulle, masque — testé JVM [réel]
│   └── TranslationFrameView rectangle + traductions dessinées sur les bulles (une fenêtre)    [réel]
├── translate/
│   ├── GoogleWebTranslator Google Traduction sans clé, une requête par page + langue détectée [réel]
│   ├── LangGuess           langue d'une bulle (fr/en/…): traduire ou lire tel quel, voix      [réel]
│   └── Translators         MlKitTranslator (hors-ligne, réel) + OnlineTranslator (stub, inactif)
├── tts/
│   ├── Voices              VoiceProvider + DeviceTts (meilleure voix hors-ligne installée)     [réel]
│   ├── EdgeVoice           voix neurale Edge-TTS : synthèse anticipée, cache, repli appareil   [réel]
│   ├── EdgeProtocol        protocole Edge (adresses, jeton, trames audio, voix) — testé JVM    [réel]
│   └── SpeechText          texte parlé nettoyé (« Noooon », « ?!?! », ♪…) — testé JVM          [réel]
├── internal/
│   ├── GeminiClient        client Gemini : OCR+traduction (vision) et TTS streaming (SSE)     [réel]
│   ├── ApiKeys             clés des stubs, lues depuis BuildConfig (chemin inactif)
│   └── TaskAwait           pont coroutine ↔ Google Play Tasks
├── service/OverlayService  service foreground : bulle + rectangle + dock + boucle de lecture
└── ui/                     theme (3 directions × clair/sombre), onboarding, accueil, réglages
```

### Ce qui est **fonctionnel hors-ligne** (sans clé)
- **Capture d'écran** (MediaProjection) et recadrage sur la zone sélectionnée.
- **OCR** via ML Kit (modèle latin embarqué), avec regroupement des lignes en bulles et ordre de
  lecture.
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
  clé), réglage par défaut. Une seule WebSocket réutilisée ; chaque bulle mise en file est
  **synthétisée à l'avance** pendant que la précédente est lue (pas de blanc entre les bulles),
  lecture en mémoire via `MediaPlayer`. Timbre Féminine / Masculine = voix « Multilingual »
  récentes (Vivienne / Rémy en français), Neutre = voix classique (Denise).
  Depuis le 10/10/2026 Microsoft refuse l'ancienne adresse `speech.platform.bing.com` (403) :
  `EdgeProtocol.HOSTS` essaie d'abord `api.msedgeservices.com`, corrige un décalage d'horloge
  du téléphone à partir de l'en-tête `Date` et, en dernier recours, lit la bulle avec la voix de
  l'appareil.
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
- L'overlay (bulle + rectangle + dock) est en **Vues Android classiques** (plus robuste
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
