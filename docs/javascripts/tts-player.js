/**
 * Playbook IA — Text-to-Speech Player
 * Compatible con MkDocs Material.
 *
 * Estrategia: forzar reload real en cada navegación interna
 * para garantizar que el player se inicialice correctamente.
 * Incluye keep-alive para evitar que Chromium corte el audio.
 */
(function () {
  "use strict";

  var synth = window.speechSynthesis;
  if (!synth) return;

  /* ── Config ── */
  var LANG = "es-AR";
  var RATES = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0];
  var KEEP_ALIVE_MS = 5000;

  /* ── State ── */
  var currentRate = 1.0;
  var isPlaying = false;
  var isPaused = false;
  var keepAliveTimer = null;

  var icons = {
    speaker: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0014 8.5v7a4.49 4.49 0 002.5-3.5zM14 3.23v2.06a6.51 6.51 0 010 13.42v2.06A8.5 8.5 0 0014 3.23z"/></svg>',
    pause:   '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>',
    play:    '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
    stop:    '<svg viewBox="0 0 24 24"><path d="M6 6h12v12H6z"/></svg>',
    restart: '<svg viewBox="0 0 24 24"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/></svg>'
  };

  /* ── Styles ── */
  var style = document.createElement("style");
  style.textContent =
    ".tts-player{position:fixed;bottom:24px;right:24px;z-index:9999;display:flex;align-items:center;font-family:var(--md-text-font-family,sans-serif)}" +
    ".tts-fab{width:48px;height:48px;border-radius:50%;border:none;background:var(--md-primary-fg-color,#673ab7);color:#fff;cursor:pointer;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 12px rgba(0,0,0,.25);transition:transform .2s,box-shadow .2s,background .2s;flex-shrink:0;position:relative}" +
    ".tts-fab:hover{transform:scale(1.08);box-shadow:0 6px 20px rgba(0,0,0,.3)}" +
    ".tts-fab:active{transform:scale(.95)}" +
    ".tts-fab svg{width:22px;height:22px;fill:currentColor}" +
    ".tts-controls{display:flex;align-items:center;gap:4px;background:var(--md-default-bg-color,#fff);border:1px solid var(--md-default-fg-color--lightest,#e0e0e0);border-radius:28px;padding:4px 8px 4px 4px;margin-right:8px;box-shadow:0 4px 16px rgba(0,0,0,.12);opacity:0;transform:translateX(20px);pointer-events:none;transition:opacity .3s,transform .3s}" +
    ".tts-controls.visible{opacity:1;transform:translateX(0);pointer-events:auto}" +
    ".tts-btn{width:36px;height:36px;border-radius:50%;border:none;background:0 0;color:var(--md-default-fg-color,#333);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:background .15s}" +
    ".tts-btn:hover{background:var(--md-default-fg-color--lightest,#f0f0f0)}" +
    ".tts-btn svg{width:18px;height:18px;fill:currentColor}" +
    ".tts-rate{font-size:.75rem;font-weight:600;color:var(--md-default-fg-color--light,#666);min-width:38px;text-align:center;cursor:pointer;padding:4px 2px;border-radius:4px;border:none;background:0 0;transition:background .15s;user-select:none;font-family:inherit}" +
    ".tts-rate:hover{background:var(--md-default-fg-color--lightest,#f0f0f0)}" +
    ".tts-fab.playing{background:var(--md-accent-fg-color,#ffc107);color:#333;animation:tts-pulse 2s ease-in-out infinite}" +
    "@keyframes tts-pulse{0%,100%{box-shadow:0 4px 12px rgba(0,0,0,.25)}50%{box-shadow:0 4px 24px rgba(255,193,7,.5)}}" +
    ".tts-fab[data-tooltip]::after{content:attr(data-tooltip);position:absolute;bottom:56px;right:0;background:var(--md-default-fg-color,#333);color:var(--md-default-bg-color,#fff);font-size:.72rem;padding:4px 10px;border-radius:6px;white-space:nowrap;opacity:0;pointer-events:none;transition:opacity .2s}" +
    ".tts-fab:hover[data-tooltip]::after{opacity:1}";
  document.head.appendChild(style);

  /* ── Force real navigation (bypass instant loading) ── */
  function forceRealNavigation() {
    document.addEventListener("click", function (e) {
      /* Buscar el <a> más cercano */
      var link = e.target.closest("a[href]");
      if (!link) return;

      var href = link.getAttribute("href");
      if (!href) return;

      /* Ignorar links externos, anchors puros, y javascript: */
      if (href.startsWith("http") || href.startsWith("#") || href.startsWith("javascript")) return;

      /* Es un link interno del sitio → forzar reload real */
      e.preventDefault();
      e.stopPropagation();

      /* Parar audio si estaba sonando */
      if (isPlaying) stopSpeech();

      /* Navegar con reload completo */
      var base = link.href || (window.location.origin + "/" + href);
      window.location.href = base;
    }, true); /* useCapture: true para interceptar ANTES que Material */
  }

  /* ── Text extraction ── */
  function getPageText() {
    var el = document.querySelector("article.md-content__inner") ||
             document.querySelector(".md-content__inner") ||
             document.querySelector("main");
    if (!el) return "";
    var clone = el.cloneNode(true);
    var junk = clone.querySelectorAll(
      "pre,code,.headerlink,.md-source,nav,.tts-player,script,style,.admonition-title,.tabbed-labels,.md-tags"
    );
    for (var i = 0; i < junk.length; i++) junk[i].remove();
    return (clone.textContent || "").replace(/\s+/g, " ").trim();
  }

  /* ── UI helpers ── */
  function showControls(v) {
    var c = document.getElementById("tts-controls");
    if (c) { if (v) c.classList.add("visible"); else c.classList.remove("visible"); }
  }

  function updateFab() {
    var f = document.getElementById("tts-fab");
    if (!f) return;
    if (isPlaying) { f.classList.add("playing"); f.dataset.tooltip = "Detener"; }
    else { f.classList.remove("playing"); f.dataset.tooltip = "Escuchar página"; }
  }

  function updatePauseBtn() {
    var b = document.getElementById("tts-pause");
    if (!b) return;
    b.innerHTML = isPaused ? icons.play : icons.pause;
    b.title = isPaused ? "Reanudar" : "Pausar";
  }

  /* ── Keep-alive (anti Chromium kill) ── */
  function startKeepAlive() {
    stopKeepAlive();
    keepAliveTimer = setInterval(function () {
      if (synth.speaking && !synth.paused) {
        synth.pause();
        synth.resume();
      }
    }, KEEP_ALIVE_MS);
  }

  function stopKeepAlive() {
    if (keepAliveTimer) { clearInterval(keepAliveTimer); keepAliveTimer = null; }
  }

  /* ── Speech ── */
  function startSpeech() {
    var text = getPageText();
    if (!text) return;
    synth.cancel();
    stopKeepAlive();

    var u = new SpeechSynthesisUtterance(text);
    u.lang = LANG;
    u.rate = currentRate;

    var voices = synth.getVoices();
    for (var i = 0; i < voices.length; i++) {
      if (voices[i].lang.indexOf("es") === 0) { u.voice = voices[i]; break; }
    }

    u.onstart = function () {
      isPlaying = true; isPaused = false;
      updateFab(); showControls(true); startKeepAlive();
    };
    u.onend = function () {
      isPlaying = false; isPaused = false;
      updateFab(); showControls(false); stopKeepAlive();
    };
    u.onerror = function () {
      isPlaying = false; isPaused = false;
      updateFab(); showControls(false); stopKeepAlive();
    };

    synth.speak(u);
  }

  function stopSpeech() {
    synth.cancel();
    isPlaying = false; isPaused = false;
    updateFab(); showControls(false); stopKeepAlive();
  }

  function togglePlay() { if (isPlaying) stopSpeech(); else startSpeech(); }

  function togglePause() {
    if (!isPlaying) return;
    if (isPaused) { synth.resume(); isPaused = false; startKeepAlive(); }
    else { synth.pause(); isPaused = true; stopKeepAlive(); }
    updatePauseBtn();
  }

  function restartSpeech() { stopSpeech(); setTimeout(startSpeech, 150); }

  function cycleRate() {
    var idx = RATES.indexOf(currentRate);
    currentRate = RATES[(idx + 1) % RATES.length];
    var el = document.getElementById("tts-rate");
    if (el) el.textContent = currentRate + "x";
    if (isPlaying) restartSpeech();
  }

  /* ── Build player ── */
  function buildPlayer() {
    var d = document.createElement("div");
    d.className = "tts-player";
    d.id = "tts-player";
    d.innerHTML =
      '<div class="tts-controls" id="tts-controls">' +
        '<button class="tts-btn" id="tts-restart" title="Reiniciar">' + icons.restart + '</button>' +
        '<button class="tts-btn" id="tts-pause" title="Pausar / Reanudar">' + icons.pause + '</button>' +
        '<button class="tts-btn" id="tts-stop" title="Detener">' + icons.stop + '</button>' +
        '<button class="tts-rate" id="tts-rate" title="Velocidad">' + currentRate + 'x</button>' +
      '</div>' +
      '<button class="tts-fab" id="tts-fab" data-tooltip="Escuchar página">' +
        icons.speaker +
      '</button>';

    document.body.appendChild(d);

    document.getElementById("tts-fab").addEventListener("click", togglePlay);
    document.getElementById("tts-pause").addEventListener("click", togglePause);
    document.getElementById("tts-stop").addEventListener("click", stopSpeech);
    document.getElementById("tts-restart").addEventListener("click", restartSpeech);
    document.getElementById("tts-rate").addEventListener("click", cycleRate);
  }

  /* ── Init ── */
  buildPlayer();
  forceRealNavigation();

  window.addEventListener("beforeunload", function () {
    stopKeepAlive();
    synth.cancel();
  });
})();
