/* Locale-independent facts: brand, links, release, capture device and media slots. */

export const brand = {
  name: "canvasOS",
  tagline: "Android TV",
  site: "https://canvasos.pages.dev",
  repo: "https://github.com/getisolated/canvasOS",
  releases: "https://github.com/getisolated/canvasOS/releases",
  apk: "https://github.com/getisolated/canvasOS/releases/latest/download/canvasOS.apk",
  agentsRepo: "https://github.com/getisolated/canvasOS/blob/main/AGENTS.md",
  agentsRaw: "https://raw.githubusercontent.com/getisolated/canvasOS/main/AGENTS.md",
  readme: "https://github.com/getisolated/canvasOS/blob/main/README.md",
  issues: "https://github.com/getisolated/canvasOS/issues",
  notices: "https://github.com/getisolated/canvasOS/blob/main/THIRD_PARTY_NOTICES.md",
  pkg: "com.getisolated.canvasos",
  year: 2026,
} as const;

/* The release the "latest update" cards describe (app/build.gradle.kts versionName).
   Update the cards in fr.ts / en.ts together with this number. */
export const release = { version: "1.2.0" } as const;

/* The device every capture (and, unless README says otherwise, every recording) comes from.
   Named wherever a caption says where an image was taken. */
export const device = "Thomson Streaming Box 240G";
export const deviceOs = "Google TV";

/* The one-line installer served from public/install.sh. Without TV=, it finds the TVs on the
   local network and asks which one. */
export const installCmd = `curl -fsSL ${brand.site}/install.sh | bash`;
export const restoreCmd = `curl -fsSL ${brand.site}/install.sh | bash -s -- restore`;

/* Real captures, 1920x1080 PNG in ../docs/screenshots, converted by scripts/build-assets.py. */
export const stills = {
  home: { src: "/img/home-1280.webp", small: "/img/home-640.webp", file: "accueil.png" },
  intro: { src: "/img/intro-1280.webp", small: "/img/intro-640.webp", file: "animation.png" },
  frame: { src: "/img/frame-1280.webp", small: "/img/frame-640.webp", file: "cadre-photo.png" },
  cc: { src: "/img/cc-1280.webp", small: "/img/cc-640.webp", file: "centre-de-controle.png" },
  settings: { src: "/img/settings-1280.webp", small: "/img/settings-640.webp", file: "reglages.png" },
  shelf: { src: "/img/shelf-1280.webp", small: "/img/shelf-640.webp", file: "top-shelf.png" },
} as const;

export type StillId = keyof typeof stills;

/* Video slots. Each one plays public/video/<id>.mp4 when that file exists at build time;
   otherwise the slot shows its capture as a still (README lists what each clip should show). */
export const slots = {
  home: { video: "/video/home.mp4", still: "home" },
  aerial: { video: "/video/aerial.mp4", still: "frame" },
  shelf: { video: "/video/shelf.mp4", still: "shelf" },
  cc: { video: "/video/cc.mp4", still: "cc" },
  intro: { video: "/video/intro.mp4", still: "intro" },
  settings: { video: "/video/settings.mp4", still: "settings" },
} as const satisfies Record<string, { video: string; still: StillId }>;

export type SlotId = keyof typeof slots;

/* The "before" pane of the comparison: the owner's capture when public/img/before.webp
   exists, otherwise a labelled SVG illustration in the page's language. */
export const before = {
  capture: "/img/before.webp",
  illustration: { fr: "/img/before-illustration-fr.svg", en: "/img/before-illustration-en.svg" },
} as const;

export const assets = {
  og: "/img/og.png",
  ogWidth: 1280,
  ogHeight: 640,
  favicon: "/img/favicon.svg",
  touchIcon: "/img/apple-touch-icon.png",
} as const;
