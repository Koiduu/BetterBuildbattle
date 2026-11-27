const reduced = matchMedia("(prefers-reduced-motion: reduce)");

export function stageAtViewport() {
  const stage = document.getElementById("app");
  if (stage) stage.style.setProperty("--stage-origin", `${innerHeight / 2 - stage.getBoundingClientRect().top}px`);
}

export function elasticPage() {
  const nativeTouchBounce = /iP(?:ad|hone|od)/.test(navigator.userAgent)
    || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (nativeTouchBounce) return;

  const root = document.documentElement;
  const scroller = document.scrollingElement;
  let pull = 0;
  let direction = 0;
  let frame = 0;
  let wheelEnd = 0;
  let touch = null;

  function paint() {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      root.style.setProperty("--edge-shift", `0 ${direction * 64 * (1 - Math.exp(-pull / 150))}px`);
    });
  }

  function release() {
    clearTimeout(wheelEnd);
    cancelAnimationFrame(frame);
    frame = 0;
    pull = direction = 0;
    root.classList.remove("edge-drag");
    root.style.removeProperty("--edge-shift");
  }

  function canPull(delta, target) {
    if (reduced.matches || document.querySelector("dialog[open], .launch-screen:not([hidden])")) return false;
    const max = scroller.scrollHeight - scroller.clientHeight;
    const top = scroller.scrollTop;
    if (max <= 1 || (delta < 0 ? top > 1 : top < max - 1)) return false;
    for (let el = target; el instanceof Element && el !== document.body; el = el.parentElement) {
      if (el.matches("select, input, textarea, [contenteditable=true]")) return false;
      const height = el.scrollHeight - el.clientHeight;
      if (height <= 1) continue;
      const style = getComputedStyle(el);
      if (!/^(auto|scroll|overlay)$/.test(style.overflowY)) continue;
      if (style.overscrollBehaviorY !== "auto" || (delta < 0 ? el.scrollTop > 1 : el.scrollTop < height - 1)) return false;
    }
    return true;
  }

  function stretch(delta, edge = -Math.sign(delta)) {
    if (direction !== edge) pull = 0;
    direction = edge;
    pull = Math.min(360, Math.max(0, pull - delta * edge));
    if (pull === 0) return release();
    root.classList.add("edge-drag");
    paint();
  }

  window.addEventListener("wheel", (e) => {
    if (!e.cancelable || e.defaultPrevented || e.ctrlKey || e.shiftKey) return;
    if (Math.abs(e.deltaY) <= Math.abs(e.deltaX) || !canPull(e.deltaY, e.target)) return release();
    e.preventDefault();
    stretch(e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? innerHeight : 1));
    clearTimeout(wheelEnd);
    wheelEnd = setTimeout(release, 85);
  }, { passive: false });

  window.addEventListener("touchstart", (e) => {
    const point = e.touches[0];
    if (e.touches.length !== 1 || reduced.matches) {
      touch = null;
      return release();
    }
    touch = { id: point.identifier, x: point.clientX, y: point.clientY, lastY: point.clientY, target: e.target, edge: 0 };
  }, { passive: true });

  window.addEventListener("touchmove", (e) => {
    if (!touch || e.touches.length !== 1 || !e.cancelable || e.defaultPrevented) return;
    const point = e.touches[0];
    if (point.identifier !== touch.id) return;
    let delta = touch.lastY - point.clientY;
    touch.lastY = point.clientY;
    if (!touch.edge) {
      const dy = touch.y - point.clientY;
      if (Math.abs(dy) < 6) return;
      if (Math.abs(dy) <= Math.abs(touch.x - point.clientX)) { touch = null; return; }
      if (!canPull(dy, touch.target)) return;
      touch.edge = -Math.sign(dy);
      delta = dy;
    }
    e.preventDefault();
    stretch(delta, touch.edge);
  }, { passive: false });

  for (const event of ["touchend", "touchcancel"]) {
    window.addEventListener(event, () => { touch = null; release(); }, { passive: true });
  }
  window.addEventListener("scroll", () => { if (direction) release(); }, { passive: true });
  window.addEventListener("popstate", release);
  window.addEventListener("blur", release);
  const setPreference = () => {
    release();
    root.classList.toggle("elastic-page", !reduced.matches);
  };
  reduced.addEventListener("change", setPreference);
  setPreference();
}
