/* English copy, served at /en/. Mirrors fr.ts; every claim follows AGENTS.md. */
import type { Copy } from "./lang";
import { brand, device, deviceOs, release, restoreCmd } from "./site";

const P = brand.pkg;
const TV = "192.168.x.x";

export const en: Copy = {
  htmlLang: "en",
  path: "/en/",
  meta: {
    title: "canvasOS — your TV home screen, the tvOS way | Android TV launcher",
    description:
      "canvasOS is an open-source Android TV launcher modelled on tvOS: glass dock, Top Shelf, customisable Control Center, photo frame and Apple TV aerial screensaver. Android 9+, no root, reversible.",
    ogTitle: "canvasOS — your TV home screen, the tvOS way",
    ogDescription:
      "Glass dock, Top Shelf, Control Center and Apple TV aerial screensavers. Open source, Android 9+, no root.",
    ogLocale: "en_US",
    ogLocaleAlt: "fr_FR",
  },
  ui: {
    skip: "Skip to content",
    navLabel: "Main",
    menuOpen: "Open menu",
    menuClose: "Close menu",
    homeLabel: "canvasOS home",
    copy: "Copy",
    copied: "Copied",
    copyFailed: "Copy failed",
    compareValue: "Showing {p}% of the before image",
    compareName: "Compare the stock home screen with a canvasOS capture",
    prev: "Previous",
    next: "Next",
    close: "Close",
    viewer: "Screenshot viewer",
    pauseClips: "Pause recordings",
    playClips: "Play recordings",
    still: "Real capture",
    recording: "Real recording",
  },
  nav: [
    { href: "#features", label: "Features" },
    { href: "#aerial", label: "Screensaver" },
    { href: "#compare", label: "Compare" },
    { href: "#install", label: "Install" },
    { href: "#compatibility", label: "Compatibility" },
    { href: "#faq", label: "FAQ" },
  ],
  langSwitch: { href: "/", label: "FR", lang: "fr", name: "Français" },
  headerCta: "Download",
  menuCta: "Download the canvasOS APK",
  menuNote: "Android TV 9+ · no root · independent project, not affiliated with Apple",

  hero: {
    eyebrow: "CANVASOS · ANDROID TV LAUNCHER",
    titleLines: ["Your TV home screen,", "the tvOS way."],
    sub: "canvasOS replaces the home screen of your Android TV, Google TV or Android box: a glass dock resting on a big photo, a Top Shelf that picks up what you were watching, and Apple TV aerial films when the room goes quiet.",
    primary: "Download",
    secondary: "See how to install",
    chips: ["Android TV 9+", "No root · reversible", "Some features vary by brand"],
    chipAerial: { title: "Aerial screensaver", sub: "The Apple TV aerial films" },
    chipDock: { title: "Glass dock", sub: "16:9 tiles, liquid glass" },
    alt: "canvasOS home screen on a Google TV box: a city at dusk and a glass dock with YouTube, Spotify, Netflix, Prime Video and Disney+.",
    note: "An independent open-source project, not affiliated with Apple. “Apple TV” and “tvOS” are trademarks of Apple Inc.",
  },

  proof: [
    `Tested on a ${device}`,
    "Apple TV aerial screensaver",
    "Updates from the TV",
    "No root, fully reversible",
  ],

  release: {
    label: `What's new in canvasOS ${release.version}`,
    pill: `Version ${release.version} · all releases ↗`,
    cards: [
      {
        title: "A player on the home screen.",
        body: "When an app plays something, a glass capsule in the top-left corner shows the artwork, title and artist. OK opens the app that is playing.",
      },
      {
        title: "Lyrics, in time.",
        body: "When LRCLIB has synced lyrics for the track, a Lyrics button slides out of the capsule; OK shows them large, the sung line in white.",
      },
      {
        title: "Your keys, your shortcuts.",
        body: "Home settings → Remote: a key, short or long press, opens the Control Center, the lyrics, an app or an HDMI input.",
      },
    ],
  },

  aerial: {
    eyebrow: "SCREENSAVER",
    title: "Apple TV aerials on your screensaver.",
    lede: "When the TV sits idle, the photo frame takes over: your wallpaper photos, or the Apple TV aerial films gliding over coastlines, mountains and cities. Offline, it falls back to photos.",
    altStill: "Real capture of the photo frame screensaver: a lit bridge at sunset, with the time, the date and the photo credit in the bottom-left corner.",
    altVideo: "Recording of the canvasOS screensaver showing the Apple TV aerial films.",
    badgeStill: "Real capture · Photos content",
    badgeVideo: "Recorded on TV · aerial films",
    captionStill: `Photo frame screensaver, Photos content · captured on a ${device}`,
    captionVideo: `Aerial screensaver · recorded on a ${device}`,
    points: [
      "Screensaver content: Photos, or Aerial views (Apple TV), streamed from Apple's servers at about 15–20 Mbit/s",
      "Offline, it falls back to photos automatically",
      "The photo frame becomes the system screensaver after the idle delay you choose",
    ],
    attribution:
      "Aerial films stream from Apple's servers; canvasOS ships none of them. “Apple TV” is a trademark of Apple Inc. This project is not affiliated with Apple.",
  },

  features: {
    eyebrow: "FEATURES",
    title: "Made for the couch, not for a phone.",
    lede: "Big focus, few layers, quiet glass: an interface drawn for ten feet away, not scaled up from a handset.",
    items: [
      {
        kicker: "GLASS DOCK",
        title: "A glass dock resting on the picture",
        body: "Apps sit on a glass dock near the bottom of the screen; the next row barely peeks out below it, and the rest of the screen belongs to the photo.",
        bullets: [
          "Rounded 16:9 tiles: the focused tile grows, tilts and catches the light",
          "Folders like on tvOS, pinned HDMI inputs, apps renamed, hidden or given another picture",
          "Recents or Fixed dock: the app you open moves to the front, or your order stays",
        ],
        alt: "Real capture of the canvasOS home screen: a city at dusk and a glass dock with YouTube, Spotify, Netflix, Prime Video and Disney+.",
        altVideo: "Recording of the canvasOS home screen: the glass dock and its tiles.",
        caption: "Home screen",
      },
      {
        kicker: "TOP SHELF",
        title: "Focus an app and the screen fills up",
        body: "When a dock app is focused, its content fills the screen above the dock, one picture every 8 seconds. OK opens the app.",
        bullets: [
          "The “Continue watching” row that YouTube, Netflix, Prime Video… publish for Android TV, read on the TV",
          "For Netflix, Prime Video, Disney+, Apple TV, Max, Paramount+, Canal+ and Crunchyroll: this week's popular titles in France from TMDB, with your free key",
          "Limit it to the Android TV row, or turn it off, in one setting",
        ],
        alt: "Real capture of the Top Shelf: Prime Video focused, “Continue watching” shows The Grand Tour above the dock.",
        altVideo: "Recording of the Top Shelf: the focused app's content above the dock.",
        caption: "Top Shelf",
      },
      {
        kicker: "CONTROL CENTER",
        title: "One Back press away",
        body: "Glass controls float in the top-right corner, on a four-column grid you rearrange on the TV, like on tvOS.",
        bullets: [
          "Power off, Sleep with a timer, Network with the IP address, Free up memory, Next photo, Photo frame, Settings",
          "Player with artwork and progress, five-day Weather, Bluetooth; Brightness and Source where the TV exposes them",
          "Long-press its button to move, resize and add controls, on up to three pages",
        ],
        alt: "Real capture of the Control Center: player with artwork, round buttons, three-day weather and Bluetooth above the dock.",
        altVideo: "Recording of the canvasOS Control Center.",
        caption: "Control Center",
      },
      {
        kicker: "WALLPAPER & PHOTO FRAME",
        title: "When the TV is quiet, make it a window",
        body: "Bing daily photos in 4K, Unsplash, landscapes, cute animals, anime, masterpieces, your own photos, a link or a gradient. The photo changes as often as you like, or never.",
        bullets: [
          "Every source is shown as a picture and picked with one press",
          "Your photos and image links come in from the phone page",
          "The photo frame doubles as the screensaver, with your photos or the aerial films (see above)",
        ],
        alt: "Real capture of the photo frame: a lit bridge at sunset, with the time and the date.",
        altVideo: "Recording of the canvasOS screensaver.",
        caption: "Photo frame and screensaver",
      },
      {
        kicker: "SETTINGS, PHONE PAGE, PROFILES",
        title: "Everything is set from the couch",
        body: "Home settings are laid out like the Apple TV's. And a QR code opens the phone page, on the same Wi-Fi, with nothing to install.",
        bullets: [
          "Phone page: touchpad remote and keyboard, photo upload, app names and tiles, APK install",
          "Profiles: one home screen per person, and a kids profile behind a PIN",
          "Updates from the TV: System → Update, settings and permissions kept",
        ],
        alt: "Real capture of Home settings: the list Appearance, Wallpaper and photo frame, Top Shelf, Playback and notifications, Memory and power, Apps, Profiles, Phone page, System (in French).",
        altVideo: "Recording of Home settings.",
        caption: "Home settings",
      },
    ],
  },

  compare: {
    eyebrow: "BEFORE / AFTER",
    title: "Drag to see the difference.",
    ledeIllustration:
      "On the left, an <strong>illustration</strong> of an ordinary Android TV home screen. On the right, a <strong>real canvasOS capture</strong>. Drag the divider to compare.",
    ledeCapture:
      "On the left, the TV's <strong>stock home screen</strong>. On the right, <strong>canvasOS</strong>. Drag the divider to compare.",
    beforeLabel: "Before",
    beforeSubIllustration: "Illustration · not a capture",
    beforeSubCapture: "Stock home screen",
    afterLabel: "After",
    afterSub: "canvasOS · real capture",
    beforeAltIllustration: "Illustration of an ordinary tiled Android TV home screen, not a capture.",
    beforeAltCapture: "The TV's stock home screen, before canvasOS.",
    afterAlt: "Real capture of the canvasOS home screen: a glass dock over a city photo.",
    hint: "Drag, click, or focus the handle and use ← →. Hold Shift for larger steps; double-click to reset.",
    disclaimerIllustration: "The left side is an illustration, not a screenshot.",
    disclaimerCapture: "Both sides are screenshots.",
  },

  gallery: {
    eyebrow: "IN MOTION",
    title: "The details, in motion.",
    lede: `Boot, Top Shelf, settings, Control Center and screensaver, captured on a ${device} (${deviceOs}). Select one to enlarge it.`,
    fine: "The boot animation shown is canvasOS's own. The “tvOS” logo visible in the settings is one of the two logos the app offers; Apple TV and tvOS are trademarks of Apple Inc. Photos, artwork and app icons belong to their owners.",
    items: [
      { caption: "Boot animation", alt: "The canvasOS boot animation: the canvasOS logo on black." },
      { caption: "Top Shelf", alt: "The Prime Video Top Shelf above the dock." },
      { caption: "Home settings", alt: "The Home settings page, laid out like the Apple TV's." },
      { caption: "Control Center", alt: "The Control Center: player, weather and Bluetooth." },
      { caption: "Screensaver", alt: "The photo frame screensaver: a bridge at sunset, the time and the date." },
    ],
  },

  install: {
    eyebrow: "INSTALL",
    title: "From your computer, in one go.",
    lede: "You need a computer on the same network as the TV and ADB debugging turned on; the agent or the script finds the TV on the network. Android 9 or later, no root. After that, canvasOS updates itself from the TV. Its interface is in French.",
    downloads: [
      {
        name: "Standard APK",
        use: `For stock Android TV / Google TV (boxes like the ${device}), Sony BRAVIA, Hisense, Philips and most others. Declares itself a home screen.`,
        cta: "Download canvasOS.apk",
      },
      {
        name: "All releases",
        use: "Release notes and earlier APKs, on GitHub. Every release is signed with the same key and updates in place.",
        cta: "See the releases",
      },
    ],
    prereq:
      "Before you start: the TV is on the same network as the computer, ADB debugging is on (see your brand below), and the first connection shows “Allow USB debugging?” — tick “Always allow”. The computer needs adb (Android SDK Platform-Tools).",
    agent: {
      title: "Let an AI agent do it (recommended)",
      body: "Send this message to an agent that can run commands on your computer (Claude Code, Codex and similar). It follows the canvasOS install guide, finds the TV on your network, walks you through the remote step by step, installs, verifies and keeps a way back to the stock home screen.",
      prompt: `Install the open-source canvasOS launcher on my Android TV and make it the home screen.

Read ${brand.site}/agent.md fully first (fallback: ${brand.agentsRaw}), then find my TV on the network yourself and guide me step by step: tell me what to press on the remote and what will appear on screen, then wait for me to confirm. Do not power off the TV, and do not uninstall or disable anything without asking me.`,
      note: "The TV shows two or three permission prompts; the agent tells you when, and you choose “Allow” with the remote.",
    },
    quick: {
      eyebrow: "MACOS / LINUX",
      title: "One command. Installed and verified.",
      body: "You need adb and curl. The script looks for Android TVs on your network and lets you pick yours, records the original home screen before touching anything, installs the latest release and checks the Home button.",
      note: "To target one TV, add its address: <code>TV=192.168.x.x bash</code>. <code>DRY_RUN=1</code> prints what the script would do without changing anything. On Windows, use an AI agent.",
    },
    update: {
      eyebrow: "UPDATE / RESTORE",
      title: "Updates in place. A way back, anytime.",
      body: "Home settings → System → Update: the first press checks, the second installs, keeping settings and permissions. Running the command again also updates.",
      restoreSummary: "Restore the original home screen",
      restoreBody: "From the same computer, which keeps the original state in ~/.canvasos/. The guard is switched off, the original screensaver and home screen are put back. canvasOS stays installed unless you add --uninstall.",
      guideLink: "Full install, verification and restore guide ↗",
    },
    manualSummary: "Enable ADB debugging · choose your TV",
    adbTable: {
      caption: "Enabling ADB debugging by brand",
      head: ["TV", "How to enable ADB"],
      rows: [
        [
          "Stock Android TV / Google TV, Hisense, Philips and others",
          "Google TV: Settings → System → About → press <strong>Android TV OS build</strong> seven times. Android TV: Settings → Device Preferences → About → press <strong>Build</strong> seven times. Then Developer options → <strong>USB debugging</strong> (sometimes called Network debugging or ADB debugging).",
        ],
        [
          "Sony BRAVIA",
          "Settings → System → About → press OK on <strong>Build</strong> seven times. Then Settings → System → Developer options → <strong>USB debugging</strong>.",
        ],
      ],
    },
    allowNote:
      "On the first connection the TV asks “Allow USB debugging?”. Choose <strong>Allow</strong> and tick <strong>Always allow</strong>. If the TV is not found on the network, its IP address is under Settings → Network or About.",
    advanced: {
      title: "Advanced: what the permissions are for",
      body: "The agent and the script grant over adb what an ordinary app cannot ask for. Everything stays on the TV.",
      head: ["Permission", "Purpose"],
      rows: [
        ["WRITE_SECURE_SETTINGS", "Switch the home guard back on when the system turns it off, keep the photo frame as screensaver"],
        ["Notification listener", "Restart canvasOS after it is killed and at boot; spare what is playing when freeing memory; Now playing and notification banners (both can be turned off)"],
        ["SYSTEM_ALERT_WINDOW", "Open the home screen from the background after boot; show banners over the app you are watching"],
        ["BLUETOOTH_CONNECT", "Android 12+: list and connect already paired Bluetooth devices from the Control Center"],
        ["READ_TV_LISTINGS", "Top Shelf: read the “Continue watching” row apps publish, nothing else"],
      ],
    },
    safety:
      "<strong>Never power off the TV remotely without asking.</strong> A TV that is off can only be turned back on with its remote. Neither the agent nor the script disables or uninstalls a system app.",
  },

  compatibility: {
    eyebrow: "COMPATIBILITY",
    title: "What your TV can really do.",
    lede: "canvasOS tries the brand's API first and falls back to standard Android. This is the current boundary, without the gloss.",
    caption: "Android TV and Google TV, Android 9 or later · no root",
    head: ["Feature", "Any Android TV", "On-device debugging", "Sony BRAVIA only"],
    rows: [
      { cells: ["Home screen, dock, folders, profiles", "Yes", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Top Shelf (“Continue watching”, TMDB)", "With READ_TV_LISTINGS; TMDB with your key", "—", "—"], tones: ["cond", "na", "na"] },
      { cells: ["Wallpapers, photo frame", "Yes", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Apple TV aerial screensaver", "Yes, needs internet", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Control Center, weather, clock", "Yes", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Player, lyrics, banners", "Notification listener (adb, once)", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Bluetooth", "Already paired devices", "—", "—"], tones: ["ok", "na", "na"] },
      { cells: ["Sleep, sleep timer, power off", "—", "Yes", "—"], tones: ["na", "ok", "na"] },
      { cells: ["Free up memory", "Background processes", "Closes unused apps", "—"], tones: ["cond", "ok", "na"] },
      { cells: ["Update from the TV, APK install", "—", "Yes", "—"], tones: ["na", "ok", "na"] },
      { cells: ["Phone remote", "On canvasOS screens", "In every app", "—"], tones: ["cond", "ok", "na"] },
      { cells: ["HDMI inputs (Source)", "Depends on the brand", "—", "Plus the current input"], tones: ["cond", "na", "ok"] },
      { cells: ["Brightness", "No standard Android API", "—", "Yes"], tones: ["na", "na", "ok"] },
      { cells: ["Home guard, revival after kill", "One-time adb grant", "Yes", "—"], tones: ["ok", "ok", "na"] },
    ],
    notes: [
      {
        title: "On-device debugging",
        body: "canvasOS talks to the TV's own adb. The TV asks “Allow debugging?”; tick “Always allow”. The same mechanism works on every brand.",
      },
      {
        title: "Android TV / Google TV boxes",
        body: `canvasOS becomes the default home screen like any launcher. Without HDMI inputs, Source shows “No input”. This is the device behind the captures on this site (${device}).`,
      },
      {
        title: "Sony BRAVIA",
        body: "After IP-control registration (the TV shows a PIN), brightness and inputs go through the TV's own API, and Source shows the current input.",
      },
      {
        title: "Home key reserved",
        body: "When the system keeps the Home key for its own launcher, the home guard brings canvasOS back within about half a second, without disabling or uninstalling anything.",
      },
      {
        title: "Xiaomi / Redmi",
        body: "Never disable the stock launcher (com.mitv.tvhome): the TV would boot to a black screen. Neither the agent nor the script does.",
      },
      {
        title: "Other brands",
        body: "Android has no standard backlight API, so brightness stays greyed out. Each brand is one file in tv/; section 9 of AGENTS.md explains how to add one.",
      },
    ],
    scope: `<strong>Verified scope:</strong> the captures on this site come from a ${device} (${deviceOs}, standard build). Sony's IP control has not been verified on every BRAVIA model.`,
  },

  faq: {
    eyebrow: "FAQ",
    title: "Before you install.",
    items: [
      {
        q: "Does it need root? What about my warranty?",
        a: [
          "No root and no system changes. canvasOS installs like an app, becomes the default home screen through ADB and gets a few permissions an ordinary app cannot ask for.",
          "Warranty terms are up to the manufacturer. The real risk is a misused ADB command, such as disabling the stock launcher: neither the agent nor the script does that without your consent.",
        ],
      },
      {
        q: "How do I restore the original home screen?",
        a: [
          "With the script, from the computer that installed canvasOS: <code>restore</code> turns the guard off, puts back the screensaver and home screen recorded at install, and leaves canvasOS installed. Add <code>--uninstall</code> to remove it as well.",
          "By hand (or by asking the agent to “uninstall canvasOS”): turn the guard off, put the original screensaver back if it was replaced, then uninstall. The TV returns to its own launcher.",
        ],
        cmds: [
          restoreCmd,
          `adb -s ${TV}:5555 shell am broadcast -a ${P}.CONFIG -n ${P}/.ConfigReceiver --ez guard false
adb -s ${TV}:5555 shell settings put secure screensaver_components <ORIGINAL_VALUE>
adb -s ${TV}:5555 uninstall ${P}`,
        ],
      },
      {
        q: "INSTALL_FAILED_UPDATE_INCOMPATIBLE when updating",
        a: [
          "Every Release is signed with the same key and updates in place. This message only appears when the installed copy is an APK built elsewhere or an old Release signed with a throwaway key.",
          "It then has to be uninstalled first, which erases the app's settings and permissions. The script stops and explains; it never uninstalls on its own.",
        ],
      },
      {
        q: "Why is the brightness slider greyed out?",
        a: [
          "Android has no standard backlight API. Sony BRAVIA exposes brightness through its IP-control API. Elsewhere the control stays in place, greyed out, rather than doing nothing.",
        ],
      },
      {
        q: "ADB keeps saying unauthorized",
        a: [
          "The TV has not accepted this computer yet. On the TV, tick “Always allow from this computer” and press Allow.",
        ],
      },
      {
        q: "Does canvasOS read my screen or notifications?",
        a: [
          "The home guard watches window changes only, never the screen's content.",
          "The notification listener restarts canvasOS. It also powers the banners, on by default: notification titles and text are shown for 6 seconds and never stored. Turn them off or exclude apps in Home settings → Playback and notifications; Now playing reads playback metadata only.",
        ],
      },
      {
        q: "How do I update?",
        a: [
          "Home settings → System → Update: the first press checks the latest Release, the second downloads and installs it in place, keeping settings and permissions (on-device debugging needed). canvasOS also flags a new version once a day. From a computer, running the script again or adb install -r always works.",
        ],
      },
      {
        q: "Does the site or the app collect data?",
        a: [
          "The website is static: no cookies, no analytics, no third-party scripts, system fonts only.",
          "The app has no account and no usage statistics. It contacts the services it shows: the wallpaper sources, Open-Meteo for the weather (and ip-api.com once, while no city is chosen), LRCLIB for lyrics (title, artist, album and duration of each new track, while lyrics are on), TMDB with your key, Apple's servers for the aerials, and GitHub once a day for updates. Weather and lyrics are on by default and can be turned off in the settings.",
        ],
      },
      {
        q: "Is this affiliated with Apple?",
        a: [
          "No. canvasOS is an independent open-source project, not affiliated with or endorsed by Apple, Google or Sony. Its interface is inspired by tvOS. The “tvOS” logo option (the Apple TV title card and boot film) belongs to Apple and is meant for personal use on your own TV.",
        ],
      },
    ],
  },

  finalCta: {
    eyebrow: "GET CANVASOS",
    title: "Make the TV worth turning on.",
    body: "Download the APK, enable ADB, then run the command or hand the guide to an agent. Android 9+, no root, reversible.",
    primary: "Download canvasOS",
    secondary: "View source on GitHub",
  },

  footer: {
    blurb: "An open-source Android TV launcher modelled on tvOS: glass dock, Top Shelf, Control Center and an aerial screensaver.",
    cols: [
      {
        title: "Product",
        links: [
          { href: "#features", label: "Features" },
          { href: "#aerial", label: "Screensaver" },
          { href: brand.releases, label: "Releases" },
          { href: brand.repo, label: "GitHub" },
        ],
      },
      {
        title: "Install",
        links: [
          { href: "#install", label: "Guide" },
          { href: "#compatibility", label: "Compatibility" },
          { href: "#faq", label: "FAQ" },
          { href: brand.agentsRepo, label: "AGENTS.md" },
        ],
      },
      {
        title: "Project",
        links: [
          { href: brand.readme, label: "README" },
          { href: brand.issues, label: "Issues" },
          { href: brand.notices, label: "Third-party notices" },
        ],
      },
    ],
    legal: `© ${brand.year} canvasOS · independent open-source project. Not affiliated with, endorsed by or sponsored by Apple, Google or Sony. “Apple TV” and “tvOS” are trademarks of Apple Inc., named here only to describe the aerial screensaver and the optional logo. Photos, artwork and app icons visible in the captures belong to their owners. Aerial films stream from Apple's servers. Install and use at your own risk.`,
  },
};
