# Installer canvasOS — instructions pour les agents IA

Vous installez canvasOS, un lanceur Android TV, sur le téléviseur de l'utilisateur depuis son ordinateur. Suivez les étapes ci-dessous dans l'ordre. Parlez à l'utilisateur dans sa langue (la plupart des utilisateurs écrivent en français). Le téléviseur est l'appareil d'un foyer : chaque fois qu'une étape dit de demander, demandez d'abord.

Ce dépôt (getisolated/canvasOS) est privé ; l'interface de l'app est en français. Dans l'app, l'écran d'accueil s'appelle « Accueil ».

## Règles absolues

- N'exécutez jamais `svc power shutdown`, `svc power reboot`, `reboot` ni `input keyevent KEYCODE_POWER` / `KEYCODE_SLEEP` sans demander. Un téléviseur éteint ne peut être rallumé qu'avec sa télécommande.
- Ne faites jamais `pm uninstall` sur une app système ni sur le lanceur du téléviseur.
- **Ne désactivez jamais le lanceur d'origine sur Xiaomi / Redmi** (`com.mitv.tvhome`, PatchWall). L'écran d'accueil de secours refuse lui aussi les lanceurs tiers, et le téléviseur démarre sur un écran noir. Sur Xiaomi, TCL et les firmwares similaires, utilisez plutôt la protection de l'accueil (section 10). Sur les autres téléviseurs, ne désactivez un lanceur qu'avec l'accord de l'utilisateur (étape 5), et expliquez-lui comment annuler.
- Avant d'envoyer `KEYCODE_DPAD_CENTER` / `ENTER` au téléviseur, vérifiez ce qui est sélectionné (étape 6). Beaucoup de boutons de canvasOS éteignent le téléviseur ou le mettent en veille.
- Ne modifiez aucun autre réglage du téléviseur (échelles d'animation, autorisations des apps, restrictions d'arrière-plan) sauf si l'utilisateur le demande.

## 0. Ce qu'il faut

- Un ordinateur sur le même réseau local que le téléviseur, avec `adb` installé (Android SDK Platform-Tools). Vérifiez avec `adb version`. S'il manque, installez-le : Windows `winget install Google.PlatformTools`, macOS `brew install android-platform-tools`, Debian/Ubuntu `apt install adb`.
- L'accès à ce dépôt privé : la CLI GitHub (`gh`, vérifiez avec `gh auth status`) connectée à un compte qui a accès à `getisolated/canvasOS`, ou un clone local du dépôt. Sans cela, les APK ne peuvent pas être téléchargés (étape 3).
- L'adresse IP du téléviseur. Demandez-la à l'utilisateur. Elle se trouve dans Paramètres → Réseau ou À propos sur la plupart des téléviseurs. Une fois canvasOS installé, l'adresse et l'état du débogage ADB s'affichent aussi dans Réglages de l'accueil → Système (l'adresse également sous « Réseau » dans le Centre de contrôle).
- Le débogage ADB activé sur le téléviseur. La plupart des utilisateurs ne l'ont jamais fait : si `adb connect` échoue à l'étape 1, guidez-les selon leur marque. Demandez la marque si vous ne la connaissez pas.

| Téléviseur | Comment activer ADB |
|---|---|
| **Xiaomi / Redmi** | Réglages (设置) → À propos (关于) → **Modèle du produit (产品型号)** : appuyez plusieurs fois sur OK jusqu'à ce que le mode développeur soit annoncé comme activé. Puis Réglages (设置) → Compte et sécurité (账号与安全) : activez **Débogage ADB (ADB 调试)** et **Installer des apps de sources inconnues (安装未知来源的应用)**. |
| **TCL / FFalcon (雷鸟)** | Réglages (设置) → À propos (关于) → **Informations sur l'appareil (本机信息)**, puis appuyez sur **Haut, Bas, Gauche, Droite** sur la télécommande, à moins d'une seconde d'intervalle. Un groupe **Plus (更多)** apparaît en bas ; activez **ADB** dedans. |
| **Sony BRAVIA** | Paramètres → Système → À propos → appuyez 7 fois sur OK sur **Build** (version d'Android TV OS). Puis Paramètres → Système → Options pour les développeurs → activez **Débogage USB**. |
| **Android TV / Google TV d'origine**, Hisense et autres | Paramètres → Préférences de l'appareil → À propos → appuyez 7 fois sur **Build**. Puis activez **Débogage USB** dans les Options pour les développeurs (parfois appelé Débogage réseau ou Débogage ADB). |

À la première connexion de l'ordinateur, le téléviseur demande « Autoriser le débogage USB ? » (sur les téléviseurs chinois : 允许 USB 调试吗？). L'utilisateur doit choisir **Autoriser** (允许) et cocher **Toujours autoriser** (始终允许).

## 1. Se connecter

```sh
adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 get-state     # doit afficher "device"
```

Si la commande affiche `unauthorized`, le téléviseur affiche « Autoriser le débogage USB ? ». Demandez à l'utilisateur de cocher « Toujours autoriser depuis cet ordinateur » et d'appuyer sur Autoriser, puis réessayez. Si la connexion est refusée, ADB est désactivé (étape 0). Xiaomi le désactive à chaque sortie de veille tant que l'option « garder ADB activé » de canvasOS n'est pas activée (étape 4). Utilisez `-s <TV_IP>:5555` dans toutes les commandes suivantes.

## 2. Examiner le téléviseur

```sh
adb -s <TV_IP>:5555 shell getprop ro.build.version.sdk          # doit être >= 28 (Android 9)
adb -s <TV_IP>:5555 shell getprop ro.product.manufacturer
adb -s <TV_IP>:5555 shell getprop ro.product.model
adb -s <TV_IP>:5555 shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <TV_IP>:5555 shell settings get secure screensaver_components
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services
```

Notez l'activité d'accueil actuelle (le lanceur d'origine), l'économiseur d'écran actuel et les services d'accessibilité : il vous les faudra pour annuler l'installation. Dites à l'utilisateur de quelles fonctions il disposera. La section 9 explique comment adapter l'app quand quelque chose ne marche pas.

## 3. Installer

Il existe deux versions :

- `canvasOS.apk` (standard) se déclare écran d'accueil. Utilisez-la partout sauf sur TCL.
- `canvasOS-guard.apk` ne déclare aucun écran d'accueil et apparaît comme une app ordinaire. TCL refuse tout APK qui en déclare un, sauf s'il est signé par TCL. La version guard est identique pour le reste ; la protection de l'accueil (section 10) la garde au premier plan.

Le dépôt étant privé, les URL de téléchargement directes ne fonctionnent pas. Téléchargez les APK avec la CLI GitHub, connectée à un compte qui a accès au dépôt :

```sh
gh release download --repo getisolated/canvasOS --pattern canvasOS.apk
adb -s <TV_IP>:5555 install -r canvasOS.apk
```

**Signature : une seule clé pour toutes les Releases.** Les APK sont compilés par GitHub Actions et signés avec la clé stable du projet : toutes les Releases publiées avec cette clé ont la même signature, donc `adb install -r` met canvasOS à jour sur place et l'app garde ses réglages et ses autorisations. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` n'apparaît plus que si la version installée est une ancienne Release signée avec la clé debug jetable du runner, ou un APK compilé localement (signé avec la clé de l'ordinateur qui l'a compilé). Il faut alors d'abord `adb -s <TV_IP>:5555 uninstall com.getisolated.canvasos`, ce qui efface les réglages et les autorisations de l'app : demandez l'accord de l'utilisateur avant, puis refaites l'étape 4, et l'utilisateur devra de nouveau autoriser le débogage local (étape 6). Avant de désinstaller, désactivez la protection comme indiqué dans la section « Désinstaller ». (Mainteneurs : la clé se crée une seule fois avec `tools/make-keystore.sh`.)

**Si l'installation échoue avec `INSTALL_FAILED_VERIFICATION_FAILURE`** (TCL : son vérificateur refuse toute installation par adb), installez plutôt la version guard via l'« Interaction multi-écrans » (多屏互动, T-Cast) de TCL. Il faut Python 3 et le script `tools/tcast-push.py` du dépôt (dans le clone local, ou récupéré avec `gh`) :

```sh
gh release download --repo getisolated/canvasOS --pattern canvasOS-guard.apk
gh api repos/getisolated/canvasOS/contents/tools/tcast-push.py -H "Accept: application/vnd.github.raw" > tcast-push.py   # inutile si vous avez un clone : utilisez tools/tcast-push.py
python3 tcast-push.py <TV_IP> canvasOS-guard.apk
```

Le script sert l'APK depuis cet ordinateur sur le port 8765. Le téléviseur affiche dans un coin « Interaction multi-écrans : envoi d'une application en cours » (多屏互动：正在发起应用推送). Dites à l'utilisateur de choisir **Accepter** (同意) sur le téléviseur. Si le pare-feu de l'ordinateur le demande, autorisez la connexion. Si le script n'atteint pas le port 6553 (pas d'Interaction multi-écrans sur ce modèle), rabattez-vous sur une installation depuis le téléviseur lui-même :

```sh
adb -s <TV_IP>:5555 push canvasOS-guard.apk /sdcard/Download/canvasOS.apk
adb -s <TV_IP>:5555 shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity   # si 当贝市场 (Dangbei Market) est installé
```

Demandez ensuite à l'utilisateur de choisir canvasOS dans le gestionnaire de paquets de Dangbei (ou avec n'importe quel gestionnaire de fichiers du téléviseur) et de l'installer. Vérifiez le résultat avec `adb shell pm path com.getisolated.canvasos`.

Une fois canvasOS installé et le débogage local autorisé (étape 6), les APK suivants peuvent aussi être envoyés depuis la page téléphone (section 8, onglet Apps), jamais la première installation ni canvasOS lui-même ; elle installe toutefois par l'adb local du téléviseur, que le vérificateur de TCL peut refuser de la même façon (`INSTALL_FAILED_VERIFICATION_FAILURE`) : dans ce cas, gardez la méthode ci-dessus.

Si GitHub est inaccessible, compilez depuis les sources : clonez le dépôt (https://github.com/getisolated/canvasOS), puis `./gradlew assembleStandardRelease assembleGuardRelease` (nécessite JDK 17 et un SDK Android ; renseignez `sdk.dir` dans `local.properties`). Les APK se trouvent dans `app/build/outputs/apk/standard/release/app-standard-release.apk` et `app/build/outputs/apk/guard/release/app-guard-release.apk`. Une version compilée localement n'a pas la clé des Releases (elle est signée avec la clé debug de cet ordinateur) : même règle de désinstallation que ci-dessus, et aussi dans l'autre sens, pour repasser plus tard à une Release.

**Mises à jour suivantes.** Une fois canvasOS installé et le débogage local autorisé (étape 6), l'utilisateur n'a plus besoin de l'ordinateur : Réglages de l'accueil → Système → **Mise à jour** cherche la dernière Release du dépôt public (`canvasOS.apk`, ou `canvasOS-guard.apk` pour la version guard), puis l'installe sur place, avec les réglages et les autorisations. Une première pression vérifie, la seconde télécharge et installe ; sans débogage local, la ligne demande de l'autoriser. canvasOS vérifie aussi une fois par jour qu'une version existe (rien n'est téléchargé sans cette seconde pression) et l'annonce sous le Centre de contrôle. Un `adb install -r` depuis un ordinateur marche toujours. Pour savoir si une version plus récente existe, depuis l'ordinateur :

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver --ez check_update true
# data="… update=<version|none|unavailable>" : la version à installer, none si canvasOS est à jour, unavailable si GitHub est injoignable ou n'a pas de Release publique
```

## 4. En faire l'écran d'accueil

Version standard :

```sh
adb -s <TV_IP>:5555 shell cmd package set-home-activity com.getisolated.canvasos/.HomeActivity
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
```

Accordez ensuite ce que seul adb peut accorder. C'est nécessaire sur tout téléviseur dont le système réserve la touche Accueil à son propre lanceur (Xiaomi, TCL, la plupart des firmwares chinois). Ailleurs, l'écouteur de notifications sert encore (grâce à lui, le nettoyage de la mémoire épargne ce qui est en cours de lecture), `READ_TV_LISTINGS` sert au Top Shelf sur tous les téléviseurs, et le reste est sans effet :

```sh
P=com.getisolated.canvasos
adb -s <TV_IP>:5555 shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
adb -s <TV_IP>:5555 shell pm grant $P android.permission.BLUETOOTH_CONNECT      # Android 12+ ; sur Android 11 et avant, erreur « permission inconnue » : ignorez-la
adb -s <TV_IP>:5555 shell cmd notification allow_listener $P/$P.RevivalListener
adb -s <TV_IP>:5555 shell appops set $P SYSTEM_ALERT_WINDOW allow
adb -s <TV_IP>:5555 shell appops set $P APP_AUTO_START allow      # TCL uniquement ; les autres firmwares affichent une erreur, ignorez-la
adb -s <TV_IP>:5555 shell appops set $P APP_ASSOC_START allow     # TCL uniquement
adb -s <TV_IP>:5555 shell pm grant $P android.permission.READ_TV_LISTINGS   # refusée sans conséquence là où le téléviseur n'a pas de TvProvider
```

| Autorisation | À quoi elle sert |
|---|---|
| `WRITE_SECURE_SETTINGS` | Réactive la protection de l'accueil quand le système la coupe ; garde le cadre photo comme économiseur ; garde ADB activé ; curseur de rétroéclairage de Xiaomi |
| `BLUETOOTH_CONNECT` | Bluetooth dans le Centre de contrôle sur Android 12+ : lister les appareils déjà associés et en connecter un. Utile sur tous les téléviseurs Android 12+, pas seulement les firmwares chinois ; sans elle, l'app la demande à l'écran à la première pression du bouton |
| Écouteur de notifications | Relance canvasOS environ une demi-seconde après que le système l'a tué, et au démarrage. Permet aussi au nettoyage de la mémoire d'épargner les apps qui lisent quelque chose (sessions média : seuls le nom du paquet et l'état de lecture sont lus ; avec « Lecture en cours » activée, aussi le titre, l'artiste et la pochette, section 8). Aucune notification n'est lue, sauf si l'utilisateur active les bannières (section 8) pour des apps qu'il choisit. |
| `SYSTEM_ALERT_WINDOW` | Ouvrir l'écran d'accueil depuis l'arrière-plan après le démarrage ; si l'utilisateur active les bannières de notifications, les afficher par-dessus l'app regardée |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` | Sinon, TCL empêche les apps tierces de se lancer au démarrage |
| `READ_TV_LISTINGS` | Top Shelf : lignes « Continuer à regarder » et recommandations publiées par Netflix, YouTube, Prime… pour l'écran d'accueil d'Android TV, lues sur le téléviseur et affichées au-dessus du Dock, rien d'autre ; sans elle, rien n'est lu |

Si l'étape 5 montre que le lanceur d'origine revient, activez la protection de l'accueil (section 10). Sur Xiaomi, demandez aussi s'il faut garder ADB activé : Xiaomi désactive ADB à chaque sortie de veille, ce qui rend impossibles les mises à jour ultérieures par adb tant que l'utilisateur ne le réactive pas dans les réglages.

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver --ez guard true --ez keep_adb true
# affiche data="guard=true keep_adb=true secure_settings=true"
```

L'utilisateur peut modifier ces deux options plus tard dans Réglages → Protection de l'accueil / Garder le débogage ADB activé. Ces lignes n'apparaissent que là où elles servent : Protection de l'accueil sur Xiaomi, TCL, ou partout où canvasOS n'est pas l'écran d'accueil par défaut ; Garder le débogage ADB activé sur Xiaomi uniquement.

## 4 bis. Assistant de premier lancement

Sur une nouvelle installation, le premier affichage de l'accueil (la touche Accueil de l'étape 4) ouvre un assistant en plein écran. Il est obligatoire : aucune commande adb ni clé CONFIG ne le saute. Ses pages : Bienvenue (« Commencer »), Horloge (24 h ou 12 h, et sa position), Fond d'écran, Dock (jusqu'à 5 apps, cochées dans l'ordre voulu ; sans choix, l'ordre par défaut est conservé), Météo (Oui / Non), Profil (le nom et la couleur du profil principal), Débogage local (étape 6) et Terminé (« Ouvrir l'accueil »). Un téléviseur mis à jour depuis une version précédente ne l'affiche pas.

L'utilisateur le parcourt avec la télécommande : Gauche et Droite passent d'un choix à l'autre dans une page, OK choisit, Bas mène à « Précédent » / « Continuer », Retour revient à la page précédente. Chaque choix est enregistré aussitôt ; tant que l'assistant n'est pas terminé, la touche Accueil le rouvre. Aidez l'utilisateur avec `screencap` et lisez la page avec `uiautomator dump` (étape 6) : dans l'assistant, le libellé de l'élément sélectionné est dans son attribut `content-desc`. Demandez-lui ce qu'il veut à chaque page ; vous pouvez appuyer sur les touches à sa place (`input keyevent KEYCODE_DPAD_RIGHT`, `KEYCODE_DPAD_DOWN`, `KEYCODE_DPAD_CENTER`, `KEYCODE_BACK`) après avoir vérifié l'élément sélectionné. Le nom se tape au clavier du téléviseur (OK sur le champ), ou depuis l'ordinateur avec `adb -s <TV_IP>:5555 shell input text "<nom>"` quand le champ est sélectionné (sans espaces ni accents : remplacez une espace par `%s`).

Tous ces choix se modifient ensuite dans Réglages de l'accueil, ou par la commande CONFIG (section 8). Pour savoir si l'assistant est terminé :

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver
# la réponse contient setup_done=true une fois l'assistant terminé, setup_done=false tant qu'il reste à faire
```

Faites les vérifications de l'étape 5 une fois l'assistant terminé : tant qu'il est affiché, `dumpsys activity activities | grep -m1 ResumedActivity` indique `com.getisolated.canvasos/.OnboardingActivity`.

## 5. Vérifier

Faites une capture d'écran et regardez-la :

```sh
adb -s <TV_IP>:5555 exec-out screencap -p > tv.png
```

Vous devez voir une horloge compacte, deux commandes rondes et une éventuelle photo de profil en haut à droite, et une rangée de tuiles d'apps sur un Dock en verre près du bas. La touche Retour ouvre le Centre de contrôle : des commandes en verre flottant séparément, ou un panneau sombre sur les téléviseurs peu puissants. Appuyez sur Accueil (`input keyevent KEYCODE_HOME`) et vérifiez à nouveau.

Si c'est le lanceur d'origine qui apparaît :

- Sur Xiaomi, TCL et les autres firmwares chinois, activez la protection de l'accueil (étape 4, section 10). **Ne désactivez pas le lanceur d'origine sur Xiaomi.**
- Sur les autres téléviseurs, refaites l'étape 4 et appuyez encore sur Accueil. S'il revient toujours, essayez d'abord la protection. Seulement si cela échoue, demandez à l'utilisateur s'il faut désactiver le lanceur du fabricant, et seulement alors :

```sh
adb -s <TV_IP>:5555 shell pm disable-user --user 0 <paquet du lanceur d'origine>
# annuler : adb -s <TV_IP>:5555 shell pm enable <paquet du lanceur d'origine>
```

Au premier lancement, l'app télécharge les photos de fond (connexion Internet nécessaire). Pendant la première minute, un dégradé peut s'afficher.

## 6. Autorisation du débogage local (extinction, veille, nettoyage complet de la mémoire)

Sur une nouvelle installation, c'est la page « Débogage local » de l'assistant de premier lancement (étape 4 bis) qui le propose ; sur un téléviseur mis à jour depuis une version précédente, l'accueil le propose lui-même au démarrage. Si l'utilisateur a choisi « Plus tard », la demande se refait dans Réglages de l'accueil → Système → Débogage local. Quand l'utilisateur appuie sur « Autoriser maintenant » (ou « Commencer »), le téléviseur affiche de nouveau une demande d'autorisation, « Autoriser le débogage ? » (sur les téléviseurs chinois : 允许调试吗？), cette fois du téléviseur vers lui-même. L'utilisateur doit cocher « Toujours autoriser » et appuyer sur « Autoriser ». Vérifiez l'écran avec `screencap` et guidez-le. Vous pouvez vérifier ensuite : le Centre de contrôle (touche Retour sur l'écran d'accueil) n'affiche plus la ligne « Débogage local non autorisé ». Une fois cette autorisation donnée, canvasOS peut aussi s'accorder lui-même les autorisations de l'étape 4 (Réglages → Protection de l'accueil s'en charge).

Si vous devez appuyer sur des boutons à la place de l'utilisateur, trouvez d'abord l'élément sélectionné :

```sh
adb -s <TV_IP>:5555 shell uiautomator dump /sdcard/u.xml && adb -s <TV_IP>:5555 shell cat /sdcard/u.xml
```

Cherchez `focused="true"` et ses attributs `text` / `bounds` avant d'envoyer `KEYCODE_DPAD_CENTER`.

## 7. Sony BRAVIA uniquement : luminosité, volume et HDMI côté téléviseur

Sautez cette étape si le fabricant n'est pas Sony. L'API de contrôle IP du téléviseur doit accepter l'enregistrement du client `tvhome:1` :

```sh
BODY='{"method":"actRegister","id":1,"version":"1.0","params":[{"clientid":"tvhome:1","nickname":"TVHome","level":"private"},[{"value":"yes","function":"WOL"}]]}'
curl -s http://<TV_IP>/sony/accessControl -d "$BODY"
```

Le téléviseur affiche alors un code PIN à 4 chiffres pendant environ une minute. Demandez à l'utilisateur de le lire à voix haute, ou lisez-le sur une `screencap`. Puis :

```sh
curl -s -u ":<PIN>" http://<TV_IP>/sony/accessControl -d "$BODY"   # {"result":[]} signifie succès
```

Si le premier appel renvoie une erreur ou rien du tout, demandez à l'utilisateur d'activer Paramètres → Réseau et Internet → Paramètres des appareils distants → « Contrôler à distance » (le nom du menu varie selon le modèle), puis réessayez. Pour vérifier : ouvrez le Centre de contrôle. Le curseur de luminosité s'affiche et la ligne Source indique « Actuelle : … ».

## 8. Réglages facultatifs

Signalez à l'utilisateur qu'ils existent ; ne les modifiez que s'il le demande. Ils se trouvent tous dans Centre de contrôle → Réglages (engrenage), ou sur la page « Réglages de l'accueil » :

- Fond d'écran (aussi la tuile **Fond d'écran** de l'accueil) : chaque choix est présenté en image et se sélectionne d'un seul appui — Photos du jour (Bing, en 3840×2160 sur un téléviseur 4K), Unsplash (sélections éditoriales Fonds d'écran et Nature, 4K ; demande une clé d'accès gratuite, https://unsplash.com/developers, collée dans l'onglet Réglages de la page téléphone ou passée avec `--es unsplash_key`), Paysages, Animaux mignons, Anime, Chefs-d'œuvre, Mes photos (importées), Lien personnalisé, ou un dégradé ; plus la fréquence de changement (Changer de photo ; « Jamais » laisse la photo jusqu'au bouton Photo suivante). La même page affiche le QR code de la page téléphone (ci-dessous), où l'utilisateur peut importer ses propres photos, coller des liens d'images (une image, une API d'images aléatoires, ou une page / un JSON qui liste des images ; récupérés chaque jour), et choisir le fond d'écran et l'économiseur.
- Page téléphone : accessible à tout moment depuis un téléphone sur le même Wi-Fi, en scannant le QR code de Réglages de l'accueil → Page téléphone → Afficher le QR code (ou celui de la tuile **Fond d'écran**). Elle choisit le fond d'écran, l'économiseur, l'animation de démarrage et la ville de la météo, importe des photos, renomme les apps de l'accueil et change leurs vignettes, sert de télécommande avec clavier, et installe des APK. La télécommande, le clavier et l'installation d'APK passent par le débogage local : il faut l'autorisation de l'étape 6. Le code dans l'adresse est la seule protection de la page ; Régénérer le code (même section) rend les anciennes adresses inutilisables, par exemple si un téléphone est perdu, et la ligne Page téléphone la désactive entièrement.
- Le bouton rond « Photo suivante » du Centre de contrôle passe tout de suite à la photo de fond d'écran suivante (le bouton Redémarrer a disparu ; `svc power reboot` reste possible par adb, avec l'accord de l'utilisateur).
- Le cadre photo comme économiseur système (Cadre photo en économiseur), le délai d'inactivité (Délai avant le cadre photo) et son contenu (Contenu de l'économiseur) : photos ou Vues aériennes (Apple TV) (lues en streaming depuis Apple, environ 15–20 Mbit/s ; retour aux photos sans connexion). Sur Xiaomi, qui affiche son propre économiseur au lieu de celui d'Android, l'écran d'accueil lance le cadre photo 15 s avant celui de Xiaomi.
- Entrées HDMI sur l'écran d'accueil : Centre de contrôle → Source → Afficher sur l'accueil….
- Dossiers, comme sur tvOS : sur l'accueil, l'appui long sur une app propose « Nouveau dossier… » (le nom se tape au clavier du téléviseur ; laissé vide, le dossier s'appelle « Dossier »), qui met l'app dans un nouveau dossier à sa place, et « Ajouter au dossier… » dès qu'un dossier existe. OK ouvre le dossier par-dessus l'accueil : OK y lance une app, Retour ou Menu le referme, et l'appui long sur une de ses apps propose « Retirer du dossier » (elle revient à la fin de l'accueil), « Renommer » et « Infos sur l'app ». L'appui long sur un dossier propose « Ouvrir », « Modifier l'accueil » (un dossier se déplace comme une app, Dock compris), « Renommer le dossier », « Ajouter des apps… » (cocher ou décocher les apps de l'accueil) et « Supprimer le dossier » (ses apps reviennent sur l'accueil à sa place). Les entrées HDMI épinglées vont aussi dans un dossier. Une app masquée reste dans son dossier sans s'y afficher ; une app désinstallée en disparaît. Réglages de l'accueil → Dossiers les liste. Il n'y a ni commande adb ni clé CONFIG : les dossiers sont enregistrés avec les réglages de l'app et effacés par une désinstallation (étape 3).
- Profils (Réglages de l'accueil → Profils) : chaque profil a son propre accueil, c'est-à-dire l'ordre des apps, les apps masquées, les dossiers, les noms des apps, les entrées HDMI épinglées et le fond d'écran (avec Changer de photo) ; tous les autres réglages valent pour le téléviseur entier, quel que soit le profil. Le profil principal est l'accueil d'avant les profils (rien n'est migré) et ne peut pas être supprimé ; un nouveau profil commence avec l'ordre des apps par défaut, sans apps masquées ni dossiers, et avec le fond d'écran du profil principal. La photo de profil en haut à droite de l'accueil montre, comme sur tvOS, l'initiale du nom du profil en blanc sur sa couleur (« P » pour le profil principal tant qu'il n'a pas de nom), ou une vraie photo : l'utilisateur l'importe depuis la page téléphone (onglet Réglages, section Profils, « Changer la photo » / « Supprimer la photo »), ou vous la poussez avec `adb push <photo>.jpg /sdcard/Android/data/com.getisolated.canvasos/files/avatars/<id>.jpg` (recadrée en cercle à l'affichage ; une photo importée depuis le téléphone l'emporte sur celle poussée par adb, et la supprimer depuis le téléphone ou les réglages efface les deux). L'identifiant `<id>` du profil s'affiche sur la page téléphone, et dans la réponse de la diffusion `CONFIG` pour le profil affiché (`profile="Enfants" profile_id=abc123` ; le profil principal a l'identifiant `main`). La même image apparaît dans la liste des profils et dans la page Profils des réglages. OK ouvre la liste des profils pour en changer, et « Gérer les profils… » ouvre la page Profils des réglages (renommer, couleur parmi six, « Photo… », « Supprimer la photo », « Profil enfant », supprimer, « Nouveau profil… », « Code PIN »). Elle reste affichée tant qu'il existe plusieurs profils, même si « Photo de profil » est désactivée. Un **profil enfant** demande le code PIN (4 à 6 chiffres) pour passer à un autre profil, ouvrir les réglages de l'accueil (par la photo de profil, l'engrenage du Centre de contrôle ou la tuile des réglages) et supprimer une app ; trois codes faux referment la demande. « Masquer », « Infos sur l'app » et « Image d'origine » n'y sont pas proposés ; Éteindre, Veille et Libérer la mémoire restent disponibles. Depuis la photo de profil, on n'entre dans un profil enfant qu'une fois un code PIN choisi. Le code lui-même n'est pas enregistré, seulement son empreinte SHA-256 salée. Il ne protège pas les réglages du téléviseur lui-même : proposez à l'utilisateur de masquer leur tuile dans le profil enfant (Réglages de l'accueil → Apps masquées, ouvert depuis ce profil). L'onglet Apps de la page téléphone agit sur le profil affiché à ce moment. Code oublié : supprimez-le par adb (`profile_pin`, ci-dessous), qui ne demande jamais de code. Comme les autres réglages, les profils sont effacés par une désinstallation (étape 3).
- Bluetooth, bouton du Centre de contrôle (sous Météo ; absent si le téléviseur n'a pas de Bluetooth) : il indique l'appareil connecté (« Casque WH-1000XM4 · connecté · 80 % », la batterie quand l'appareil la communique) ou le nombre d'appareils connectés, et ouvre la liste des appareils déjà associés. Une pression sur un casque ou une enceinte le connecte ou le déconnecte (le téléviseur demande cette connexion à Android ; selon le firmware, Android peut la refuser aux apps : l'app envoie alors vers les réglages Bluetooth du téléviseur). Les manettes, claviers et télécommandes s'affichent sans action : une manette se reconnecte d'elle-même quand on l'allume. « Associer un appareil… » ouvre les réglages Bluetooth du téléviseur : l'association (et son code) reste là-bas, canvasOS ne cherche aucun appareil. Sur Android 12+, la première pression affiche la demande « Appareils à proximité » ; vous pouvez l'éviter avec `pm grant` de l'étape 4.
- Vignettes et noms des apps : l'onglet Apps de la page téléphone liste les apps de l'accueil dans leur ordre, puis celles qui sont masquées. Pour chacune, l'utilisateur modifie le nom (vide, l'app reprend le sien), choisit une image (« Changer l'image », jusqu'à 20 Mo, recadrée au centre en 16:9 et enregistrée en 800×450), revient à l'« Image d'origine », et la masque ou l'affiche. Sur le téléviseur, l'appui long sur une tuile propose « Renommer » (au clavier du téléviseur) et, si l'image a été changée, « Image d'origine ». Par adb, poussez une image JPEG 16:9 nommée d'après le paquet de l'app : `adb -s <TV_IP>:5555 push vignette.jpg /sdcard/Android/data/com.getisolated.canvasos/files/tiles/<paquet>.jpg` (par exemple `tiles/com.netflix.ninja.jpg` ; `pm list packages` donne les noms). Aucun `am broadcast` n'est nécessaire : l'accueil la prend au prochain retour à l'accueil (touche Accueil). Une image importée depuis le téléphone passe avant celle poussée par adb et la remplace ; pour revenir à l'image d'origine, supprimez le fichier (`adb shell rm …/tiles/<paquet>.jpg`) ou utilisez « Image d'origine ». Les écrans de canvasOS et les entrées HDMI se renomment mais gardent leur image (l'icône des réglages s'importe dans l'onglet Réglages de la page téléphone). Comme les autres réglages, noms et vignettes sont effacés par une désinstallation (étape 3).
- Minuteur de veille, depuis le bouton Veille du Centre de contrôle : Maintenant, ou dans 15 min, 30 min, 1 h, 1 h 30, avec un avertissement une minute avant si l'écran d'accueil est affiché. Il demande le débogage local (étape 6), ne survit pas à un redémarrage, et la touche de veille n'est envoyée que parce que l'utilisateur a choisi ce délai.
- Top Shelf : quand une app du Dock est sélectionnée, ses contenus remplissent l'écran au-dessus du Dock, une image toutes les 8 s (OK ouvre l'app). Ils viennent de la première source qui a quelque chose : la ligne « Continuer à regarder » (Watch Next) que l'app publie pour Android TV, puis sa chaîne de recommandations (par exemple « Recommandé » de YouTube) — ces deux-là demandent l'autorisation `READ_TV_LISTINGS` de l'étape 4 —, puis, pour Netflix, Prime Video, Disney+, Apple TV, Max, Paramount+, Canal+ et Crunchyroll, les titres populaires de la semaine sur ce service en France d'après TMDB (The Movie Database), enfin les sources web de Bilibili et Douyu. TMDB demande une clé d'API gratuite à l'utilisateur (https://www.themoviedb.org/settings/api) : il la colle dans l'onglet Réglages de la page téléphone (section « Top Shelf (TMDB) »), ou vous la passez avec `--es tmdb_key` (ci-dessous). Elle reste sur le téléviseur ; ne la recopiez nulle part ailleurs. Pour voir ce que fait le Top Shelf : `adb -s <TV_IP>:5555 logcat -d -s Shelf`. Réglages de l'accueil → Top Shelf permet de se limiter aux lignes Android TV (sans TMDB ni web) ou de le désactiver.
- Apps conservées lors du nettoyage de la mémoire (Apps à conserver). Les apps en cours d'utilisation sont épargnées automatiquement, sans figurer dans cette liste : celles qui lisent du son ou de la vidéo (reconnues grâce à l'écouteur de notifications de l'étape 4), celles qui font tourner un service au premier plan, comme la diffusion d'écran, un serveur Plex ou Jellyfin, un VPN (reconnues grâce au débogage local de l'étape 6), ainsi que les services d'accessibilité et le clavier actifs. Réglages de l'accueil → Dernier nettoyage liste les apps fermées la dernière fois ; `adb logcat -d -s canvasOSClean` montre aussi celles qui ont été épargnées.
- Météo (activée par défaut) : la température et une icône à gauche de la petite horloge, ou une ligne « 14° · Nuageux » sous la date de la grande horloge, et un bouton Météo dans le Centre de contrôle qui ouvre les prévisions sur cinq jours. Les prévisions viennent d'Open-Meteo (sans clé), mises à jour au plus toutes les 30 minutes pendant que l'accueil est affiché, et disparaissent hors connexion. Tant qu'aucune ville n'est choisie, le téléviseur devine le lieu une fois d'après son adresse IP publique (ip-api.com) ; Réglages de l'accueil → Lieu de la météo l'affiche alors suivi de « (détecté) ». La ville se choisit depuis la page téléphone (onglet Réglages, section Météo) ou par adb (`weather_city`, ci-dessous). Signalez-la à l'utilisateur : elle est active d'emblée et contacte ces deux services.
- Lecture en cours (Réglages de l'accueil → Lecture et notifications), désactivée par défaut : le Centre de contrôle affiche sous « Source » ce que lit une app (titre, artiste, pochette) ; OK met en lecture ou en pause, Gauche et Droite passent à la piste précédente ou suivante. Elle lit les métadonnées des sessions média grâce à l'écouteur de notifications de l'étape 4 ; aucune notification n'est lue pour cela.
- Bannières de notifications (même section), désactivées par défaut : les notifications des apps cochées dans « Apps des bannières » (sonnette, domotique…) s'affichent 6 secondes en haut à droite, sur l'accueil et, si `SYSTEM_ALERT_WINDOW` est accordée (étape 4), par-dessus l'app regardée. Seules ces apps sont lues (titre et texte) et rien n'est enregistré. Ne les activez que si l'utilisateur le demande, avec les apps qu'il nomme (`adb shell pm list packages` pour trouver leur paquet). Les deux options demandent l'écouteur de notifications de l'étape 4 ; si le débogage local est autorisé (étape 6), les activer sur le téléviseur l'approuve aussi.
- Horloge (Format de l'heure, qui suit le format du téléviseur tant que l'utilisateur n'a rien choisi ; Horloge), Dock (Récents ou Fixe : si l'app ouverte passe en tête de l'accueil), animation de sélection (Effet de sélection), style du Centre de contrôle (Style du Centre de contrôle). Les deux derniers sont sur Auto par défaut, ce qui choisit l'aspect le plus léger sur les téléviseurs peu puissants.
- Animation de démarrage, désactivée par défaut. L'app ne contient aucune vidéo : l'utilisateur en importe une depuis la page ouverte par le QR code, ou vous la poussez avec `adb push <vidéo> /sdcard/Android/data/com.getisolated.canvasos/files/intro.mp4`. Ne téléchargez pas pour l'utilisateur un clip protégé par une marque. Il en va de même pour une icône de réglages personnalisée (`files/settings_icon.png`) et un fond pour la page des réglages (`files/settings_bg.jpg`, une image 16:9 avec son sujet à gauche), qui peuvent aussi être importés depuis la page du téléphone.

Chacun de ces réglages peut aussi être modifié par adb, par exemple :

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver   --ez clock_24 true --es clock_style corner --es focus_style auto --es cc_style auto --es intro boot --ez intro_sound true
# clock_style corner|large|off · focus_style / cc_style auto|tvos|simple / auto|glass|panel · intro off|boot|wake
# intro_source video|wordmark (la vidéo importée, ou le logo canvasOS)
# --ei wake_delay <ms> (intro=wake : écran noir avant l'animation à la sortie de veille, 5000 par défaut ; les dalles OLED s'allument tard)
# own_icon / settings_bg true|false (l'icône de réglages / le fond de page importés, ou l'aspect intégré ; les fichiers sont conservés dans les deux cas)
# background daily|scenery|fresh|anime|art|mine|custom|green|blue|purple|orange|graphite (source du fond d'écran de l'accueil, ou un dégradé intégré)
# phone_server true|false (la page téléphone, activée par défaut ; désactivée, son adresse ne répond plus du tout)
# dock_recents true|false (true par défaut : l'app ouverte passe en tête de l'accueil ; false : l'ordre choisi avec « Modifier l'accueil » est conservé)
# weather true|false (la météo sur l'accueil et dans le Centre de contrôle, activée par défaut ; désactivée, rien n'est téléchargé)
# --es weather_city "Lyon" (lieu de la météo : le téléviseur cherche la ville sur Open-Meteo et garde le premier résultat, ce qui prend quelques secondes ;
#   introuvable ou Open-Meteo injoignable, le lieu ne change pas et `adb logcat -d -s canvasOSWeather` dit pourquoi ; la réponse indique weather_place="Lyon")
# now_playing true|false (Lecture en cours) · banners true|false (bannières de notifications) : false par défaut, seulement si l'utilisateur le demande
# --es banner_apps "pkg1,pkg2" (les seules apps dont les notifications deviennent des bannières ; "" vide la liste)
# la réponse indique aussi listener=true|false (écouteur de notifications approuvé, nécessaire aux deux) et overlay=true|false (bannières par-dessus les apps)
# --es unsplash_key <clé> (clé d'accès Unsplash pour la source de fond d'écran « Unsplash » ; --es unsplash_key "''" l'efface ; le résultat n'affiche que unsplash_key=set|unset)
# --es shelf all|tv|off (Top Shelf : complet, lignes Android TV seules sans TMDB ni web, ou désactivé ; Réglages → Top Shelf)
# --es tmdb_key <clé> (clé d'API TMDB v3 ou jeton d'accès en lecture v4, pour le Top Shelf ; --es tmdb_key "''" l'efface ; le résultat n'affiche que tmdb_key=set|unset)
# --es profile_add "Enfants" (crée ce profil ; avec --ez profile_restricted true, un profil enfant, protégé seulement une fois profile_pin donné ;
#   s'il existe déjà, rien n'est créé et profile_restricted s'applique à lui)
# --es profile "Enfants" (affiche ce profil, sans code PIN ; "Principal" désigne le profil principal tant qu'il n'a pas de nom)
# --es profile_pin 1234 (le code PIN des profils enfants, 4 à 6 chiffres ; --es profile_pin "''" le supprime)
# la réponse indique profile="Enfants" profile_id=abc123 profiles=2 pin=set|unset, plus profile_unknown="…" si le nom n'existe pas et profile_pin=invalid si le code n'a pas 4 à 6 chiffres
# la réponse indique aussi setup_done=true|false (l'assistant de premier lancement est terminé ou non, étape 4 bis ; en lecture seule, aucune clé ne le saute)
```

## 9. Adapter canvasOS à un téléviseur mal pris en charge

Ne le faites que si l'utilisateur le demande, ou s'il est d'accord après que vous lui avez signalé ce qui ne marche pas. Lacunes typiques sur les téléviseurs qui ne sont pas des Sony :

- Le curseur de luminosité est absent. Android n'a pas d'API standard pour le rétroéclairage.
- Le curseur de volume bouge mais le son ne change pas, par exemple quand l'audio passe par un amplificateur en ARC.
- La ligne Source indique « Sources illisibles », ou choisir une entrée HDMI ne fait rien.

Ces trois fonctions se trouvent dans le paquet [`tv/`](app/src/main/java/com/getisolated/canvasos/tv/), avec un adaptateur par marque :

```
tv/
├── TvControls.kt      # choisit le premier adaptateur dont matches() renvoie true
├── AndroidAdapter.kt  # l'interface TvAdapter + Android standard, dont hérite chaque marque
├── SonyAdapter.kt     # API de contrôle IP BRAVIA sur 127.0.0.1
├── XiaomiAdapter.kt
├── TclAdapter.kt
└── CoocaaAdapter.kt   # Skyworth / Coocaa : sources uniquement, non testé sur un vrai téléviseur
```

Une nouvelle marque, c'est un nouveau `<Marque>Adapter.kt` qui hérite d'`AndroidAdapter`, redéfinit `matches()` (en général d'après `Build.MANUFACTURER` / `Build.BRAND`) et uniquement ce que le firmware fait différemment, et qui est ajouté à `TvControls.brands`. Ne modifiez pas les adaptateurs des autres marques ni le comportement d'`AndroidAdapter`. Ce que font les adaptateurs existants :

- Sony (`SonyAdapter`) : rétroéclairage, volume et sources via l'API BRAVIA ; chaque fonction se rabat sur Android standard quand l'API ne répond pas.
- TCL / FFalcon, firmware chinois (`TclAdapter`) : les sources ouvrent le sélecteur de source de LiveTV, les réglages ouvrent `com.tcl.settings`, et la luminosité reste masquée.
- Xiaomi, firmware chinois (`XiaomiAdapter`) : rétroéclairage via `Settings.Global picture_backlight` (nécessite l'autorisation `WRITE_SECURE_SETTINGS`), sources via le lecteur de Xiaomi (`EXTSRC_PLAY`), réglages et Wi-Fi via `com.xiaomi.mitv.settings`.
- Skyworth / Coocaa (`CoocaaAdapter`) : les sources ouvrent le sélecteur de source du lanceur (`SourceDialogActivity`) quand il n'existe pas de sources standard ; pas encore testé sur un vrai téléviseur, et la luminosité et les réglages restent ceux d'Android standard.

### 9.1 Préparer une compilation

- Clonez https://github.com/getisolated/canvasOS (dépôt privé : `gh repo clone getisolated/canvasOS` avec un compte qui y a accès). Il faut JDK 17+ (`java -version`) et un SDK Android avec `platforms;android-36` et `build-tools` (installez-les avec `sdkmanager`). Mettez `sdk.dir=<chemin>` dans `local.properties`. Compilez avec `./gradlew assembleStandardRelease` (ou `assembleGuardRelease` pour TCL).
- Votre version est signée avec la clé debug de cet ordinateur, pas avec la clé stable des Releases : elle ne peut pas se mettre à jour par-dessus l'APK d'une Release installé. `adb install -r` échouera avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Demandez à l'utilisateur avant de faire d'abord `adb uninstall com.getisolated.canvasos` : cela efface les réglages et les autorisations de l'app, et ensuite vous devez refaire l'étape 4 et l'utilisateur doit de nouveau autoriser le débogage local (étape 6). Pour repasser plus tard à une Release, la même désinstallation sera nécessaire.

### 9.2 Trouver ce que propose le téléviseur

Travaillez depuis adb. Avant d'écrire quoi que ce soit, notez chaque valeur lue ; remettez les valeurs d'origine quand vous avez terminé.

- **Volume** : `adb shell cmd media_session volume --stream 3 --get` montre ce que règle le curseur de l'app. Modifiez-le avec `--set <n>` et demandez à l'utilisateur si le son a changé. Si ce n'est pas le cas, cherchez dans `dumpsys audio` la sortie audio active et si le contrôle du volume par HDMI-CEC est utilisé (`dumpsys hdmi_control`).
- **Entrées HDMI** : `adb shell dumpsys tv_input` liste les entrées avec leurs identifiants et leurs états ; les entrées HDMI ont en général `HDMI` ou `HW` dans leur identifiant. Testez un changement de la même manière que l'app :
  `adb shell am start -a android.intent.action.VIEW -d "content://android.media.tv/passthrough/<identifiant d'entrée encodé pour URL>"`
  Si rien ne se passe, lancez `adb logcat -c`, demandez à l'utilisateur de changer de source avec la télécommande, puis lisez `adb logcat -d | grep -iE "START u0|tune|input"` pour trouver l'activité du lecteur du fabricant et l'intent qu'elle utilise.
- **Luminosité / rétroéclairage** : prenez un instantané avec `adb shell settings list system; settings list global; settings list secure`. Demandez à l'utilisateur de modifier le rétroéclairage dans les réglages d'image du téléviseur, reprenez l'instantané et comparez les deux. Une clé qui a changé (avec un nom comme `backlight`, `picture_backlight`) est une candidate. Testez-la avec `settings put`, demandez à l'utilisateur si l'écran a changé, puis remettez la valeur d'origine. Regardez aussi logcat pendant le changement, et `adb shell ss -ltn` pour repérer des services de contrôle du fabricant sur localhost, comme le port 80 de Sony.

### 9.3 Implémenter et vérifier

- Écrivez l'adaptateur de la marque dans `tv/` et ajoutez-le à `TvControls.brands`. Si `brightness()` renvoie un `Level(value, min, max)` non nul, le curseur apparaît ; `setBrightness` est alors appelé avec des valeurs dans cette plage. Toutes ces fonctions sauf les deux intents s'exécutent sur un thread d'arrière-plan : les E/S bloquantes ne posent pas de problème. Les tuiles de sources épinglées appellent `setInput` avec des uri enregistrées auparavant : gardez stable le format d'uri d'une marque.
- Écrire des réglages : les réglages secure et global peuvent être écrits directement une fois `WRITE_SECURE_SETTINGS` accordée (étape 4 ; vérifiez avec `Guard.hasSecureSettings`). Pour la table system, accordez `WRITE_SETTINGS` avec `adb shell appops set com.getisolated.canvasos WRITE_SETTINGS allow`, ou exécutez la commande via l'adb local de l'app, `LocalAdb.shell(ctx, "settings put …")` dans `Cleaner.kt`, qui fonctionne une fois que l'utilisateur a autorisé le débogage local.
- Compilez, installez, ouvrez le Centre de contrôle (touche Retour sur l'écran d'accueil) et faites une `screencap`. Allez jusqu'au curseur avec les touches directionnelles, en vérifiant d'abord l'élément sélectionné (étape 6). Relisez ensuite la valeur de la même manière qu'en 9.2 pour confirmer le changement.

### 9.4 Terminer

Dites à l'utilisateur ce qui fonctionne désormais et ce qui ne fonctionne toujours pas. Sa version adaptée reste sur son téléviseur. S'il veut la partager, proposez-lui de committer la modification dans ce dépôt, en indiquant le modèle du téléviseur et la version d'Android.

## 10. Protection de l'accueil (téléviseurs qui rejettent les lanceurs tiers)

Beaucoup de systèmes de téléviseurs chinois (Xiaomi PatchWall, TCL, Hisense VIDAA sur Android, Skyworth et d'autres) ne sont pas de l'Android TV d'origine. Ce que l'on sait (d'après les tests de canvasOS et les notes de modèles d'[Aerial](https://github.com/pengchujin/aerial-tv)) :

- **Xiaomi** accepte `set-home-activity` et le rôle HOME, les deux annoncent un succès, et pourtant la touche Accueil ouvre toujours PatchWall (`com.mitv.tvhome`) : seule une liste blanche système décide. En veille, Xiaomi force l'arrêt de toutes les apps tierces, ce qui désactive aussi leurs services d'accessibilité. Il désactive aussi ADB à chaque sortie de veille.
- **TCL** refuse les installations par adb et les APK d'écran d'accueil (étape 3), bloque le lancement automatique des apps tierces au démarrage, et peut réécrire son propre économiseur d'écran au démarrage.

canvasOS gère cela sans root, et sans rien désactiver ni désinstaller :

1. **Protection de l'accueil**, un service d'accessibilité (« Protection de l'accueil »). Quand l'écran d'accueil du lanceur d'origine passe au premier plan, canvasOS revient par-dessus (l'utilisateur voit le lanceur d'origine pendant environ une demi-seconde). Quand l'utilisateur ouvre volontairement le lanceur d'origine depuis sa tuile, la touche Accueil ramène à canvasOS. Le service ne surveille que les changements de fenêtre, jamais le contenu de l'écran.
2. **Relance** : l'écouteur de notifications de l'étape 4. Android le relie à nouveau environ une demi-seconde après que le système a tué canvasOS : canvasOS redémarre alors et réactive la protection (avec `WRITE_SECURE_SETTINGS`). Il lance aussi canvasOS au démarrage.
3. **Garder ADB activé** (facultatif) : quand Xiaomi désactive le débogage ADB, canvasOS le réactive aussitôt.
4. Avec la protection activée, canvasOS remet aussi son économiseur d'écran en place quand le firmware l'écrase.

Activez-la avec les autorisations de l'étape 4 et la diffusion `CONFIG`, ou sur le téléviseur dans Réglages → Protection de l'accueil (qui s'accorde les autorisations via l'adb local une fois l'étape 6 faite). Puis vérifiez :

```sh
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services   # contient com.getisolated.canvasos/com.getisolated.canvasos.HomeGuardService
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME; sleep 2
adb -s <TV_IP>:5555 shell dumpsys activity activities | grep -m1 ResumedActivity  # com.getisolated.canvasos/.HomeActivity
adb -s <TV_IP>:5555 logcat -d -s canvasOSGuard                                     # ce qu'a fait la protection, et pourquoi
```

Demandez ensuite à l'utilisateur de mettre le téléviseur en veille puis de le réveiller, et vérifiez de nouveau que canvasOS est au premier plan et que la protection est toujours listée. Si l'utilisateur accepte un redémarrage, vérifiez aussi après le démarrage. La protection attend 1,5 s après le démarrage ou la sortie de veille avant de prendre la main, pour ne pas gêner une console de jeu ou un boîtier qui bascule le téléviseur sur son entrée HDMI via CEC.

Si la protection est listée mais que `dumpsys accessibility` n'indique aucun service lié pour elle, canvasOS la réenregistre de lui-même quelques secondes après son ouverture ; si elle reste non liée, désactivez-la puis réactivez-la dans Réglages → Protection de l'accueil.

Si rien ne marche, dites-le clairement à l'utilisateur : le système de ce téléviseur n'accepte pas de lanceur de remplacement, mais canvasOS peut toujours être ouvert comme une app.

## 11. Facultatif : supprimer des apps préinstallées

Uniquement sur demande. Le menu d'appui long de canvasOS peut supprimer les apps préinstallées qui apparaissent sur l'écran d'accueil (Supprimer l'app), et les restaurer depuis Réglages de l'accueil → Apps préinstallées supprimées. Pour celles qui ne tournent qu'en arrière-plan (services de publicité, de push ou de suivi sans icône), listez-les avec `pm list packages -s`, identifiez chacune par son libellé et son chemin, et proposez une liste à l'utilisateur. Expliquez chaque entrée et demandez avant de supprimer quoi que ce soit.

- Supprimez avec `adb shell pm uninstall -k --user 0 <paquet>`. Restaurez avec `adb shell cmd package install-existing <paquet>`. Donnez les deux commandes à l'utilisateur.
- Ne supprimez jamais les réglages, les lanceurs, la méthode de saisie, le lecteur / tuner TV / `tvinput`, l'interface système, la mise à jour, les services Google Play, ni rien que vous ne savez pas identifier. Sur les téléviseurs chinois, conservez aussi les services de licence (« 播控 » comme `com.newtv.*`, CIBN, ce qui touche à ktcp). Sans eux, les apps vidéo sous licence ne lisent plus rien.
- Après la suppression, ne redémarrez que si l'utilisateur est d'accord, puis vérifiez que le téléviseur démarre toujours, que les entrées HDMI fonctionnent et que les apps vidéo lisent.

## Désinstaller

Désactivez d'abord la protection, pour que la liste d'accessibilité revienne à sa valeur d'origine (celle notée à l'étape 2), et remettez l'économiseur d'origine si le cadre photo était devenu l'économiseur :

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver --ez guard false --ez keep_adb false
adb -s <TV_IP>:5555 shell settings put secure screensaver_components <valeur d'origine>
adb -s <TV_IP>:5555 uninstall com.getisolated.canvasos
```

Le téléviseur revient à son lanceur d'origine. Si vous avez désactivé un lanceur à l'étape 5, réactivez-le avec `pm enable <paquet>`.
