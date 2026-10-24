# canvasOS website

The site at https://canvasos.pages.dev: French at `/`, English at `/en/`. Static Astro build,
built and deployed by Cloudflare Pages from this folder. **Not affiliated with Apple.**

Its layout, components and CSS are adapted from the Tigris website (MIT); the credit is in
`../THIRD_PARTY_NOTICES.md`. Keep the markup and `src/styles/global.css` close to that design;
canvasOS additions go at the end of the stylesheet.

## Commands

```sh
pnpm install --frozen-lockfile   # pnpm 11 (pinned in package.json "packageManager"), Node 24 (.nvmrc)
pnpm exec astro check            # must report 0 errors
pnpm build                       # astro build + scripts/check-csp.mjs -> dist/
python3 scripts/preview.py       # serve dist/ at http://127.0.0.1:8789 with the _headers rules applied
python3 scripts/build-assets.py  # regenerate public/img from ../docs/screenshots and ../DA (needs Pillow)
```

## Reproducible deploy (Cloudflare Pages, Git integration)

Project **`canvasos`**, connected to `github.com/getisolated/canvasOS`. Cloudflare clones the whole
repository, so the build can read `../AGENTS.md`.

| Setting | Value |
|---|---|
| Production branch | `main` |
| Framework preset | `Astro` (or `None`; the commands below are what matter) |
| Root directory (advanced) | `website` |
| Build command | `pnpm install --frozen-lockfile && pnpm build` |
| Build output directory | `dist` |
| Build system version | v3 (latest) |
| Environment variables | `NODE_VERSION=24`, `PNPM_VERSION=11.13.1` |

`.nvmrc` and the `packageManager` field pin the same versions; the two environment variables make
Cloudflare's build image use them even where it does not read those files. No secrets, no API keys.

What the build produces in `dist/`:

- `index.html` (French) and `en/index.html` (English); `404.html` + `404.css` (static, from `public/`).
- `agent.md`: an exact copy of the repository's `AGENTS.md`, read at build time by
  `src/pages/agent.md.ts`. It cannot drift: every push to `main` rebuilds it.
- `install.sh`: the one-command installer (`public/install.sh`), served as `text/plain`.
- `_headers`: CSP and cache rules. The only inline script (the `no-js` switch in
  `src/layouts/Base.astro`) is allowed by its sha256; `scripts/check-csp.mjs` fails the build if an
  inline script is not in the CSP. `_redirects`: `/download` to the latest APK.
- `sitemap.xml`, `robots.txt`, `img/*`, `video/*` (when supplied), `_astro/*`.

Do not deploy by hand while the Git integration is on; a push to `main` is the deploy.

## Media the owner supplies

Every video slot already looks finished: until its file exists, it shows a real capture as a still
(no player, no request for the missing file). When a file is added to `public/video/` and pushed,
the next build switches that slot to the recording and updates its captions ("capture réelle" ->
"enregistrement réel"). The hero pause button appears as soon as one recording exists.

Format for every clip: **1280×720, H.264 (MP4), no audio track**, a few MB at most, real screen
recordings (`adb shell screenrecord`) of canvasOS, never rendered or generated video. They loop
muted and play only while visible.

| File | Where it plays | What it should show | Length |
|---|---|---|---|
| `public/video/home.mp4` | Hero, feature 01, "after" of the comparison | The home screen: focus moving along the glass dock, tiles growing and tilting, the next row peeking below | 8–12 s |
| `public/video/aerial.mp4` | Screensaver section, feature 04, gallery | The screensaver with *Vues aériennes (Apple TV)* content: an aerial film with the clock | 10–15 s |
| `public/video/shelf.mp4` | Feature 02, gallery | Top Shelf: moving between dock apps, the screen filling with each app's content (Continue watching / TMDB) | 10–15 s |
| `public/video/cc.mp4` | Feature 03, gallery | Back opens the Control Center, focus moves through the controls, player and page 2 | 8–12 s |
| `public/video/intro.mp4` | Gallery (large cell) | The canvasOS boot animation (the canvasOS logo film, not the tvOS one) ending on the home screen | 5–8 s |
| `public/video/settings.mp4` | Gallery | Home settings: moving through the groups, opening one or two | 8–12 s |
| `public/img/before.webp` | "Before" of the comparison | A real capture of the TV's stock home screen (ideally the same box), 1280×720 WebP | still |

Until `before.webp` exists, the "before" pane is `public/img/before-illustration-{fr,en}.svg`, and
the page labels it as an illustration, not a capture.

The captures and their device are named in `src/data/site.ts` (`device`, `deviceOs`): today a
Thomson Streaming Box 240G (Google TV). If recordings come from another device, change those two
values so the captions stay true.

## Layout

```
src/data/site.ts        brand, links, release number, capture device, media slots, install commands
src/data/media.ts       build-time checks: which videos / before.webp exist in public/
src/data/lang.ts        the Copy interface both locales satisfy
src/data/fr.ts          French copy (default locale, served at /)
src/data/en.ts          English copy (/en/)
src/components/         Page (the whole one-pager), Header, Hero (+ proof strip + release cards),
                        Aerial, Features, Compare, Gallery (+ lightbox), Install, Compatibility,
                        Faq, Footer (+ final call to action), Clip (one media slot)
src/pages/              index.astro (fr), en/index.astro, agent.md.ts, sitemap.xml.ts
src/scripts/main.ts     menu, comparison slider, copy buttons, lightbox, clip playback, reveal
public/install.sh       the installer (see below)
scripts/                build-assets.py, check-csp.mjs, preview.py
```

## Content rules (do not regress)

- **Every claim follows `../AGENTS.md`.** When a feature changes, change the copy in both
  `fr.ts` and `en.ts`; `astro check` fails if one locale misses a field.
- **Captures are real.** `public/img/*-1280.webp` / `*-640.webp` come from
  `../docs/screenshots/*.png` through `scripts/build-assets.py`. Never substitute a generated image.
  The settings capture shows the optional "tvOS" logo; the gallery's fine print says so.
- **The release cards** (`release` in both copies) describe `release.version` in `site.ts`; update
  all three together when a version ships.
- **Nothing specific to one market's firmware.** Brands named are stock Android TV / Google TV,
  Sony BRAVIA and Android TV models of Hisense, Philips, Thomson… The one exception is the safety
  note (compatibility card, both languages, and `install.sh`): never disable the Xiaomi / Redmi
  stock launcher (`com.mitv.tvhome`), the TV would boot to a black screen. The home guard is
  described generically, for TVs whose system keeps the Home key for its own launcher.
- **Downloads** point at GitHub `releases/latest/download/canvasOS.apk` only; the second download
  card links the releases page.
- **The agent message** (`install.agent.prompt`) never asks for the TV's IP: the agent finds the TV
  on the network and guides the user step by step (AGENTS.md).
- **Client-side strings** come from `data-*` attributes written by the layout, never from literals
  in `main.ts`, so `/en/` cannot fall back to French.

## install.sh

`curl -fsSL https://canvasos.pages.dev/install.sh | bash` follows AGENTS.md. Without `TV=`, it
finds the Android TVs on the local network and asks which one (read from `/dev/tty`; with no
terminal it lists them and asks for `TV=`): adb's own network devices, an mDNS browse
(`_androidtvremote2._tcp` marks an Android TV, `_googlecast._tcp` adds its name and model; `dns-sd`
on macOS, `avahi-browse` on Linux) and a parallel probe of port 5555 on the local /24 (`nc`, or
bash `/dev/tcp`), in about 4 s. Discovery never runs `adb connect`; a TV seen by mDNS with port
5555 closed gets the "turn on ADB debugging" steps instead. Then it checks adb, connects to the
chosen TV, requires API 28+, saves the original home activity, screensaver, accessibility
services, notification listeners and device info to `~/.canvasos/<ip>_5555/` before changing
anything (never overwritten by a later run), downloads the latest Release, installs with
`adb install -r`, sets the home activity, grants the step-4 permissions, turns on the home guard
only when Home still opens the stock launcher, and checks `ResumedActivity`.

- `TV=192.168.x.x` (or an adb serial) skips discovery; `APK=/path/file.apk`, `DRY_RUN=1`.
- `bash -s -- restore`: guard off, original screensaver back, revival listener off,
  original home activity back. `restore --uninstall` also uninstalls canvasOS.
- It stops on `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and never uninstalls on its own. It never sends
  power, sleep or reboot keys, never uninstalls a system app and never disables a launcher: `run`
  refuses any disable / hide / foreign uninstall, and names `com.mitv.tvhome` explicitly.
- Before publishing a change: `bash -n public/install.sh && shellcheck public/install.sh`, a
  `DRY_RUN=1` run, then a real run and `restore` on the `tv34` emulator (`TV=emulator-5554`).
  Discovery: `DRY_RUN=1 bash public/install.sh </dev/null` (no terminal: it lists the TVs and
  stops), then the same in a terminal, answering `n` so nothing connects.
