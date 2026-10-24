/* Copie française (langue par défaut, servie à /). Chaque affirmation suit AGENTS.md. */
import type { Copy } from "./lang";
import { brand, device, deviceOs, release, restoreCmd } from "./site";

const P = brand.pkg;
const TV = "192.168.x.x";

export const fr: Copy = {
  htmlLang: "fr",
  path: "/",
  meta: {
    title: "canvasOS — l'accueil de votre TV, à la manière de tvOS | Lanceur Android TV",
    description:
      "canvasOS est un lanceur Android TV open source calqué sur tvOS : Dock en verre, Top Shelf, Centre de contrôle personnalisable, cadre photo et vues aériennes de l'Apple TV en économiseur. Android 9+, sans root, réversible.",
    ogTitle: "canvasOS — l'accueil de votre TV, à la manière de tvOS",
    ogDescription:
      "Dock en verre, Top Shelf, Centre de contrôle et vues aériennes de l'Apple TV en économiseur. Open source, Android 9+, sans root.",
    ogLocale: "fr_FR",
    ogLocaleAlt: "en_US",
  },
  ui: {
    skip: "Aller au contenu",
    navLabel: "Navigation principale",
    menuOpen: "Ouvrir le menu",
    menuClose: "Fermer le menu",
    homeLabel: "Accueil canvasOS",
    copy: "Copier",
    copied: "Copié",
    copyFailed: "Échec de la copie",
    compareValue: "{p} % de l'image « avant » affichés",
    compareName: "Comparer l'accueil d'origine avec une capture de canvasOS",
    prev: "Précédente",
    next: "Suivante",
    close: "Fermer",
    viewer: "Visionneuse de captures",
    pauseClips: "Mettre en pause les enregistrements",
    playClips: "Lire les enregistrements",
    still: "Capture réelle",
    recording: "Enregistrement réel",
  },
  nav: [
    { href: "#features", label: "Fonctions" },
    { href: "#aerial", label: "Économiseur" },
    { href: "#compare", label: "Avant / après" },
    { href: "#install", label: "Installation" },
    { href: "#compatibility", label: "Compatibilité" },
    { href: "#faq", label: "FAQ" },
  ],
  langSwitch: { href: "/en/", label: "EN", lang: "en", name: "English" },
  headerCta: "Télécharger",
  menuCta: "Télécharger l'APK canvasOS",
  menuNote: "Android TV 9+ · sans root · projet indépendant, sans lien avec Apple",

  hero: {
    eyebrow: "CANVASOS · LANCEUR ANDROID TV",
    titleLines: ["L'accueil de votre TV,", "à la manière de tvOS."],
    sub: "canvasOS remplace l'écran d'accueil de votre Android TV, Google TV ou box Android : un Dock en verre posé sur une grande photo, un Top Shelf qui reprend ce que vous regardiez, et les vues aériennes de l'Apple TV quand le salon se tait.",
    primary: "Télécharger",
    secondary: "Voir l'installation",
    chips: ["Android TV 9+", "Sans root · réversible", "Certaines fonctions varient selon la marque"],
    chipAerial: { title: "Vues aériennes", sub: "Les films de l'Apple TV" },
    chipDock: { title: "Dock en verre", sub: "Tuiles 16:9, verre liquide" },
    alt: "L'accueil de canvasOS sur une box Google TV : une ville au crépuscule et un Dock en verre avec YouTube, Spotify, Netflix, Prime Video et Disney+.",
    note: "Projet open source indépendant, sans lien avec Apple. « Apple TV » et « tvOS » sont des marques d'Apple Inc.",
  },

  proof: [
    `Testé sur ${device}`,
    "Vues aériennes de l'Apple TV en économiseur",
    "APK standard et APK guard",
    "Sans root, entièrement réversible",
  ],

  release: {
    label: `Nouveautés de canvasOS ${release.version}`,
    pill: `Version ${release.version} · toutes les versions ↗`,
    cards: [
      {
        title: "Un lecteur sur l'accueil.",
        body: "Quand une app lit quelque chose, une capsule de verre en haut à gauche montre la pochette, le titre et l'artiste. OK ouvre l'app qui lit.",
      },
      {
        title: "Les paroles, en rythme.",
        body: "Quand LRCLIB a les paroles synchronisées du titre, un bouton Paroles sort de la capsule ; OK les affiche en grand, la ligne chantée en blanc.",
      },
      {
        title: "Vos touches, vos raccourcis.",
        body: "Réglages de l'accueil → Télécommande : une touche, en appui simple ou long, ouvre le Centre de contrôle, les paroles, une app ou une entrée HDMI.",
      },
    ],
  },

  aerial: {
    eyebrow: "ÉCONOMISEUR D'ÉCRAN",
    title: "Les vues aériennes de l'Apple TV, en économiseur.",
    lede: "Quand la TV reste inactive, le cadre photo prend le relais : vos photos de fond d'écran, ou les films aériens de l'Apple TV qui survolent côtes, montagnes et villes. Sans connexion, il revient aux photos.",
    altStill: "Capture réelle du cadre photo en économiseur : un pont illuminé au coucher du soleil, l'heure, la date et le crédit de la photo en bas à gauche.",
    altVideo: "Enregistrement de l'économiseur d'écran de canvasOS avec les vues aériennes de l'Apple TV.",
    badgeStill: "Capture réelle · contenu Photos",
    badgeVideo: "Enregistré sur la TV · vues aériennes",
    captionStill: `Cadre photo en économiseur, contenu Photos · capture sur ${device}`,
    captionVideo: `Vues aériennes en économiseur · enregistrement sur ${device}`,
    points: [
      "Contenu de l'économiseur : Photos, ou Vues aériennes (Apple TV), lues en streaming depuis les serveurs d'Apple (environ 15–20 Mbit/s)",
      "Sans connexion, retour automatique aux photos",
      "Le cadre photo devient l'économiseur système, après le délai d'inactivité que vous choisissez ; sur Xiaomi, l'accueil le lance avant celui de Xiaomi",
    ],
    attribution:
      "Les films aériens sont lus en streaming depuis les serveurs d'Apple ; canvasOS n'en contient aucun. « Apple TV » est une marque d'Apple Inc. Ce projet n'est pas affilié à Apple.",
  },

  features: {
    eyebrow: "FONCTIONS",
    title: "Pensé pour le canapé, pas pour un téléphone.",
    lede: "Un grand focus, peu de niveaux, du verre discret : une interface dessinée pour trois mètres de distance, pas agrandie depuis un téléphone.",
    items: [
      {
        kicker: "DOCK EN VERRE",
        title: "Un Dock en verre posé sur la photo",
        body: "Les apps reposent sur un Dock en verre près du bas de l'écran ; la rangée suivante dépasse à peine en dessous, et le reste de l'écran appartient à la photo.",
        bullets: [
          "Tuiles 16:9 aux coins arrondis : la tuile sélectionnée grossit, s'incline et reflète la lumière",
          "Dossiers comme sur tvOS, entrées HDMI épinglées, apps renommées, masquées ou illustrées d'une autre image",
          "Dock Récents ou Fixe : l'app ouverte passe en tête, ou l'ordre reste le vôtre",
        ],
        alt: "Capture réelle de l'accueil de canvasOS : une ville au crépuscule et un Dock en verre avec YouTube, Spotify, Netflix, Prime Video et Disney+.",
        altVideo: "Enregistrement de l'accueil de canvasOS : le Dock en verre et ses tuiles.",
        caption: "Accueil",
      },
      {
        kicker: "TOP SHELF",
        title: "Sélectionnez une app, l'écran se remplit",
        body: "Quand une app du Dock est sélectionnée, ses contenus occupent l'écran au-dessus du Dock, une image toutes les 8 secondes. OK ouvre l'app.",
        bullets: [
          "La ligne « Continuer à regarder » que YouTube, Netflix, Prime Video… publient pour Android TV, lue sur la TV",
          "Pour Netflix, Prime Video, Disney+, Apple TV, Max, Paramount+, Canal+ et Crunchyroll : les titres populaires de la semaine en France d'après TMDB, avec votre clé gratuite",
          "Limité à la ligne Android TV ou désactivé en un réglage",
        ],
        alt: "Capture réelle du Top Shelf : Prime Video sélectionnée, « Continuer à regarder » affiche The Grand Tour au-dessus du Dock.",
        altVideo: "Enregistrement du Top Shelf : les contenus de l'app sélectionnée au-dessus du Dock.",
        caption: "Top Shelf",
      },
      {
        kicker: "CENTRE DE CONTRÔLE",
        title: "Une pression sur Retour",
        body: "Des commandes en verre flottent en haut à droite, sur une grille de quatre colonnes que vous réorganisez sur la TV, comme sur tvOS.",
        bullets: [
          "Éteindre, Veille avec minuteur, Réseau avec l'adresse IP, Libérer la mémoire, Photo suivante, Cadre photo, Réglages",
          "Lecteur avec pochette et progression, Météo sur cinq jours, Bluetooth ; Luminosité et Source quand la TV les expose",
          "Appui long sur son bouton : déplacer, redimensionner, ajouter des commandes, sur trois pages au plus",
        ],
        alt: "Capture réelle du Centre de contrôle : lecteur avec pochette, boutons ronds, météo sur trois jours et Bluetooth, au-dessus du Dock.",
        altVideo: "Enregistrement du Centre de contrôle de canvasOS.",
        caption: "Centre de contrôle",
      },
      {
        kicker: "FOND D'ÉCRAN ET CADRE PHOTO",
        title: "Quand la TV se tait, elle devient une fenêtre",
        body: "Photos du jour de Bing en 4K, Unsplash, paysages, animaux mignons, anime, chefs-d'œuvre, vos propres photos, un lien ou un dégradé. La photo change au rythme que vous choisissez, ou jamais.",
        bullets: [
          "Chaque source se présente en image et se choisit d'un seul appui",
          "Vos photos et vos liens d'images s'importent depuis la page téléphone",
          "Le cadre photo sert d'économiseur, avec vos photos ou les vues aériennes (voir plus haut)",
        ],
        alt: "Capture réelle du cadre photo : un pont illuminé au coucher du soleil, avec l'heure et la date.",
        altVideo: "Enregistrement de l'économiseur d'écran de canvasOS.",
        caption: "Cadre photo et économiseur",
      },
      {
        kicker: "RÉGLAGES, PAGE TÉLÉPHONE, PROFILS",
        title: "Tout se règle depuis le canapé",
        body: "Les Réglages de l'accueil sont présentés comme ceux de l'Apple TV. Et un QR code ouvre la page téléphone, sur le même Wi-Fi, sans rien installer.",
        bullets: [
          "Page téléphone : pavé tactile de télécommande et clavier, import de photos, noms et vignettes des apps, installation d'APK",
          "Profils : un accueil par personne, et un profil enfant protégé par un code PIN",
          "Mises à jour depuis la TV : Système → Mise à jour, réglages et autorisations conservés",
        ],
        alt: "Capture réelle des Réglages de l'accueil : la liste Apparence, Fond d'écran et cadre photo, Top Shelf, Lecture et notifications, Mémoire et alimentation, Apps, Profils, Page téléphone, Système.",
        altVideo: "Enregistrement des Réglages de l'accueil.",
        caption: "Réglages de l'accueil",
      },
    ],
  },

  compare: {
    eyebrow: "AVANT / APRÈS",
    title: "Faites glisser, voyez la différence.",
    ledeIllustration:
      "À gauche, une <strong>illustration</strong> d'un accueil Android TV ordinaire. À droite, une <strong>capture réelle de canvasOS</strong>. Faites glisser le séparateur pour comparer.",
    ledeCapture:
      "À gauche, <strong>l'accueil d'origine</strong> de la TV. À droite, <strong>canvasOS</strong>. Faites glisser le séparateur pour comparer.",
    beforeLabel: "Avant",
    beforeSubIllustration: "Illustration · pas une capture",
    beforeSubCapture: "Accueil d'origine",
    afterLabel: "Après",
    afterSub: "canvasOS · capture réelle",
    beforeAltIllustration: "Illustration d'un accueil Android TV ordinaire en mosaïque, pas une capture.",
    beforeAltCapture: "L'accueil d'origine de la TV, avant canvasOS.",
    afterAlt: "Capture réelle de l'accueil de canvasOS : un Dock en verre sur une photo de ville.",
    hint: "Faites glisser, cliquez, ou placez le focus sur la poignée et utilisez ← →. Maj pour de plus grands pas ; double-clic pour recentrer.",
    disclaimerIllustration: "L'image de gauche est une illustration, pas une capture d'écran.",
    disclaimerCapture: "Les deux images sont des captures d'écran.",
  },

  gallery: {
    eyebrow: "EN MOUVEMENT",
    title: "Les détails, en mouvement.",
    lede: `Démarrage, Top Shelf, réglages, Centre de contrôle et économiseur, capturés sur une ${device} (${deviceOs}). Sélectionnez une image pour l'agrandir.`,
    fine: "L'animation de démarrage montrée est celle de canvasOS. Le logo « tvOS » visible dans les réglages est l'un des deux logos proposés par l'app ; Apple TV et tvOS sont des marques d'Apple Inc. Les photos, pochettes et icônes d'apps appartiennent à leurs auteurs.",
    items: [
      { caption: "Animation de démarrage", alt: "L'animation de démarrage de canvasOS : le logo canvasOS sur fond noir." },
      { caption: "Top Shelf", alt: "Le Top Shelf de Prime Video au-dessus du Dock." },
      { caption: "Réglages de l'accueil", alt: "La page Réglages de l'accueil, présentée comme celle de l'Apple TV." },
      { caption: "Centre de contrôle", alt: "Le Centre de contrôle : lecteur, météo et Bluetooth." },
      { caption: "Économiseur d'écran", alt: "Le cadre photo en économiseur : un pont au coucher du soleil, l'heure et la date." },
    ],
  },

  install: {
    eyebrow: "INSTALLATION",
    title: "Depuis votre ordinateur, en une fois.",
    lede: "Il faut un ordinateur sur le même réseau que la TV, l'adresse IP de la TV et le débogage ADB activé. Android 9 ou plus, sans root. Ensuite, canvasOS se met à jour depuis la TV.",
    downloads: [
      {
        name: "APK standard",
        use: `Pour Android TV / Google TV d'origine (box comme la ${device}), Sony BRAVIA, Xiaomi / Redmi, Hisense et la plupart des autres. Se déclare écran d'accueil.`,
        cta: "Télécharger canvasOS.apk",
      },
      {
        name: "APK guard",
        use: "Ne déclare aucun écran d'accueil, pour TCL / FFalcon qui refuse les APK de lanceur. S'installe par l'Interaction multi-écrans de TCL ; la Protection de l'accueil le garde au premier plan.",
        cta: "Télécharger canvasOS-guard.apk",
      },
    ],
    prereq:
      "Avant de commencer : la TV est sur le même réseau que l'ordinateur, le débogage ADB est activé (voir la marque plus bas), et la première connexion affiche « Autoriser le débogage USB ? » : cochez « Toujours autoriser ». L'ordinateur a besoin d'adb (Android SDK Platform-Tools).",
    agent: {
      title: "Confiez-le à un agent IA (recommandé)",
      body: "Envoyez ce message à un agent qui exécute des commandes sur votre ordinateur (Claude Code, Codex…). Il suit le guide d'installation de canvasOS, vous demande la marque et l'IP de la TV, installe, vérifie et garde un moyen de revenir à l'accueil d'origine.",
      prompt: `Installe le lanceur open source canvasOS sur ma TV et fais-en l'écran d'accueil.
IP de la TV : <IP_TV>
Garder ADB activé (Xiaomi seulement) : oui

Lis d'abord entièrement ${brand.site}/agent.md (sinon : ${brand.agentsRaw}), puis exécute les commandes sur mon ordinateur. Respecte ses règles absolues : n'éteins pas la TV, ne désinstalle et ne désactive rien sans me le demander. Note l'accueil d'origine avant de changer quoi que ce soit. Dis-moi quand je dois agir sur la TV.`,
      note: "La TV affiche deux ou trois demandes d'autorisation : choisissez « Autoriser » avec la télécommande. Sur TCL, choisissez « Accepter » (同意) quand l'envoi par l'Interaction multi-écrans s'affiche.",
    },
    quick: {
      eyebrow: "MACOS / LINUX",
      title: "Une commande. Installé et vérifié.",
      body: "Il faut adb et curl (et Python 3 sur TCL). Remplacez l'IP d'exemple. Le script note l'accueil d'origine avant de toucher à quoi que ce soit, installe la dernière version et vérifie la touche Accueil.",
      note: "Xiaomi : ajoutez <code>KEEP_ADB=1</code> pour garder le débogage ADB activé après la veille. <code>DRY_RUN=1</code> affiche ce que le script ferait, sans rien changer. Sous Windows, passez par un agent IA.",
    },
    update: {
      eyebrow: "MISE À JOUR / RETOUR",
      title: "Mises à jour sur place. Retour à tout moment.",
      body: "Réglages de l'accueil → Système → Mise à jour : une première pression vérifie, la seconde installe, réglages et autorisations conservés. Relancer la commande d'installation met aussi à jour.",
      restoreSummary: "Revenir à l'accueil d'origine",
      restoreBody: "Depuis le même ordinateur, qui garde l'état d'origine dans ~/.canvasos/. La protection est coupée, l'économiseur et l'accueil d'origine sont remis en place. canvasOS reste installé, sauf si vous ajoutez --uninstall.",
      guideLink: "Guide complet d'installation, de vérification et de retour ↗",
    },
    manualSummary: "Activer le débogage ADB · choisissez votre TV",
    adbTable: {
      caption: "Activer le débogage ADB selon la marque",
      head: ["TV", "Comment activer ADB"],
      rows: [
        [
          "Xiaomi / Redmi",
          "Réglages (设置) → À propos (关于) → <strong>Modèle du produit (产品型号)</strong> : appuyez plusieurs fois sur OK jusqu'à l'annonce du mode développeur. Puis Réglages → Compte et sécurité (账号与安全) : activez <strong>Débogage ADB (ADB 调试)</strong> et <strong>Installer des apps de sources inconnues (安装未知来源的应用)</strong>.",
        ],
        [
          "TCL / FFalcon",
          "Réglages (设置) → À propos (关于) → <strong>Informations sur l'appareil (本机信息)</strong>, puis <strong>Haut, Bas, Gauche, Droite</strong> sur la télécommande, à moins d'une seconde d'intervalle. Un groupe <strong>Plus (更多)</strong> apparaît en bas : activez <strong>ADB</strong>.",
        ],
        [
          "Sony BRAVIA",
          "Paramètres → Système → À propos → appuyez 7 fois sur OK sur <strong>Build</strong>. Puis Paramètres → Système → Options pour les développeurs → <strong>Débogage USB</strong>.",
        ],
        [
          "Android TV / Google TV d'origine, Hisense et autres",
          "Paramètres → Préférences de l'appareil → À propos → appuyez 7 fois sur <strong>Build</strong>. Puis Options pour les développeurs → <strong>Débogage USB</strong> (parfois appelé Débogage réseau ou Débogage ADB).",
        ],
      ],
    },
    allowNote:
      "À la première connexion, la TV demande « Autoriser le débogage USB ? » (sur les TV chinoises : 允许 USB 调试吗？). Choisissez <strong>Autoriser</strong> (允许) et cochez <strong>Toujours autoriser</strong> (始终允许). L'IP de la TV se trouve dans Paramètres → Réseau ou À propos.",
    advanced: {
      title: "Avancé : à quoi servent les autorisations",
      body: "L'agent et le script accordent par adb ce qu'une app ordinaire ne peut pas demander. Tout reste sur la TV.",
      head: ["Autorisation", "Usage"],
      rows: [
        ["WRITE_SECURE_SETTINGS", "Réactiver la Protection de l'accueil quand le système la coupe, garder le cadre photo comme économiseur, garder ADB activé (Xiaomi), luminosité sur Xiaomi"],
        ["Écouteur de notifications", "Relancer canvasOS après un arrêt et au démarrage ; épargner ce qui lit au nettoyage de la mémoire ; Lecture en cours et bannières de notifications (désactivables)"],
        ["SYSTEM_ALERT_WINDOW", "Ouvrir l'accueil depuis l'arrière-plan après le démarrage ; afficher les bannières par-dessus l'app regardée"],
        ["BLUETOOTH_CONNECT", "Android 12+ : lister et connecter les appareils Bluetooth déjà associés depuis le Centre de contrôle"],
        ["READ_TV_LISTINGS", "Top Shelf : lire la ligne « Continuer à regarder » publiée par les apps, rien d'autre"],
        ["APP_AUTO_START / APP_ASSOC_START", "TCL uniquement : autoriser canvasOS à démarrer avec la TV"],
      ],
    },
    safety:
      "<strong>N'éteignez jamais la TV à distance sans demander.</strong> Une TV éteinte ne se rallume qu'avec sa télécommande. Ni l'agent ni le script ne désactivent ou ne désinstallent une app système.",
  },

  compatibility: {
    eyebrow: "COMPATIBILITÉ",
    title: "Ce que votre TV sait vraiment faire.",
    lede: "canvasOS essaie d'abord l'API de la marque, puis se rabat sur Android standard. Voici la limite actuelle, sans enjoliver.",
    caption: "Android TV 9+ · les versions standard et guard se comportent de la même façon, à la déclaration d'accueil près",
    head: ["Fonction", "Toute Android TV", "Débogage local", "Sony BRAVIA seulement"],
    rows: [
      { cells: ["Accueil, Dock, dossiers, profils", "Oui", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Top Shelf (« Continuer à regarder », TMDB)", "Avec READ_TV_LISTINGS ; TMDB avec votre clé", "—", "—"], tones: ["cond", "na", "na"] },
      { cells: ["Fonds d'écran, cadre photo", "Oui", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Vues aériennes de l'Apple TV", "Oui, avec Internet", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Centre de contrôle, météo, horloge", "Oui", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Lecteur, paroles, bannières", "Écouteur de notifications (adb, une fois)", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Bluetooth", "Appareils déjà associés", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Veille, minuteur, extinction", "—", "Oui", "—"], tones: ["na", "ok", "na"] },
      { cells: ["Libérer la mémoire", "Processus en arrière-plan", "Ferme les apps inutilisées", "—"], tones: ["cond", "ok", "na"] },
      { cells: ["Mise à jour depuis la TV, installation d'APK", "—", "Oui", "—"], tones: ["na", "ok", "na"] },
      { cells: ["Télécommande du téléphone", "Sur les écrans de canvasOS", "Dans toutes les apps", "—"], tones: ["cond", "ok", "na"] },
      { cells: ["Entrées HDMI (Source)", "Selon la marque", "—", "Avec l'entrée actuelle"], tones: ["cond", "na", "ok"] },
      { cells: ["Luminosité", "Xiaomi, avec WRITE_SECURE_SETTINGS", "—", "Oui"], tones: ["cond", "na", "ok"] },
      { cells: ["Protection de l'accueil, relance", "Autorisation adb, une fois", "Oui", "—"], tones: ["ok", "ok", "na"] },
    ],
    notes: [
      {
        title: "Débogage local",
        body: "canvasOS se connecte à l'adb de la TV elle-même. La TV demande « Autoriser le débogage ? » : cochez « Toujours autoriser ». Même mécanisme sur toutes les marques.",
      },
      {
        title: "Box Android TV / Google TV",
        body: `canvasOS devient l'accueil par défaut comme n'importe quel lanceur. Sans entrée HDMI, Source affiche « Aucune entrée ». C'est l'appareil des captures de ce site (${device}).`,
      },
      {
        title: "Sony BRAVIA",
        body: "Après l'enregistrement du contrôle IP (un code PIN s'affiche sur la TV), la luminosité et les sources passent par l'API du téléviseur, et Source indique l'entrée actuelle.",
      },
      {
        title: "TCL / FFalcon, firmware chinois",
        body: "Version guard installée par l'Interaction multi-écrans, Protection de l'accueil. Les sources ouvrent le sélecteur de LiveTV ; pas de luminosité.",
      },
      {
        title: "Xiaomi / Redmi, firmware chinois",
        body: "La touche Accueil reste réservée à PatchWall : la Protection de l'accueil ramène canvasOS. Ne désactivez jamais le lanceur d'origine. Rétroéclairage par picture_backlight, sources par le lecteur de Xiaomi.",
      },
      {
        title: "Skyworth / Coocaa",
        body: "Sans sources standard, Source ouvre le sélecteur du lanceur. Luminosité et réglages restent ceux d'Android standard. Pas encore testé sur un vrai téléviseur.",
      },
      {
        title: "Autres marques",
        body: "Android n'a pas d'API standard pour le rétroéclairage : la luminosité reste grisée. Chaque marque est un fichier dans tv/ ; la section 9 d'AGENTS.md explique comment en ajouter une.",
      },
    ],
    scope: `<strong>Périmètre vérifié :</strong> les captures de ce site viennent d'une ${device} (${deviceOs}, version standard). Les adaptateurs Sony, Xiaomi, TCL / FFalcon et Skyworth / Coocaa n'ont pas tous été vérifiés sur chaque firmware.`,
  },

  faq: {
    eyebrow: "FAQ",
    title: "Avant d'installer.",
    items: [
      {
        q: "Faut-il rooter la TV ? Et la garantie ?",
        a: [
          "Pas de root, aucune modification du système. canvasOS s'installe comme une app, devient l'accueil par défaut par ADB et reçoit quelques autorisations qu'une app ordinaire ne peut pas demander.",
          "La garantie dépend du fabricant. Le vrai risque vient d'une commande ADB mal employée, comme désactiver le lanceur d'origine d'une Xiaomi : ni l'agent ni le script ne le font.",
        ],
      },
      {
        q: "Comment revenir à l'accueil d'origine ?",
        a: [
          "Avec le script, depuis l'ordinateur qui a installé canvasOS : <code>restore</code> coupe la protection, remet l'économiseur et l'accueil d'origine notés à l'installation, et laisse canvasOS installé. Ajoutez <code>--uninstall</code> pour le désinstaller aussi.",
          "À la main (ou en demandant « Désinstalle canvasOS » à l'agent) : coupez la protection, remettez l'économiseur d'origine s'il a été remplacé, puis désinstallez. La TV revient à son lanceur.",
        ],
        cmds: [
          restoreCmd,
          `adb -s ${TV}:5555 shell am broadcast -a ${P}.CONFIG -n ${P}/.ConfigReceiver --ez guard false --ez keep_adb false
adb -s ${TV}:5555 shell settings put secure screensaver_components <VALEUR_D_ORIGINE>
adb -s ${TV}:5555 uninstall ${P}`,
        ],
      },
      {
        q: "Pourquoi ne jamais désactiver le lanceur d'une Xiaomi ?",
        a: [
          "Sur Xiaomi / Redmi, PatchWall (com.mitv.tvhome) est imposé par une liste blanche du système, et l'accueil de secours refuse lui aussi les lanceurs tiers : le désactiver laisse un écran noir au démarrage.",
          "canvasOS utilise la Protection de l'accueil : quand PatchWall passe devant, canvasOS revient par-dessus en une demi-seconde environ. Aucune app système n'est désactivée ni supprimée.",
        ],
      },
      {
        q: "TCL affiche INSTALL_FAILED_VERIFICATION_FAILURE",
        a: [
          "Le vérificateur de TCL refuse toute installation par adb. Installez l'APK guard par l'Interaction multi-écrans (多屏互动) avec tools/tcast-push.py, puis choisissez « Accepter » sur la TV : le script et l'agent le font pour vous.",
          "Si le port 6553 ne répond pas, ce modèle n'a pas l'Interaction multi-écrans : copiez l'APK dans /sdcard/Download/ et installez-le avec un gestionnaire de fichiers de la TV.",
        ],
      },
      {
        q: "INSTALL_FAILED_UPDATE_INCOMPATIBLE à la mise à jour",
        a: [
          "Toutes les Releases sont signées avec la même clé et se mettent à jour sur place. Ce message n'apparaît que si la version installée est un APK compilé ailleurs ou une ancienne Release signée avec une clé jetable.",
          "Il faut alors désinstaller d'abord, ce qui efface les réglages et les autorisations de l'app. Le script s'arrête et l'explique ; il ne désinstalle jamais de lui-même.",
        ],
      },
      {
        q: "Pourquoi le curseur de luminosité est-il grisé ?",
        a: [
          "Android n'a pas d'API standard pour le rétroéclairage. Sony BRAVIA l'expose par son API de contrôle IP, Xiaomi par picture_backlight (avec WRITE_SECURE_SETTINGS). Ailleurs, la commande reste à sa place, grisée, plutôt que de ne rien faire.",
        ],
      },
      {
        q: "ADB répond toujours unauthorized",
        a: [
          "La TV n'a pas encore accepté cet ordinateur. Sur la TV, cochez « Toujours autoriser depuis cet ordinateur » et appuyez sur Autoriser. Xiaomi coupe ADB à chaque sortie de veille : l'option « Garder le débogage ADB activé » de canvasOS (KEEP_ADB=1) le réactive.",
        ],
      },
      {
        q: "canvasOS lit-il mon écran ou mes notifications ?",
        a: [
          "La Protection de l'accueil ne surveille que les changements de fenêtre, jamais le contenu de l'écran.",
          "L'écouteur de notifications relance canvasOS. Il sert aussi aux bannières, activées par défaut : le titre et le texte des notifications s'affichent 6 secondes, sans être enregistrés. Désactivez-les ou excluez des apps dans Réglages de l'accueil → Lecture et notifications ; Lecture en cours ne lit que les métadonnées de lecture.",
        ],
      },
      {
        q: "Comment mettre à jour ?",
        a: [
          "Réglages de l'accueil → Système → Mise à jour : une première pression vérifie la dernière Release, la seconde la télécharge et l'installe sur place, réglages et autorisations conservés (débogage local nécessaire). canvasOS signale aussi une nouvelle version une fois par jour. Depuis un ordinateur, relancer le script ou adb install -r marche toujours.",
        ],
      },
      {
        q: "Le site ou l'app collectent-ils des données ?",
        a: [
          "Le site est statique : ni cookies, ni statistiques, ni scripts tiers, polices du système uniquement.",
          "L'app n'a ni compte ni statistiques d'usage. Elle contacte les services qu'elle affiche : les sources de fond d'écran, Open-Meteo pour la météo (et ip-api.com une fois, tant qu'aucune ville n'est choisie), LRCLIB pour les paroles (titre, artiste, album et durée de chaque nouveau titre, tant que les paroles sont activées), TMDB avec votre clé, les serveurs d'Apple pour les vues aériennes et GitHub une fois par jour pour les mises à jour. Météo et paroles sont activées par défaut et se désactivent dans les réglages.",
        ],
      },
      {
        q: "Est-ce lié à Apple ?",
        a: [
          "Non. canvasOS est un projet open source indépendant, ni affilié à Apple, Sony, Xiaomi ou TCL, ni approuvé par eux. Son interface s'inspire de tvOS. Le choix de logo « tvOS » (carte-titre et film de démarrage de l'Apple TV) appartient à Apple et sert à un usage personnel sur votre propre TV.",
        ],
      },
    ],
  },

  finalCta: {
    eyebrow: "OBTENIR CANVASOS",
    title: "Redonnez envie d'allumer la TV.",
    body: "Téléchargez l'APK, activez ADB, puis lancez la commande ou confiez le guide à un agent. Android 9+, sans root, réversible.",
    primary: "Télécharger canvasOS",
    secondary: "Voir le code sur GitHub",
  },

  footer: {
    blurb: "Un lanceur Android TV open source calqué sur tvOS : Dock en verre, Top Shelf, Centre de contrôle et vues aériennes en économiseur.",
    cols: [
      {
        title: "Produit",
        links: [
          { href: "#features", label: "Fonctions" },
          { href: "#aerial", label: "Économiseur d'écran" },
          { href: brand.releases, label: "Versions" },
          { href: brand.repo, label: "GitHub" },
        ],
      },
      {
        title: "Installation",
        links: [
          { href: "#install", label: "Guide" },
          { href: "#compatibility", label: "Compatibilité" },
          { href: "#faq", label: "FAQ" },
          { href: brand.agentsRepo, label: "AGENTS.md" },
        ],
      },
      {
        title: "Projet",
        links: [
          { href: brand.readme, label: "README" },
          { href: brand.issues, label: "Signaler un problème" },
          { href: brand.notices, label: "Mentions tierces" },
        ],
      },
    ],
    legal: `© ${brand.year} canvasOS · projet open source indépendant. Ni affilié à Apple, Sony, Xiaomi ou TCL, ni approuvé ou soutenu par eux. « Apple TV » et « tvOS » sont des marques d'Apple Inc., citées ici pour décrire les vues aériennes et le logo proposé au choix. Les photos, pochettes et icônes d'apps visibles sur les captures appartiennent à leurs propriétaires. Les vues aériennes sont lues depuis les serveurs d'Apple. Installation et utilisation à vos risques.`,
  },
};

