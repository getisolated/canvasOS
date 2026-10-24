/* The copy contract both locales satisfy (fr.ts is the source, en.ts the translation).
   Strings marked "html" may contain <strong>, <code> and <a>; everything else is plain text. */

export type Locale = "fr" | "en";

export interface Card {
  title: string;
  body: string;
}

export interface Feature {
  kicker: string;
  title: string;
  body: string;
  bullets: string[];
  /* describes the capture; altVideo replaces it once the slot has a recording */
  alt: string;
  altVideo: string;
  /* subject only; " · Capture réelle" or " · Enregistrement réel" is appended */
  caption: string;
}

export interface FaqItem {
  q: string;
  /* html paragraphs */
  a: string[];
  cmds?: string[];
}

export interface Copy {
  htmlLang: string;
  path: string;
  meta: {
    title: string;
    description: string;
    ogTitle: string;
    ogDescription: string;
    ogLocale: string;
    ogLocaleAlt: string;
  };
  ui: {
    skip: string;
    navLabel: string;
    menuOpen: string;
    menuClose: string;
    homeLabel: string;
    copy: string;
    copied: string;
    copyFailed: string;
    /* "{p}" is replaced by the percentage */
    compareValue: string;
    compareName: string;
    prev: string;
    next: string;
    close: string;
    viewer: string;
    pauseClips: string;
    playClips: string;
    /* caption prefix for a still capture / a screen recording */
    still: string;
    recording: string;
  };
  nav: { href: string; label: string }[];
  langSwitch: { href: string; label: string; lang: string; name: string };
  headerCta: string;
  menuCta: string;
  menuNote: string;
  hero: {
    eyebrow: string;
    titleLines: [string, string];
    sub: string;
    primary: string;
    secondary: string;
    chips: string[];
    chipAerial: { title: string; sub: string };
    chipDock: { title: string; sub: string };
    alt: string;
    note: string;
  };
  proof: string[];
  release: { label: string; pill: string; cards: Card[] };
  aerial: {
    eyebrow: string;
    title: string;
    lede: string;
    altStill: string;
    altVideo: string;
    badgeStill: string;
    badgeVideo: string;
    captionStill: string;
    captionVideo: string;
    points: string[];
    attribution: string;
  };
  features: { eyebrow: string; title: string; lede: string; items: Feature[] };
  compare: {
    eyebrow: string;
    title: string;
    /* html */
    ledeIllustration: string;
    ledeCapture: string;
    beforeLabel: string;
    beforeSubIllustration: string;
    beforeSubCapture: string;
    afterLabel: string;
    afterSub: string;
    beforeAltIllustration: string;
    beforeAltCapture: string;
    afterAlt: string;
    hint: string;
    disclaimerIllustration: string;
    disclaimerCapture: string;
  };
  gallery: {
    eyebrow: string;
    title: string;
    lede: string;
    fine: string;
    /* intro, shelf, settings, cc, aerial (gallery order) */
    items: { caption: string; alt: string }[];
  };
  install: {
    eyebrow: string;
    title: string;
    lede: string;
    /* the standard APK, then the releases page */
    downloads: { name: string; use: string; cta: string }[];
    prereq: string;
    agent: { title: string; body: string; prompt: string; note: string };
    quick: {
      eyebrow: string;
      title: string;
      body: string;
      /* html */
      note: string;
    };
    update: {
      eyebrow: string;
      title: string;
      body: string;
      restoreSummary: string;
      restoreBody: string;
      guideLink: string;
    };
    manualSummary: string;
    adbTable: { caption: string; head: [string, string]; rows: [string, string][] };
    /* html */
    allowNote: string;
    advanced: { title: string; body: string; head: [string, string]; rows: [string, string][] };
    /* html */
    safety: string;
  };
  compatibility: {
    eyebrow: string;
    title: string;
    lede: string;
    caption: string;
    head: [string, string, string, string];
    /* cells: [feature, any TV, on-device debugging, Sony only]; tone per cell: ok | cond | na */
    rows: { cells: [string, string, string, string]; tones: ["ok" | "cond" | "na", "ok" | "cond" | "na", "ok" | "cond" | "na"] }[];
    notes: Card[];
    /* html */
    scope: string;
  };
  faq: { eyebrow: string; title: string; items: FaqItem[] };
  finalCta: { eyebrow: string; title: string; body: string; primary: string; secondary: string };
  footer: {
    blurb: string;
    cols: { title: string; links: { href: string; label: string }[] }[];
    legal: string;
  };
}
