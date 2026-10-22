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
- L'adresse IP du téléviseur. Demandez-la à l'utilisateur. Elle se trouve dans Paramètres → Réseau ou À propos sur la plupart des téléviseurs.
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

Si GitHub est inaccessible, compilez depuis les sources : clonez le dépôt (https://github.com/getisolated/canvasOS), puis `./gradlew assembleStandardRelease assembleGuardRelease` (nécessite JDK 17 et un SDK Android ; renseignez `sdk.dir` dans `local.properties`). Les APK se trouvent dans `app/build/outputs/apk/standard/release/app-standard-release.apk` et `app/build/outputs/apk/guard/release/app-guard-release.apk`. Une version compilée localement n'a pas la clé des Releases (elle est signée avec la clé debug de cet ordinateur) : même règle de désinstallation que ci-dessus, et aussi dans l'autre sens, pour repasser plus tard à une Release.

## 4. En faire l'écran d'accueil

Version standard :

```sh
adb -s <TV_IP>:5555 shell cmd package set-home-activity com.getisolated.canvasos/.HomeActivity
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
```

Accordez ensuite ce que seul adb peut accorder. C'est nécessaire sur tout téléviseur dont le système réserve la touche Accueil à son propre lanceur (Xiaomi, TCL, la plupart des firmwares chinois), et sans effet ailleurs :

```sh
P=com.getisolated.canvasos
adb -s <TV_IP>:5555 shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
adb -s <TV_IP>:5555 shell cmd notification allow_listener $P/$P.RevivalListener
adb -s <TV_IP>:5555 shell appops set $P SYSTEM_ALERT_WINDOW allow
adb -s <TV_IP>:5555 shell appops set $P APP_AUTO_START allow      # TCL uniquement ; les autres firmwares affichent une erreur, ignorez-la
adb -s <TV_IP>:5555 shell appops set $P APP_ASSOC_START allow     # TCL uniquement
```

| Autorisation | À quoi elle sert |
|---|---|
| `WRITE_SECURE_SETTINGS` | Réactive la protection de l'accueil quand le système la coupe ; garde le cadre photo comme économiseur ; garde ADB activé ; curseur de rétroéclairage de Xiaomi |
| Écouteur de notifications | Relance canvasOS environ une demi-seconde après que le système l'a tué, et au démarrage. Aucune notification n'est lue |
| `SYSTEM_ALERT_WINDOW` | Ouvrir l'écran d'accueil depuis l'arrière-plan après le démarrage |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` | Sinon, TCL empêche les apps tierces de se lancer au démarrage |

Si l'étape 5 montre que le lanceur d'origine revient, activez la protection de l'accueil (section 10). Sur Xiaomi, demandez aussi s'il faut garder ADB activé : Xiaomi désactive ADB à chaque sortie de veille, ce qui rend impossibles les mises à jour ultérieures par adb tant que l'utilisateur ne le réactive pas dans les réglages.

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver --ez guard true --ez keep_adb true
# affiche data="guard=true keep_adb=true secure_settings=true"
```

L'utilisateur peut modifier ces deux options plus tard dans Réglages → Protection de l'accueil / Garder le débogage ADB activé. Ces lignes n'apparaissent que là où elles servent : Protection de l'accueil sur Xiaomi, TCL, ou partout où canvasOS n'est pas l'écran d'accueil par défaut ; Garder le débogage ADB activé sur Xiaomi uniquement.

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

Au premier lancement, canvasOS propose d'activer ces fonctions. Quand l'utilisateur appuie sur « Commencer », le téléviseur affiche de nouveau une demande d'autorisation, « Autoriser le débogage ? » (sur les téléviseurs chinois : 允许调试吗？), cette fois du téléviseur vers lui-même. L'utilisateur doit cocher « Toujours autoriser » et appuyer sur « Autoriser ». Vérifiez l'écran avec `screencap` et guidez-le. Vous pouvez vérifier ensuite : le Centre de contrôle (touche Retour sur l'écran d'accueil) n'affiche plus la ligne « Débogage local non autorisé ». Une fois cette autorisation donnée, canvasOS peut aussi s'accorder lui-même les autorisations de l'étape 4 (Réglages → Protection de l'accueil s'en charge).

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

- Fond d'écran (aussi la tuile **Fond d'écran** de l'accueil) : chaque choix est présenté en image et se sélectionne d'un seul appui — Photos du jour (Bing), Paysages, Animaux mignons, Anime, Chefs-d'œuvre, Mes photos (importées), Lien personnalisé, ou un dégradé ; plus la fréquence de changement (Changer de photo). La même page affiche un QR code : un téléphone sur le même Wi-Fi ouvre une page pour importer ses propres photos, coller des liens d'images (une image, une API d'images aléatoires, ou une page / un JSON qui liste des images ; récupérés chaque jour), et choisir le fond d'écran et l'économiseur.
- Le cadre photo comme économiseur système (Cadre photo en économiseur), le délai d'inactivité (Délai avant le cadre photo) et son contenu (Contenu de l'économiseur) : photos ou Vues aériennes (Apple TV) (lues en streaming depuis Apple, environ 15–20 Mbit/s ; retour aux photos sans connexion). Sur Xiaomi, qui affiche son propre économiseur au lieu de celui d'Android, l'écran d'accueil lance le cadre photo 15 s avant celui de Xiaomi.
- Entrées HDMI sur l'écran d'accueil : Centre de contrôle → Source → Afficher sur l'accueil….
- Apps conservées lors du nettoyage de la mémoire (Apps à conserver).
- Horloge (Format de l'heure, Horloge), animation de sélection (Effet de sélection), style du Centre de contrôle (Style du Centre de contrôle). Les deux derniers sont sur Auto par défaut, ce qui choisit l'aspect le plus léger sur les téléviseurs peu puissants.
- Animation de démarrage, désactivée par défaut. L'app ne contient aucune vidéo : l'utilisateur en importe une depuis la page ouverte par le QR code, ou vous la poussez avec `adb push <vidéo> /sdcard/Android/data/com.getisolated.canvasos/files/intro.mp4`. Ne téléchargez pas pour l'utilisateur un clip protégé par une marque. Il en va de même pour une icône de réglages personnalisée (`files/settings_icon.png`) et un fond pour la page des réglages (`files/settings_bg.jpg`, une image 16:9 avec son sujet à gauche), qui peuvent aussi être importés depuis la page du téléphone.

Chacun de ces réglages peut aussi être modifié par adb, par exemple :

```sh
adb -s <TV_IP>:5555 shell am broadcast -a com.getisolated.canvasos.CONFIG -n com.getisolated.canvasos/.ConfigReceiver   --ez clock_24 true --es clock_style corner --es focus_style auto --es cc_style auto --es intro boot --ez intro_sound true
# clock_style corner|large|off · focus_style / cc_style auto|tvos|simple / auto|glass|panel · intro off|boot|wake
# intro_source video|wordmark (la vidéo importée, ou le logo canvasOS)
# --ei wake_delay <ms> (intro=wake : écran noir avant l'animation à la sortie de veille, 5000 par défaut ; les dalles OLED s'allument tard)
# own_icon / settings_bg true|false (l'icône de réglages / le fond de page importés, ou l'aspect intégré ; les fichiers sont conservés dans les deux cas)
# background daily|scenery|fresh|anime|art|mine|custom|green|blue|purple|orange|graphite (source du fond d'écran de l'accueil, ou un dégradé intégré)
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
