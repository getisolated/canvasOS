/* canvasOS website — progressive enhancement only, no framework.
   Every user-visible string comes from data-* attributes written by the layout,
   so the same script speaks French on / and English on /en/. */
(() => {
  "use strict";

  const doc = document;
  const body = doc.body;
  const isEnglish = (doc.documentElement.lang || "fr").toLowerCase().startsWith("en");
  const t = {
    copy: body?.dataset.copyLabel || (isEnglish ? "Copy" : "Copier"),
    copied: body?.dataset.copied || (isEnglish ? "Copied" : "Copié"),
    copyFailed: body?.dataset.copyFailed || (isEnglish ? "Copy failed" : "Échec de la copie"),
    compareTemplate:
      body?.dataset.compareValue || (isEnglish ? "Showing {p}% of the before image" : "{p} % de l'image « avant » affichés"),
  };

  /* ---------- header ---------- */
  const header = doc.querySelector(".site-header");
  const toggle = doc.querySelector<HTMLButtonElement>(".nav-toggle");
  const navLinks = doc.querySelector(".nav-links");
  const onScroll = () => header?.classList.toggle("is-scrolled", window.scrollY > 8);
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  if (toggle && header) {
    const setMenu = (open: boolean) => {
      header.classList.toggle("is-open", open);
      toggle.setAttribute("aria-expanded", String(open));
      const label = open ? toggle.dataset.labelClose : toggle.dataset.labelOpen;
      if (label) toggle.setAttribute("aria-label", label);
      doc.body.classList.toggle("menu-open", open);
    };
    toggle.addEventListener("click", () => setMenu(!header.classList.contains("is-open")));
    navLinks?.addEventListener("click", (e) => {
      if ((e.target as HTMLElement).closest("a")) setMenu(false);
    });
    doc.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && header.classList.contains("is-open")) {
        setMenu(false);
        toggle.focus();
      }
    });
  }

  /* ---------- before / after slider ---------- */
  doc.querySelectorAll<HTMLElement>("[data-compare]").forEach((compare) => {
    const handle = compare.querySelector<HTMLElement>(".compare-handle");
    if (!handle) return;
    const template = handle.dataset.valuetemplate || t.compareTemplate;
    let pos = 50;
    let dragging = false;

    const render = () => {
      compare.style.setProperty("--pos", pos + "%");
      handle.setAttribute("aria-valuenow", String(Math.round(pos)));
      handle.setAttribute("aria-valuetext", template.replace("{p}", String(Math.round(pos))));
    };
    const setFromX = (clientX: number) => {
      const rect = compare.getBoundingClientRect();
      pos = Math.min(100, Math.max(0, ((clientX - rect.left) / rect.width) * 100));
      render();
    };

    compare.addEventListener("pointerdown", (e) => {
      if (e.button !== 0) return;
      dragging = true;
      compare.setPointerCapture?.(e.pointerId);
      setFromX(e.clientX);
      if (e.target !== handle) handle.focus({ preventScroll: true });
    });
    compare.addEventListener("pointermove", (e) => {
      if (!dragging) return;
      e.preventDefault();
      setFromX(e.clientX);
    });
    const stop = (e: PointerEvent) => {
      if (!dragging) return;
      dragging = false;
      compare.releasePointerCapture?.(e.pointerId);
    };
    compare.addEventListener("pointerup", stop);
    compare.addEventListener("pointercancel", stop);
    compare.addEventListener("lostpointercapture", () => { dragging = false; });

    handle.addEventListener("keydown", (e) => {
      const step = e.shiftKey ? 10 : 2;
      let next = pos;
      switch (e.key) {
        case "ArrowLeft":
        case "ArrowDown": next = pos - step; break;
        case "ArrowRight":
        case "ArrowUp": next = pos + step; break;
        case "Home": next = 0; break;
        case "End": next = 100; break;
        case "PageDown": next = pos - 20; break;
        case "PageUp": next = pos + 20; break;
        default: return;
      }
      e.preventDefault();
      pos = Math.min(100, Math.max(0, next));
      render();
    });
    compare.addEventListener("dblclick", () => { pos = 50; render(); });
    render();
  });

  /* ---------- copy buttons: label span, icon untouched ---------- */
  const fallbackCopy = (text: string) => {
    const area = doc.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    doc.body.appendChild(area);
    area.select();
    let ok = false;
    try {
      ok = doc.execCommand("copy");
    } catch {
      ok = false;
    }
    area.remove();
    return ok;
  };

  doc.querySelectorAll<HTMLButtonElement>("[data-copy]").forEach((btn) => {
    const label = btn.querySelector<HTMLElement>(".label");
    if (label && !label.textContent) label.textContent = t.copy;
    const resolveText = () => {
      const sel = btn.dataset.copy || "";
      let node: HTMLElement | null = sel ? doc.querySelector<HTMLElement>(sel) : null;
      if (!node) node = btn.parentElement?.querySelector<HTMLElement>("pre code") ?? null;
      return node?.textContent?.trim() ?? "";
    };
    let reset: number | undefined;
    const flash = (ok: boolean) => {
      if (label) {
        label.textContent = ok ? t.copied : t.copyFailed;
        window.clearTimeout(reset);
        reset = window.setTimeout(() => { label.textContent = t.copy; }, 1800);
      }
      btn.classList.toggle("is-done", ok);
      btn.setAttribute("data-state", ok ? "copied" : "failed");
    };
    btn.addEventListener("click", async () => {
      const text = resolveText();
      if (!text) {
        flash(false);
        return;
      }
      let ok = false;
      try {
        await navigator.clipboard.writeText(text);
        ok = true;
      } catch {
        ok = fallbackCopy(text);
      }
      flash(ok);
    });
  });

  /* ---------- a recording that fails to load falls back to its poster ---------- */
  const toStill = (video: HTMLVideoElement) => {
    const poster = video.getAttribute("poster");
    if (!poster || !video.isConnected) return;
    const img = doc.createElement("img");
    img.src = poster;
    img.width = 1280;
    img.height = 720;
    img.alt = video.getAttribute("aria-label") || "";
    video.replaceWith(img);
  };

  /* ---------- gallery lightbox ---------- */
  const lightbox = doc.querySelector<HTMLDialogElement>("[data-lightbox]");
  const triggers = Array.from(doc.querySelectorAll<HTMLButtonElement>("[data-lightbox-open]"));
  if (lightbox && triggers.length && typeof lightbox.showModal === "function") {
    const img = lightbox.querySelector<HTMLImageElement>("img");
    const video = lightbox.querySelector<HTMLVideoElement>("video");
    const caption = lightbox.querySelector<HTMLElement>("[data-lightbox-caption]");
    const counter = lightbox.querySelector<HTMLElement>("[data-lightbox-counter]");
    let index = 0;
    const data = triggers.map((el) => ({
      src: el.dataset.full || "",
      poster: el.dataset.poster || "",
      alt: el.dataset.alt || "",
      caption: el.dataset.caption || "",
      video: el.dataset.video !== undefined,
    }));

    const showStill = (src: string, alt: string) => {
      if (!img || !video) return;
      video.pause();
      video.removeAttribute("src");
      video.removeAttribute("poster");
      video.hidden = true;
      img.hidden = false;
      img.src = src;
      img.alt = alt;
    };

    const show = (i: number) => {
      index = (i + data.length) % data.length;
      const item = data[index];
      if (!img || !video) return;
      if (item.video) {
        img.hidden = true;
        img.removeAttribute("src");
        video.hidden = false;
        video.muted = true;
        video.poster = item.poster;
        video.src = item.src;
        video.setAttribute("aria-label", item.alt);
        video.onerror = () => showStill(item.poster, item.alt);
        const play = () => { if (!video.hidden) video.play().catch(() => {}); };
        video.addEventListener("canplay", play, { once: true });
        video.load();
        requestAnimationFrame(play);
      } else {
        video.onerror = null;
        showStill(item.src, item.alt);
      }
      if (caption) caption.textContent = item.caption;
      if (counter) counter.textContent = `${index + 1} / ${data.length}`;
    };

    triggers.forEach((el, i) => el.addEventListener("click", () => {
      lightbox.showModal();
      show(i);
    }));
    doc.querySelectorAll<HTMLElement>(".gallery figure").forEach((fig) => {
      fig.addEventListener("click", (e) => {
        if (!(e.target as HTMLElement).closest("[data-lightbox-open]")) {
          fig.querySelector<HTMLButtonElement>("[data-lightbox-open]")?.click();
        }
      });
    });
    lightbox.querySelector("[data-prev]")?.addEventListener("click", () => show(index - 1));
    lightbox.querySelector("[data-next]")?.addEventListener("click", () => show(index + 1));
    lightbox.querySelector("[data-close]")?.addEventListener("click", () => lightbox.close());
    lightbox.addEventListener("close", () => video?.pause());
    lightbox.addEventListener("click", (e) => { if (e.target === lightbox) lightbox.close(); });
    lightbox.addEventListener("keydown", (e) => {
      if (e.key === "ArrowRight") show(index + 1);
      if (e.key === "ArrowLeft") show(index - 1);
    });
  }

  /* ---------- recordings: muted, play only while on screen; the hero button pauses them all ---------- */
  const clipEls = Array.from(doc.querySelectorAll<HTMLVideoElement>("video[data-clip]"));
  clipEls.forEach((v) => v.addEventListener("error", () => toStill(v), { once: true }));
  const clipToggle = doc.querySelector<HTMLButtonElement>("[data-clip-toggle]");
  let clipsPaused = false;
  const onScreen = new Set<HTMLVideoElement>();
  if (clipEls.length && "IntersectionObserver" in window) {
    const watch = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          const v = entry.target as HTMLVideoElement;
          if (entry.intersectionRatio >= 0.2) {
            onScreen.add(v);
            if (!clipsPaused) v.play().catch(() => {});
          } else {
            onScreen.delete(v);
            v.pause();
          }
        });
      },
      { threshold: [0, 0.2] },
    );
    clipEls.forEach((v) => watch.observe(v));
  }
  clipToggle?.addEventListener("click", () => {
    clipsPaused = !clipsPaused;
    clipToggle.setAttribute("aria-pressed", String(clipsPaused));
    clipToggle.setAttribute("aria-label", (clipsPaused ? clipToggle.dataset.labelPlay : clipToggle.dataset.labelPause) || "");
    clipEls.forEach((v) => (clipsPaused ? v.pause() : onScreen.has(v) && v.play().catch(() => {})));
  });

  /* ---------- reveal on scroll ---------- */
  const items = Array.from(doc.querySelectorAll<HTMLElement>(".reveal"));
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!items.length || reduce || !("IntersectionObserver" in window)) {
    items.forEach((el) => el.classList.add("is-visible"));
  } else {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-visible");
          io.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 },
    );
    items.forEach((el) => io.observe(el));
  }
})();
