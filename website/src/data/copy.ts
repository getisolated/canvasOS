import { fr } from "./fr";
import { en } from "./en";
import type { Copy, Locale } from "./lang";

const copies: Record<Locale, Copy> = { fr, en };

export const getCopy = (locale: Locale): Copy => copies[locale];
export type { Copy, Locale };
