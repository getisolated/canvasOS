/* /agent.md is an exact copy of the repository's AGENTS.md, read at build time, so the
   guide the install prompt points at can never drift from the one in the repo. */
import { readFileSync } from "node:fs";
import type { APIRoute } from "astro";
import { agentsPath } from "../data/media";

export const GET: APIRoute = () =>
  new Response(readFileSync(agentsPath()), {
    headers: { "content-type": "text/markdown; charset=utf-8" },
  });
