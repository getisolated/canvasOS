/* Build-time media resolution. Astro renders every page at build time in the website/
   directory (locally and on Cloudflare Pages), so public/ is checked on disk: a slot whose
   video has not been supplied yet renders its capture instead of a broken player. */
import { existsSync } from "node:fs";
import { join } from "node:path";
import { before, slots, stills } from "./site";
import type { SlotId } from "./site";
import type { Locale } from "./lang";

const inPublic = (path: string) => existsSync(join(process.cwd(), "public", path));

export interface ResolvedSlot {
  /* Video URL, only when the file exists. */
  video?: string;
  poster: string;
  small: string;
  /* File name shown in the gallery captions. */
  file: string;
}

export function slot(id: SlotId): ResolvedSlot {
  const s = slots[id];
  const still = stills[s.still];
  const hasVideo = inPublic(s.video);
  return {
    video: hasVideo ? s.video : undefined,
    poster: still.src,
    small: still.small,
    file: hasVideo ? s.video.split("/").pop()! : still.file,
  };
}

export const anyVideo = () => (Object.keys(slots) as SlotId[]).some((id) => slot(id).video);

export function beforeImage(locale: Locale): { src: string; isCapture: boolean } {
  return inPublic(before.capture)
    ? { src: before.capture, isCapture: true }
    : { src: before.illustration[locale], isCapture: false };
}

/* AGENTS.md lives at the repository root, one level above website/. */
export const agentsPath = () => join(process.cwd(), "..", "AGENTS.md");
