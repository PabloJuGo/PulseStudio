/* Pulse Studio Desktop · app.js
   Capa de PRESENTACIÓN. Toda la lógica de negocio (noticias, extracción de artículos, guion con Groq, fotos de Pexels,
   fondos de Hugging Face, transcripción y guardado de archivos) vive en el backend Java y se consume vía REST (/api/*).
   Aquí solo queda: interfaz, dibujo en canvas, recorte con MediaPipe y exportación de vídeo con WebCodecs.
     1. Cliente REST  ·  2. Motor multimedia  ·  3. Aplicación (asistente de 5 pasos) */
'use strict';
/* ═════════════════════ 1. Cliente de la API REST local (backend Java) ═════════════════════
   El lanzador abre la ventana con #t=TOKEN. El token se guarda en sessionStorage, se retira de la URL y
   acompaña a cada petición (cabecera X-Pulse-Token). Las claves de API viven SOLO en el backend. */
(() => {
  'use strict';
  const fromHash = new URLSearchParams(location.hash.slice(1)).get('t');
  if (fromHash) {
    try { sessionStorage.setItem('pulseToken', fromHash); } catch {}
    history.replaceState(null, '', location.pathname);
  }
  let token = fromHash || '';
  if (!token) { try { token = sessionStorage.getItem('pulseToken') || ''; } catch {} }

  class ApiError extends Error {
    constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status; }
  }

  async function call(method, path, {json, body, headers = {}, timeout = 120000} = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const init = {method, headers: {'X-Pulse-Token': token, ...headers}, signal: controller.signal, credentials: 'same-origin', cache: 'no-store'};
      if (json !== undefined) { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(json); }
      else if (body !== undefined) init.body = body;
      const res = await fetch(path, init);
      if (!res.ok) {
        let message = `Error ${res.status}`;
        try { const data = await res.json(); if (data && data.error) message = data.error; } catch {}
        throw new ApiError(message, res.status);
      }
      const type = res.headers.get('content-type') || '';
      return type.includes('application/json') ? res.json() : res.blob();
    } catch (err) {
      if (err.name === 'AbortError') throw new ApiError('Pulse Studio ha tardado demasiado en responder.', 0);
      if (err instanceof ApiError) throw err;
      throw new ApiError('No se puede contactar con el servidor local de Pulse Studio. ¿Se ha cerrado la aplicación?', 0);
    } finally { clearTimeout(timer); }
  }

  const api = Object.freeze({
    get: (path, options) => call('GET', path, options),
    post: (path, json, options = {}) => call('POST', path, {...options, json}),
    put: (path, json, options = {}) => call('PUT', path, {...options, json}),
    del: (path, options) => call('DELETE', path, options),
    upload: (path, blob, options = {}) => call('POST', path, {...options, body: blob, headers: {'Content-Type': blob.type || 'application/octet-stream'}})
  });

  /* Guardado: el backend escribe en la carpeta de salida (en streaming: el vídeo no pasa entero por memoria). */
  async function saveBlob(blob, name, options = {}) {
    if (!(blob instanceof Blob) || blob.size < 1) throw new Error('El archivo está vacío.');
    const query = new URLSearchParams({name, folder: options.folder || ''});
    return api.upload('/api/files?' + query, blob, {timeout: 900000});
  }
  async function saveImages(images, folder) {
    let last = null;
    for (const image of images) last = await saveBlob(image.blob, image.name, {folder});
    return {saved: true, count: images.length, path: last ? last.folder : '', folder: last ? last.folder : ''};
  }

  /* Latido: si la ventana se cierra, el backend deja de recibirlo y se apaga solo. */
  const ping = () => api.get('/api/ping', {timeout: 8000}).catch(() => {});
  ping();
  setInterval(ping, 10000);

  window.PulseApi = api;
  window.PulsePlatform = Object.freeze({native: false, desktop: true, saveBlob, saveImages});
})();

/* ═════════════════════ 2. Motor multimedia local (presentación) ═════════════════════
   MediaPipe Tasks Vision (Selfie Segmenter) se sirve desde el propio backend (vendor/mediapipe);
   ninguna foto ni fotograma sale del equipo. Sin estado de la app: solo funciones de dibujo. */
(() => {
  'use strict';
  const VENDOR = new URL('vendor/mediapipe/', location.href).href;
  let visionModule = null, segmenter = null, segmenterMode = null, busy = false, lastTimestamp = 0;
  let chain = Promise.resolve();
  const serial = task => { const run = chain.then(task, task); chain = run.catch(() => {}); return run; };
  /* Escalera de configuraciones. En algunos equipos (drivers antiguos, escritorio remoto, máquinas virtuales) la GPU no admite las texturas
     float que MediaPipe usa para la máscara de confianza: el modelo «funciona» pero devuelve una máscara vacía
     (google-ai-edge/mediapipe#6296). Si una configuración no detecta a nadie, se prueba la siguiente; la máscara
     binaria (categoryMask, 8 bits) no necesita texturas float. La que funciona se conserva toda la sesión. */
  const LADDER = [
    {delegate: 'GPU', mask: 'confidence', label: 'GPU'},
    {delegate: 'CPU', mask: 'confidence', label: 'CPU'},
    {delegate: 'GPU', mask: 'category', label: 'GPU · máscara binaria'},
    {delegate: 'CPU', mask: 'category', label: 'CPU · máscara binaria'}
  ];
  let rung = 0, scratch = null;
  const config = () => LADDER[Math.min(rung, LADDER.length - 1)];

  function engineError(error) {
    const text = String(error && error.message || error || '');
    if (/wasm|WebAssembly|CompileError|unsafe-eval/i.test(text) && /refused|disallowed|CSP|Content Security/i.test(text))
      return new Error('Este navegador no permite ejecutar el motor local de recorte. Actualiza Microsoft Edge o Google Chrome.');
    if (/Failed to fetch|import|404|Blocked|NetworkError|Load failed|dynamically imported module/i.test(text))
      return new Error('No se encuentra el motor local de recorte (MediaPipe). Ejecuta «node tools/fetch-mediapipe.cjs» y vuelve a compilar la app.');
    return new Error('No se ha podido iniciar el recorte en el dispositivo: ' + (text || 'error desconocido') + '.');
  }
  async function loadVision() {
    if (!visionModule) visionModule = import(VENDOR + 'vision_bundle.js').catch(error => { visionModule = null; throw error; });
    return visionModule;
  }
  async function create(vision, cfg, mode) {
    const fileset = await vision.FilesetResolver.forVisionTasks(VENDOR + 'wasm');
    return vision.ImageSegmenter.createFromOptions(fileset, {
      baseOptions: {modelAssetPath: VENDOR + 'selfie_segmenter.tflite', delegate: cfg.delegate},
      runningMode: mode, outputConfidenceMasks: cfg.mask === 'confidence', outputCategoryMask: cfg.mask === 'category'
    });
  }
  /** Un único segmentador compartido; se cambia de modo IMAGE (foto) a VIDEO (editor) sin recrearlo. */
  function getSegmenter(mode) {
    return serial(async () => {
      try {
        if (!segmenter) {
          const vision = await loadVision(); let lastError = null;
          while (!segmenter && rung < LADDER.length) {
            try { segmenter = await create(vision, config(), mode); } catch (error) { lastError = error; rung++; }
          }
          if (!segmenter) { rung = 0; throw lastError || new Error('sin configuración válida'); }
          segmenterMode = mode; lastTimestamp = 0;
        } else if (segmenterMode !== mode) {
          await segmenter.setOptions({runningMode: mode}); segmenterMode = mode; lastTimestamp = 0;
        }
        return segmenter;
      } catch (error) { segmenter = null; segmenterMode = null; throw engineError(error); }
    });
  }
  function closeSegmenter() { if (segmenter) { try { segmenter.close(); } catch {} } segmenter = null; segmenterMode = null; lastTimestamp = 0; }
  function release() { return serial(async () => closeSegmenter()); }
  /** Pasa a la siguiente configuración de la escalera. Devuelve false si ya no quedan (y vuelve a empezar). */
  function escalate() {
    return serial(async () => { closeSegmenter(); rung++; if (rung >= LADDER.length) { rung = 0; return false; } return true; });
  }
  const makeCanvas = (w, h) => { const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(w)); c.height = Math.max(1, Math.round(h)); return c; };
  const freeCanvas = c => { if (c) { c.width = 0; c.height = 0; } };
  const smooth = (lo, hi, v) => { const t = Math.min(1, Math.max(0, (v - lo) / (hi - lo))); return t * t * (3 - 2 * t); };
  const binaryMask = () => config().mask === 'category';
  /** Lee la máscara de persona como Float32 [0..1] (sea de confianza o binaria) y SIEMPRE libera el resultado. */
  function withMask(result, use) {
    try {
      if (binaryMask()) {
        const mask = result && result.categoryMask;
        if (!mask) throw new Error('El modelo no ha devuelto máscara.');
        const u8 = mask.getAsUint8Array(), n = u8.length;
        if (!scratch || scratch.length !== n) scratch = new Float32Array(n);
        // Selfie Segmenter: categoría 0 = persona, 255 = fondo.
        for (let i = 0; i < n; i++) scratch[i] = u8[i] === 0 ? 1 : 0;
        return use(scratch, mask.width, mask.height);
      }
      const mask = result && result.confidenceMasks && result.confidenceMasks[result.confidenceMasks.length - 1];
      if (!mask) throw new Error('El modelo no ha devuelto máscara.');
      return use(mask.getAsFloat32Array(), mask.width, mask.height);
    } finally { if (result && typeof result.close === 'function') result.close(); }
  }
  /** Máscara → canvas alfa + contorno + % de persona. Una máscara vacía o completa se considera fallo del motor. */
  function analyse(result) {
    return withMask(result, (data, mw, mh) => {
      const out = makeCanvas(mw, mh), mctx = out.getContext('2d'), pixels = mctx.createImageData(mw, mh), px = pixels.data;
      let minX = mw, minY = mh, maxX = -1, maxY = -1, fg = 0, max = 0;
      for (let y = 0, i = 0; y < mh; y++) for (let x = 0; x < mw; x++, i++) {
        const v = data[i], a = smooth(0.32, 0.72, v);
        if (v > max) max = v;
        if (v > 0.5) fg++;
        if (a > 0.08) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; }
        const p = i * 4; px[p] = px[p + 1] = px[p + 2] = 255; px[p + 3] = Math.round(a * 255);
      }
      mctx.putImageData(pixels, 0, 0);
      const coverage = fg / (mw * mh);
      return {canvas: out, coverage, max, ok: maxX >= minX && coverage >= 0.01 && coverage <= 0.985,
        box: maxX >= minX ? {x: minX / mw, y: minY / mh, w: (maxX - minX + 1) / mw, h: (maxY - minY + 1) / mh} : null};
    });
  }
  /** Prueba la escalera sobre una imagen hasta encontrar una configuración que detecte a la persona. */
  async function detect(source, mode) {
    const tried = [];
    for (;;) {
      const seg = await getSegmenter(mode), label = config().label;
      let r;
      try {
        if (mode === 'VIDEO') { const ts = Math.max(Math.round(performance.now()), lastTimestamp + 1); lastTimestamp = ts; r = analyse(seg.segmentForVideo(source, ts)); }
        else r = analyse(seg.segment(source));
      } catch (error) { r = {ok: false, error}; }
      tried.push(`${label}: ${r.error ? 'error' : (r.coverage * 100).toFixed(1) + ' %'}`);
      if (r.ok) return {...r, label, tried};
      freeCanvas(r.canvas);
      if (!(await escalate())) return {ok: false, tried};
    }
  }

  /** Foto → persona recortada (canvas con alfa, ajustado al contorno) + silueta blanca para el borde. */
  async function cutoutPerson(source, maxSide = 1600) {
    const ratio = Math.min(1, maxSide / Math.max(source.width, source.height));
    const w = Math.round(source.width * ratio), h = Math.round(source.height * ratio);
    const work = makeCanvas(w, h), ctx = work.getContext('2d');
    ctx.drawImage(source, 0, 0, w, h);
    const r = await detect(work, 'IMAGE');
    if (!r.ok) { freeCanvas(work); throw new Error(`No se ha detectado a ninguna persona en la foto. Prueba con un retrato más cercano y bien iluminado. (Diagnóstico: ${r.tried.join(' · ')})`); }
    const box = r.box;
    ctx.globalCompositeOperation = 'destination-in';
    // La máscara binaria tiene bordes duros: se suavizan al escalarla.
    if (binaryMask()) ctx.filter = `blur(${Math.max(1, Math.round(Math.max(w, h) / 450))}px)`;
    ctx.drawImage(r.canvas, 0, 0, w, h); ctx.filter = 'none'; freeCanvas(r.canvas);
    const pad = 0.015, bx = Math.max(0, Math.floor((box.x - pad) * w)), by = Math.max(0, Math.floor((box.y - pad) * h));
    const bw = Math.min(w - bx, Math.ceil((box.w + pad * 2) * w)), bh = Math.min(h - by, Math.ceil((box.h + pad * 2) * h));
    const person = makeCanvas(bw, bh); person.getContext('2d').drawImage(work, bx, by, bw, bh, 0, 0, bw, bh); freeCanvas(work);
    const silhouette = makeCanvas(bw, bh), sctx = silhouette.getContext('2d');
    sctx.drawImage(person, 0, 0); sctx.globalCompositeOperation = 'source-in'; sctx.fillStyle = '#ffffff'; sctx.fillRect(0, 0, bw, bh);
    return {canvas: person, silhouette, coverage: r.coverage, engine: r.label};
  }
  /** Editor: comprueba con un fotograma real que la configuración actual detecta a la persona (y escala si no). */
  async function calibrateVideo(video) {
    const k = 320 / Math.max(video.videoWidth, video.videoHeight), c = makeCanvas(video.videoWidth * k, video.videoHeight * k);
    c.getContext('2d').drawImage(video, 0, 0, c.width, c.height);
    try { const r = await detect(c, 'VIDEO'); freeCanvas(r.canvas); if (r.ok) await getSegmenter('VIDEO'); return {ok: r.ok, engine: r.label, tried: r.tried}; }
    finally { freeCanvas(c); }
  }

  const coverRect = (sw, sh, W, H, zoom = 1, panX = 0, panY = 0) => {
    const s = Math.max(W / sw, H / sh) * zoom, w = sw * s, h = sh * s;
    return [(W - w) / 2 + panX * (w - W) / 2, (H - h) / 2 + panY * (h - H) / 2, w, h];
  };
  function brandBackground(ctx, W, H) {
    const g = ctx.createLinearGradient(0, 0, W, H); g.addColorStop(0, '#12314a'); g.addColorStop(0.55, '#0b1a2c'); g.addColorStop(1, '#061019');
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    const r = ctx.createRadialGradient(W * 0.8, H * 0.18, 10, W * 0.8, H * 0.18, W * 0.9); r.addColorStop(0, 'rgba(121,245,209,.30)'); r.addColorStop(1, 'rgba(121,245,209,0)');
    ctx.fillStyle = r; ctx.fillRect(0, 0, W, H);
  }
  const roundRect = (ctx, x, y, w, h, r) => {
    ctx.beginPath();
    if (typeof ctx.roundRect === 'function') { ctx.roundRect(x, y, w, h, r); return; }
    r = Math.min(r, w / 2, h / 2);
    ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  };
  const FONT = '"Roboto","Segoe UI",system-ui,-apple-system,sans-serif';
  const plain = s => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^\p{L}\p{N}]/gu, '').toUpperCase();
  function wrapWords(ctx, words, maxW) {
    const lines = []; let line = [];
    for (const word of words) {
      const test = [...line, word].join(' ');
      if (line.length && ctx.measureText(test).width > maxW) { lines.push(line); line = [word]; } else line.push(word);
    }
    if (line.length) lines.push(line);
    return lines;
  }
  function drawHeadline(ctx, W, text, highlight, top) {
    const words = String(text || '').normalize('NFC').trim().toUpperCase().split(/\s+/).filter(Boolean).slice(0, 14);
    if (!words.length) return top;
    const maxW = W * 0.88; let size = Math.round(W * 0.135), lines;
    for (; size >= Math.round(W * 0.06); size -= 4) {
      ctx.font = `900 ${size}px ${FONT}`; lines = wrapWords(ctx, words, maxW);
      if (lines.length <= 3 && lines.every(l => ctx.measureText(l.join(' ')).width <= maxW)) break;
    }
    const mark = plain(highlight), lh = size * 1.04;
    ctx.textBaseline = 'alphabetic'; ctx.lineJoin = 'round'; ctx.miterLimit = 2;
    let y = top + size;
    for (const line of lines) {
      const space = ctx.measureText(' ').width, total = ctx.measureText(line.join(' ')).width;
      let x = (W - total) / 2;
      for (const word of line) {
        const width = ctx.measureText(word).width;
        ctx.lineWidth = size * 0.17; ctx.strokeStyle = '#04101a'; ctx.strokeText(word, x, y);
        ctx.fillStyle = mark && plain(word) === mark ? '#79f5d1' : '#ffffff'; ctx.fillText(word, x, y);
        x += width + space;
      }
      y += lh;
    }
    return y;
  }
  /** Composición de la miniatura: fondo + velado + persona con borde + titular. */
  function drawThumbnail(canvas, o) {
    const ctx = canvas.getContext('2d'), W = canvas.width, H = canvas.height;
    ctx.save(); ctx.globalCompositeOperation = 'source-over'; ctx.globalAlpha = 1;
    if (o.background) ctx.drawImage(o.background, ...coverRect(o.background.width, o.background.height, W, H)); else brandBackground(ctx, W, H);
    ctx.fillStyle = `rgba(3,9,18,${Math.min(0.8, Math.max(0, o.dim || 0))})`; ctx.fillRect(0, 0, W, H);
    const top = ctx.createLinearGradient(0, 0, 0, H * 0.46); top.addColorStop(0, 'rgba(3,9,18,.78)'); top.addColorStop(1, 'rgba(3,9,18,0)');
    ctx.fillStyle = top; ctx.fillRect(0, 0, W, H * 0.46);
    const bottom = ctx.createLinearGradient(0, H * 0.62, 0, H); bottom.addColorStop(0, 'rgba(3,9,18,0)'); bottom.addColorStop(1, 'rgba(3,9,18,.6)');
    ctx.fillStyle = bottom; ctx.fillRect(0, H * 0.62, W, H * 0.38);
    if (o.person) {
      const c = o.person.canvas, dh = H * (o.scale || 0.62), dw = c.width * dh / c.height;
      const travel = Math.abs(W - dw) / 2 + W * 0.2, x = (W - dw) / 2 + (o.x || 0) * travel, y = H - dh;
      ctx.save(); ctx.shadowColor = 'rgba(121,245,209,.55)'; ctx.shadowBlur = W * 0.06; ctx.drawImage(o.person.silhouette, x, y, dw, dh); ctx.restore();
      const r = Math.max(4, Math.round(W * 0.007));
      for (let k = 0; k < 12; k++) { const a = k / 12 * Math.PI * 2; ctx.drawImage(o.person.silhouette, x + Math.cos(a) * r, y + Math.sin(a) * r, dw, dh); }
      ctx.drawImage(c, x, y, dw, dh);
    }
    // Sin etiqueta del medio: solo el titular en la parte superior.
    drawHeadline(ctx, W, o.headline, o.highlight, H * 0.055);
    ctx.restore();
  }

  /** Compositor del editor: fondo según la línea de tiempo (fundido + zoom suave) + sujeto recortado. */
  class ReelRenderer {
    constructor(canvas) {
      this.canvas = canvas; this.ctx = canvas.getContext('2d', {alpha: false});
      this.inference = null; this.maskCanvas = null; this.subject = null; this.alpha = null; this.pixels = null; this.frames = 0; this.maskReady = false;
    }
    resize(w, h) { if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; } }
    resetMask() { this.alpha = null; this.maskReady = false; }
    releaseScratch() { freeCanvas(this.inference); freeCanvas(this.maskCanvas); freeCanvas(this.subject); this.inference = this.maskCanvas = this.subject = null; this.alpha = null; this.pixels = null; this.maskReady = false; }
    drawBackgrounds(o) {
      const {ctx} = this, W = this.canvas.width, H = this.canvas.height, bgs = o.backgrounds || [], starts = o.starts || [0];
      if (!bgs.length) { brandBackground(ctx, W, H); return; }
      let i = 0; for (let k = 0; k < starts.length; k++) if (o.time >= starts[k]) i = k;
      const end = i + 1 < starts.length ? starts[i + 1] : Math.max(o.duration || 0, starts[i] + 1);
      const draw = (k, progress, alpha) => {
        const img = bgs[k]; if (!img) return;
        const zoom = 1.03 + 0.07 * Math.min(1, Math.max(0, progress)), pan = (k % 2 ? -1 : 1) * 0.35 * (progress - 0.5);
        ctx.globalAlpha = alpha; ctx.drawImage(img, ...coverRect(img.width, img.height, W, H, zoom, pan, 0)); ctx.globalAlpha = 1;
      };
      const since = o.time - starts[i], fade = 0.4;
      if (i > 0 && since < fade) draw(i - 1, 1, 1);
      draw(i, since / Math.max(0.1, end - starts[i]), i > 0 && since < fade ? Math.max(0, since / fade) : 1);
    }
    /** Escena completa: fondo + sujeto + subtítulo activo. Misma función en vista previa y exportación. */
    draw(o) {
      this.drawScene(o);
      if (o.captions && o.captions.length) drawCaption(this.ctx, this.canvas.width, this.canvas.height, activeCaption(o.captions, o.time), o.captionStyle || {});
      this.frames++;
    }
    /** segmentFrame(source, timestampMs, use) es opcional: sin él, el vídeo se muestra en recuadro. */
    drawScene(o) {
      const {ctx} = this, W = this.canvas.width, H = this.canvas.height, v = o.video;
      this.drawBackgrounds(o);
      // Fuente del sujeto: fotograma decodificado (exportación offline) o el <video> (vista previa).
      let src, vw, vh;
      if (o.frame) { src = o.frame; vw = src.width; vh = src.height; }
      else { if (!v || v.readyState < 2 || !v.videoWidth) return; src = v; vw = v.videoWidth; vh = v.videoHeight; }
      if (!vw || !vh) return;
      if (o.segmentFrame) {
        const long = 320, k = long / Math.max(vw, vh), iw = Math.max(16, Math.round(vw * k)), ih = Math.max(16, Math.round(vh * k));
        if (!this.inference) this.inference = makeCanvas(iw, ih);
        if (this.inference.width !== iw || this.inference.height !== ih) { this.inference.width = iw; this.inference.height = ih; this.alpha = null; }
        const ictx = this.inference.getContext('2d', {willReadFrequently: false}); ictx.drawImage(src, 0, 0, iw, ih);
        o.segmentFrame(this.inference, performance.now(), (data, mw, mh) => {
          if (!this.maskCanvas) this.maskCanvas = makeCanvas(mw, mh);
          if (this.maskCanvas.width !== mw || this.maskCanvas.height !== mh) { this.maskCanvas.width = mw; this.maskCanvas.height = mh; this.alpha = null; this.pixels = null; }
          const mctx = this.maskCanvas.getContext('2d');
          if (!this.pixels) { this.pixels = mctx.createImageData(mw, mh); this.pixels.data.fill(255); }
          const fresh = !this.alpha || this.alpha.length !== mw * mh; if (fresh) this.alpha = new Float32Array(mw * mh);
          const a = this.alpha, px = this.pixels.data;
          for (let i = 0; i < a.length; i++) { const t = smooth(0.3, 0.7, data[i]); a[i] = fresh ? t : a[i] * 0.35 + t * 0.65; px[i * 4 + 3] = a[i] * 255; }
          mctx.putImageData(this.pixels, 0, 0); this.maskReady = true;
        });
        // Hasta tener la primera máscara no se pinta el sujeto (evita un fotograma con el fondo original).
        if (!this.maskReady) return;
        const dh = H * (o.scale || 0.8), dw = vw * dh / vh, travel = Math.abs(W - dw) / 2 + W * 0.15;
        const x = (W - dw) / 2 + (o.x || 0) * travel, y = H - dh;
        if (!this.subject) this.subject = makeCanvas(dw, dh);
        if (this.subject.width !== Math.round(dw) || this.subject.height !== Math.round(dh)) { this.subject.width = Math.round(dw); this.subject.height = Math.round(dh); }
        const sctx = this.subject.getContext('2d'), sw = this.subject.width, sh = this.subject.height;
        sctx.globalCompositeOperation = 'copy'; sctx.drawImage(src, 0, 0, sw, sh);
        sctx.globalCompositeOperation = 'destination-in';
        if (binaryMask()) sctx.filter = `blur(${Math.max(1, Math.round(sw / 260))}px)`;
        sctx.drawImage(this.maskCanvas, 0, 0, sw, sh); sctx.filter = 'none';
        sctx.globalCompositeOperation = 'source-over';
        ctx.drawImage(this.subject, x, y, sw, sh);
      } else {
        const bw = W * 0.56 * (o.scale || 0.8) / 0.8, bh = Math.min(H * 0.62, bw * vh / vw), fw = bh * vw / vh;
        const travel = (W - fw) / 2 - W * 0.05, x = (W - fw) / 2 + (o.x || 0) * Math.max(0, travel), y = H - bh - H * 0.06, r = W * 0.035;
        ctx.save(); roundRect(ctx, x, y, fw, bh, r); ctx.clip(); ctx.drawImage(src, x, y, fw, bh); ctx.restore();
        ctx.lineWidth = Math.max(3, W * 0.006); ctx.strokeStyle = '#79f5d1'; roundRect(ctx, x, y, fw, bh, r); ctx.stroke();
      }
    }
  }
  /* ── Subtítulos automáticos ── */
  const CAPTION_FONTS = {
    bold: {weight: 900, family: '"Roboto","Segoe UI",system-ui,sans-serif'},
    condensed: {weight: 700, family: '"Roboto Condensed","Arial Narrow","sans-serif-condensed",sans-serif'},
    serif: {weight: 700, family: 'Georgia,"Noto Serif","Times New Roman",serif'},
    mono: {weight: 700, family: '"Roboto Mono","Droid Sans Mono","Courier New",monospace'},
    hand: {weight: 700, family: '"Dancing Script","Comic Sans MS",cursive'}
  };
  /** Palabras con marca de tiempo (Whisper) → subtítulos cortos estilo TikTok. */
  function buildCaptions(words, maxWords = 3) {
    const out = []; let cur = null;
    const flush = () => { if (cur && cur.words.length) out.push({start: cur.start, end: cur.end, text: cur.words.join(' ')}); cur = null; };
    for (const w of words || []) {
      const text = String(w.word || '').trim(); if (!text) continue;
      const start = Number(w.start) || 0, end = Math.max(start + 0.05, Number(w.end) || start);
      if (cur && (cur.words.length >= maxWords || start - cur.end > 0.6 || (cur.words.join(' ') + ' ' + text).length > 26)) flush();
      if (!cur) cur = {start, end, words: []};
      cur.words.push(text); cur.end = end;
      if (/[.!?…]$/.test(text)) flush();
    }
    flush();
    // Evitar parpadeos: cada subtítulo dura hasta el siguiente si el hueco es corto.
    for (let i = 0; i < out.length - 1; i++) if (out[i + 1].start - out[i].end < 0.35) out[i].end = out[i + 1].start;
    return out;
  }
  function activeCaption(list, t) {
    let lo = 0, hi = list.length - 1;
    while (lo <= hi) { const mid = (lo + hi) >> 1, c = list[mid]; if (t < c.start) hi = mid - 1; else if (t >= c.end) lo = mid + 1; else return c; }
    return null;
  }
  function drawCaption(ctx, W, H, cap, st) {
    const text = cap && String(cap.text || '').trim(); if (!text) return;
    const font = CAPTION_FONTS[st.font] || CAPTION_FONTS.bold, words = (st.upper ? text.toUpperCase() : text).split(/\s+/);
    let size = Math.round(W * (st.size || 0.075)); const maxW = W * 0.86;
    let lines;
    for (; size > 12; size -= 2) {
      ctx.font = `${font.weight} ${size}px ${font.family}`; lines = wrapWords(ctx, words, maxW);
      if (lines.length <= 2 && lines.every(l => ctx.measureText(l.join(' ')).width <= maxW)) break;
    }
    const lh = size * 1.18, total = lh * lines.length, cy = H * (st.y ?? 0.72), top = Math.min(H - total - H * 0.02, Math.max(H * 0.02, cy - total / 2));
    ctx.save(); ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.lineJoin = 'round'; ctx.miterLimit = 2;
    lines.forEach((line, i) => {
      const str = line.join(' '), y = top + lh * i + lh / 2, w = ctx.measureText(str).width;
      if (st.style === 'box') { ctx.fillStyle = 'rgba(0,0,0,.72)'; roundRect(ctx, W / 2 - w / 2 - size * 0.35, y - lh / 2, w + size * 0.7, lh, size * 0.25); ctx.fill(); }
      else if (st.style === 'shadow') { ctx.shadowColor = 'rgba(0,0,0,.9)'; ctx.shadowBlur = size * 0.35; ctx.shadowOffsetY = size * 0.06; }
      else { ctx.lineWidth = size * 0.2; ctx.strokeStyle = '#000000'; ctx.strokeText(str, W / 2, y); }
      ctx.fillStyle = st.color || '#ffffff'; ctx.fillText(str, W / 2, y);
      ctx.shadowColor = 'transparent'; ctx.shadowBlur = 0; ctx.shadowOffsetY = 0;
    });
    ctx.restore();
  }
  /** Fotograma de vídeo → máscara. Marca de tiempo estrictamente creciente, resultado liberado cada vez. */
  function segmentFrame(source, timestampMs, use) {
    if (!segmenter || segmenterMode !== 'VIDEO') return;
    const ts = Math.max(Math.round(timestampMs), lastTimestamp + 1); lastTimestamp = ts;
    withMask(segmenter.segmentForVideo(source, ts), use);
  }
  function pickRecorderMime() {
    if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    // Preferencia: MP4 H.264 (galería/TikTok, codificador por hardware) → WebM VP8 (barato en tiempo real) → VP9.
    // No se usa 'video/mp4' genérico: en algunos motores produce VP9 dentro de MP4, poco compatible.
    return ['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/mp4;codecs=avc1,mp4a.40.2', 'video/mp4;codecs=avc1.42E01E,opus',
      'video/webm;codecs=vp8,opus', 'video/webm;codecs=vp9,opus', 'video/webm'].find(t => MediaRecorder.isTypeSupported(t)) || '';
  }
  /** MediaRecorder escribe WebM «en directo» sin duración: se inserta Segment › Info › Duration (EBML).
      Si la estructura no es la esperada devuelve el Blob original sin tocarlo. */
  async function fixWebmDuration(blob, durationMs) {
    try {
      if (!(blob instanceof Blob) || !/webm/.test(blob.type) || !(durationMs > 0)) return blob;
      const head = new Uint8Array(await blob.slice(0, Math.min(blob.size, 1 << 16)).arrayBuffer());
      const id = p => { const b = head[p], len = b & 0x80 ? 1 : b & 0x40 ? 2 : b & 0x20 ? 3 : b & 0x10 ? 4 : 0; if (!len) throw 0; let v = 0; for (let i = 0; i < len; i++) v = v * 256 + head[p + i]; return {v, len}; };
      const size = p => { const b = head[p]; let len = 1; while (len <= 8 && !(b & (0x80 >> (len - 1)))) len++; if (len > 8) throw 0;
        let v = b & (0xff >> len), ones = v === (0xff >> len); for (let i = 1; i < len; i++) { v = v * 256 + head[p + i]; ones = ones && head[p + i] === 0xff; } return {v, len, unknown: ones}; };
      let p = 0, e = id(p); if (e.v !== 0x1A45DFA3) return blob; p += e.len; let z = size(p); p += z.len + z.v;
      e = id(p); if (e.v !== 0x18538067) return blob; p += e.len; z = size(p); if (!z.unknown) return blob; p += z.len;
      while (p < head.length - 12) {
        const start = p; e = id(p); p += e.len; z = size(p); if (z.unknown) return blob; p += z.len;
        if (e.v !== 0x1549A966) { p += z.v; continue; }
        const dataStart = p, dataEnd = p + z.v; if (dataEnd > head.length) return blob;
        let scale = 1e6;
        for (let q = dataStart; q < dataEnd;) { const c = id(q); q += c.len; const cz = size(q); q += cz.len;
          if (c.v === 0x2AD7B1) { scale = 0; for (let i = 0; i < cz.v; i++) scale = scale * 256 + head[q + i]; }
          if (c.v === 0x4489) return blob; q += cz.v; }
        const dur = new Uint8Array(11); dur.set([0x44, 0x89, 0x88]); new DataView(dur.buffer).setFloat64(3, durationMs * 1e6 / (scale || 1e6));
        const bodyLen = z.v + dur.length, sizeBytes = new Uint8Array(8); sizeBytes[0] = 0x01;
        for (let i = 7, v = bodyLen; i >= 1; i--, v = Math.floor(v / 256)) sizeBytes[i] = v & 0xff;
        return new Blob([head.slice(0, start), head.slice(start, start + e.len), sizeBytes, head.slice(dataStart, dataEnd), dur, blob.slice(dataEnd)], {type: blob.type});
      }
      return blob;
    } catch { return blob; }
  }
  const trimListeners = new Set();
  /** Llamado al ocultar la ventana: libera lo que no esté en uso. */
  function trimMemory(level) {
    if (busy) return false;
    trimListeners.forEach(fn => { try { fn(level); } catch {} });
    release();
    return true;
  }
  window.addEventListener('pagehide', () => { busy = false; trimMemory(80); });
  window.PulseMedia = Object.freeze({
    getSegmenter, cutoutPerson, drawThumbnail, ReelRenderer, segmentFrame, pickRecorderMime, fixWebmDuration, release, trimMemory,
    onTrim: fn => trimListeners.add(fn), setBusy: flag => { busy = !!flag; },
    calibrateVideo, buildCaptions,
    get delegate() { return segmenter ? config().label : null; }
  });
})();

/* ═════════════════════ 3. Aplicación: asistente de 5 pasos ═════════════════════ */
(() => {
 const $ = id => document.getElementById(id);
 const state = {category:'all',items:[],selected:null,isManual:false,images:[],script:'',busy:false,runId:0};
 const categories = {all:['technology','cybersecurity','artificial intelligence','hardware'],tech:['technology','software'],cyber:['cybersecurity','ransomware'],ai:['artificial intelligence','machine learning'],hardware:['hardware','semiconductor']};
 const categoryLabels={all:'Todas las temáticas',tech:'Tech general',cyber:'Ciberseguridad',ai:'Inteligencia artificial',hardware:'Hardware'};
 const htmlStrip = html => { const doc = new DOMParser().parseFromString(String(html||''),'text/html'); return (doc.body.textContent||'').replace(/\s+/g,' ').trim(); };
 const safeHttp = input => {try {const u=new URL(input);return ['https:','http:'].includes(u.protocol)?u.href:null;}catch{return null;}};
 const extLink = (url,text) => {const link=document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=text;return link;};
 const status = (id,msg,kind='info') => {const el=$(id);el.textContent=msg;el.dataset.kind=kind;};
 const prettyDate = input => { if(!input)return 'No indicada';const d=new Date(input);return Number.isNaN(d.getTime())?'No indicada':new Intl.DateTimeFormat('es-ES',{dateStyle:'medium'}).format(d);};
 let previousFocus=null;
 let lastExtractedArticle='';
 let wizardStep=1;
 const STEP_COUNT=5;
 function setWizardStep(step,scroll=true){
  const previous=wizardStep;
  wizardStep=Math.max(1,Math.min(STEP_COUNT,step));
  for(let n=1;n<=STEP_COUNT;n++){const panel=$(`stepPanel${n}`),tab=$(`stepTab${n}`);if(panel)panel.classList.toggle('hidden',n!==wizardStep);if(tab){const done=n<wizardStep;tab.dataset.state=done?'done':n===wizardStep?'active':'pending';tab.setAttribute('aria-current',n===wizardStep?'step':'false');const marker=tab.querySelector('.step-dot');if(marker)marker.textContent=done?'✓':String(n);}}
  if(scroll){const target=$(`stepPanel${wizardStep}`);if(target)target.scrollIntoView({behavior:'smooth',block:'start'});}
  if(previous===5&&wizardStep!==5&&media.video.ready)$('videoSource').pause();
  if(wizardStep===4){syncThumbSources();renderThumb();}
  if(wizardStep===5)enterVideoStep();
 }
 function updateServiceState(service,state,label){document.querySelectorAll(`[data-service-state="${service}"]`).forEach(el=>{el.dataset.state=state;el.textContent=label;});}
 function syncServiceStates(){
  for(const name of ['groq','pexels','hf']){
   const typed=$(keyInputs[name]).value.trim();
   if(typed)updateServiceState(name,'configured','Sin guardar');
   else if(configured(name))updateServiceState(name,'configured','Configurado');
   else updateServiceState(name,'off','Sin configurar');
  }
 }
 function openSettings(){
  if(state.busy||media.busy)return;
  previousFocus=document.activeElement;
  $('settingsScreen').classList.remove('hidden');document.body.classList.add('settings-open');
  $('openSettingsBtn').setAttribute('aria-expanded','true');$('closeSettingsBtn').focus();
 }
 function closeSettings(){
  if($('settingsScreen').classList.contains('hidden'))return;
  $('settingsScreen').classList.add('hidden');document.body.classList.remove('settings-open');
  $('openSettingsBtn').setAttribute('aria-expanded','false');
  if(previousFocus&&typeof previousFocus.focus==='function')previousFocus.focus();
 }
 $('settingsScreen').addEventListener('keydown',event=>{
  if(event.key==='Escape'){event.preventDefault();closeSettings();return;}
  if(event.key!=='Tab')return;
  const active=[...$('settingsScreen').querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),a[href]')];
  if(!active.length)return;
  if(event.shiftKey&&document.activeElement===active[0]){event.preventDefault();active[active.length-1].focus();}
  else if(!event.shiftKey&&document.activeElement===active[active.length-1]){event.preventDefault();active[0].focus();}
 });
 // Escape (y el menú de la ventana) cierran la configuración.
 window.PulseSettings=Object.freeze({closeIfOpen(){if($('settingsScreen').classList.contains('hidden'))return false;closeSettings();return true;}});

 function setProgress(n){$('progressBar').style.width=Math.max(0,Math.min(100,n))+'%';}
 function setBusy(flag){state.busy=flag;for(const id of ['searchInput','sourceSelect','sortSelect','manualTitle','manualSource','manualDate','manualUrl','articleText','groqKey','pexelsKey','modelName','hfKey','hfModel'])$(id).disabled=flag;for(const id of ['searchBtn','manualBtn','generateBtn','openSettingsBtn','saveKeysBtn','clearKeysBtn','groqTestBtn','pexelsTestBtn','hfTestBtn','backToSearchBtn','backToEditorBtn'])$(id).disabled=flag;document.querySelectorAll('.cat').forEach(el=>el.disabled=flag);$('generateBtn').textContent=flag?'◌ Generando contenido…':'✦ Generar pack de TikTok';$('imagesBtn').disabled=flag||!isComplete();$('txtBtn').disabled=flag||!state.script;$('copyBtn').disabled=flag||!state.script;}
 function clearPack(){resetNewsMedia();state.runId++;state.packFolder='';state.images.forEach(im=>URL.revokeObjectURL(im.preview));state.images=[];state.script='';$('scriptOutput').value='';$('scriptOutput').disabled=true;$('pictureGrid').replaceChildren();const empty=document.createElement('div');empty.className='empty';empty.style.gridColumn='1/-1';empty.textContent='Las seis imágenes aparecerán aquí.';$('pictureGrid').append(empty);$('imagesBtn').disabled=true;$('txtBtn').disabled=true;$('copyBtn').disabled=true;setProgress(0);}
 function current(){if(state.isManual){return {title:$('manualTitle').value.trim(),url:$('manualUrl').value.trim(),source:$('manualSource').value.trim(),date:$('manualDate').value||'',summary:'',storyText:''};}return state.selected;}
 function renderPreview(){const n=current(),box=$('preview');box.replaceChildren();if(!n){const x=document.createElement('div');x.className='empty';x.textContent='Selecciona una noticia o introduce una manualmente.';box.append(x);return;}
 const heading=document.createElement('h3');heading.textContent=n.title||'Noticia manual: completa los campos';box.append(heading);
 if(n.summary){const p=document.createElement('p');p.textContent=n.summary;box.append(p);}
 const dl=document.createElement('dl');[['Medio',n.source||'Pendiente'],['Fecha',prettyDate(n.date)]].forEach(([label,value])=>{const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value;dl.append(dt,dd);});const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent='Fuente';dd.className='source';const url=safeHttp(n.url);if(url)dd.append(extLink(url,url));else dd.textContent='Pendiente de indicar';dl.append(dt,dd);box.append(dl);
 }
 function choose(item){if(state.busy)return;state.isManual=false;$('manualFields').classList.add('hidden');state.selected=item;$('articleText').value='';lastExtractedArticle='';clearPack();renderPreview();document.querySelectorAll('.item').forEach(el=>el.setAttribute('aria-selected',String(el.dataset.id===item.id)));status('genStatus','Noticia seleccionada. Comprueba los datos y genera el contenido.');setWizardStep(2);}
 function makeItem(n){const button=document.createElement('button');button.type='button';button.className='item';button.dataset.id=n.id;button.setAttribute('aria-selected',String(state.selected?.id===n.id));const headline=document.createElement('span');headline.className='title';headline.textContent=n.title;const meta=document.createElement('div');meta.className='meta';const a=document.createElement('span');a.textContent=n.source;const b=document.createElement('span');b.textContent=prettyDate(n.date);a.className='sourceflag';meta.append(a,b);button.append(headline,meta);button.addEventListener('click',()=>choose(n));return button;}
 function renderFeed(){const list=$('feedList');list.replaceChildren();if(!state.items.length){const empty=document.createElement('div');empty.className='empty';empty.textContent='No hay noticias en esta búsqueda. Prueba otro término o introduce una fuente manual.';list.append(empty);return;}const frag=document.createDocumentFragment();state.items.forEach(n=>frag.append(makeItem(n)));list.append(frag);}
 // El backend ya deduplica y ordena; aquí solo se reordena al cambiar el selector sin volver a buscar.
 function dedupeAndSort(items){return [...items].sort((a,b)=>state.sort==='oldest'?a.publishedAt-b.publishedAt:b.publishedAt-a.publishedAt);}
 /* GET /api/news: el backend consulta Hacker News y los RSS, filtra por categoría, deduplica y ordena. */
 async function findNews(){
  if(state.busy)return;await settingsReady;if(state.busy)return;
  setBusy(true);state.sort=$('sortSelect').value;
  const query=$('searchInput').value.trim(),source=$('sourceSelect').value;
  status('searchStatus','Consultando '+(source==='all'?'las fuentes disponibles':$('sourceSelect').selectedOptions[0].textContent)+'…');
  try{
   const data=await PulseApi.get('/api/news?'+new URLSearchParams({q:query,category:state.category,source,sort:state.sort}),{timeout:90000});
   state.items=data.items||[];renderFeed();
   const count=state.items.length,bySource=Object.entries(data.bySource||{}).map(([name,n])=>`${name}: ${n}`),failures=data.failures||[];
   let message=`${count} noticias · ${data.categoryLabel||categoryLabels[state.category]} · orden ${state.sort==='oldest'?'antiguas':'recientes'}. ${bySource.join(' · ')||'Sin resultados disponibles'}.`;
   if(failures.length)message+=' Fuentes no disponibles: '+failures.join(' | ');
   if(data.partial)message+=' Algunas búsquedas de Hacker News no respondieron.';
   status('searchStatus',message,failures.length||data.partial?'warning':count?'success':'info');
  }catch(err){state.items=[];renderFeed();status('searchStatus','No se han podido obtener noticias: '+err.message,'error');}
  finally{setBusy(false);}
 }
 function activateManual(){if(state.busy)return;state.isManual=true;state.selected=null;clearPack();$('manualFields').classList.remove('hidden');$('articleText').value='';lastExtractedArticle='';$('manualDate').value=new Date().toISOString().slice(0,10);renderPreview();status('genStatus','Completa los datos de la noticia. Al generar se intentará recuperar el artículo automáticamente.');setWizardStep(2);}
 /* Las claves nunca llegan a la interfaz: solo se sabe si cada servicio está configurado en el backend. */
 let settingsInfo=null;
 const keyInputs={groq:'groqKey',pexels:'pexelsKey',hf:'hfKey'};
 const serviceLabel=name=>({groq:'Groq',pexels:'Pexels',hf:'Hugging Face'})[name]||name;
 const configured=name=>!!settingsInfo?.services?.[name]?.configured;
 function requireServices(...names){
  const missing=names.filter(name=>!configured(name));
  if(missing.length)throw new Error(`Configura ${missing.map(serviceLabel).join(' y ')} en Configuración (⚙, arriba a la derecha).`);
 }
 /* Todo lo que se exporta de una noticia va a la misma carpeta dentro de la carpeta de salida. */
 function packFolderName(n){
  const stamp=new Date().toISOString().replace(/[-:]/g,'').replace('T','_').slice(0,13);
  const slug=String(n?.title||'noticia').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-+|-+$/g,'').slice(0,40);
  return `PulseStudio_${stamp}${slug?'_'+slug:''}`.slice(0,80);
 }
 function packFolder(){if(!state.packFolder)state.packFolder=packFolderName(current());return state.packFolder;}
 /* Mensaje de guardado con acceso directo a la carpeta en el Explorador. */
 function showSaved(id,label,result){
  const el=$(id);el.replaceChildren();el.dataset.kind='success';
  const text=document.createElement('span');text.textContent=`${label} ${result.path}`;
  const open=document.createElement('button');open.type='button';open.className='btn alt small reveal-path';open.textContent='📂 Mostrar en la carpeta';
  open.addEventListener('click',()=>PulseApi.post('/api/system/reveal',{path:result.path}).catch(err=>status(id,err.message,'error')));
  el.append(text,open);
 }
 function cleanString(x,max=6000){return typeof x==='string'?x.trim().slice(0,max):'';}
 function displayImages(){const grid=$('pictureGrid');grid.replaceChildren();state.images.forEach((im,i)=>{const div=document.createElement('div');div.className='picture';const media=document.createElement('div');media.className='picture-media';const img=document.createElement('img');img.src=im.preview;img.alt=`Imagen ${i+1}: foto ilustrativa de ${im.query}`;img.loading='lazy';const dl=document.createElement('button');dl.type='button';dl.className='image-save';dl.setAttribute('aria-label',`Descargar imagen ${i+1}`);dl.textContent='↓';dl.addEventListener('click',()=>downloadSingleImage(i));media.append(img,dl);const info=document.createElement('div');info.className='desc';const strong=document.createElement('strong');strong.textContent=`Imagen ${i+1}`;const span=document.createElement('span');span.textContent=` · ${im.photographer}`;info.append(strong,span,document.createElement('br'));if(im.photoUrl)info.append(extLink(im.photoUrl,'Ver original en Pexels ↗'));div.append(media,info);grid.append(div);});$('imageCount').textContent=`${state.images.length}/6`; }
 function isComplete(){return state.script.trim()&&state.images.length===6&&state.images.every(i=>i.blob instanceof Blob&&i.blob.type==='image/jpeg'&&i.blob.size>1000);}
 /* Generar el pack: POST /api/article → POST /api/script → 6 × POST /api/photo (JPEG 1080 × 1920 desde el backend). */
 async function generate(){
  if(state.busy)return;await settingsReady;if(state.busy)return;
  const n=current();
  if(!n){status('genStatus','Selecciona una noticia antes de generar.','error');return;}
  if(!n.title||!n.source||!safeHttp(n.url)){status('genStatus','Faltan el titular, el medio o la URL original válida.','error');return;}
  try{requireServices('groq','pexels');}catch(e){status('genStatus',e.message,'error');openSettings();return;}
  clearPack();setBusy(true);const run=state.runId;let stage='';
  try{
   stage='Lectura del artículo';setProgress(8);status('genStatus','1/3 · Leyendo y limpiando la noticia original…');
   const evidence=await PulseApi.post('/api/article',{url:n.url,title:n.title,storyText:n.storyText||'',summary:n.summary||'',manualText:$('articleText').value,lastExtracted:lastExtractedArticle},{timeout:120000});
   $('articleText').value=evidence.text;lastExtractedArticle=evidence.text;
   setProgress(26);status('genStatus',`2/3 · Artículo recuperado (${evidence.characters.toLocaleString('es-ES')} caracteres). Generando el guion en español de España…`,evidence.automatic?'success':'warning');
   stage='Guion';
   const out=await PulseApi.post('/api/script',{news:{title:n.title,source:n.source,date:n.date||'',url:n.url},evidence:{text:evidence.text,origin:evidence.origin},category:state.category},{timeout:150000});
   state.script=out.script;$('scriptOutput').value=out.script;$('scriptOutput').disabled=false;$('txtBtn').disabled=false;$('copyBtn').disabled=false;
   stage='Imágenes';setProgress(43);state.packFolder=packFolderName(n);
   const used=[];
   for(let i=0;i<6;i++){
    status('genStatus',`3/3 · Guion listo. Buscando imagen ${i+1} de 6…`);
    const meta=await PulseApi.post('/api/photo',{query:out.queries[i],exclude:used},{timeout:120000});
    used.push(meta.pexelsId);
    const blob=await PulseApi.get(meta.url,{timeout:60000});
    if(run!==state.runId)return;
    state.images.push({...meta,blob,preview:URL.createObjectURL(blob)});displayImages();setProgress(43+Math.round((i+1)/6*53));
   }
   if(!isComplete())throw new Error('Falta algún archivo JPEG.');
   setProgress(100);status('genStatus','Pack generado: guion y seis imágenes listos para revisar y guardar.','success');setWizardStep(3);
  }catch(err){status('genStatus',`${stage}: ${err.message}${state.images.length?' Las imágenes ya recuperadas seguirán visibles; vuelve a generar para completar las seis.':''}`,'error');}
  finally{setBusy(false);}
 }
 function getScript(){return $('scriptOutput').value.trim();}
 function savedStatus(result,label){showSaved('genStatus',label,result);}
 async function downloadText(){const value=getScript();if(!value){status('genStatus','El guion está vacío.','error');return;}
  $('txtBtn').disabled=true;try{status('genStatus','Guardando el guion…');
   savedStatus(await PulsePlatform.saveBlob(new Blob(['\ufeff',value,'\n'],{type:'text/plain;charset=utf-8'}),'guion_tiktok.txt',{folder:packFolder()}),'Guion TXT guardado en');
  }catch(err){status('genStatus','Error al guardar TXT: '+err.message,'error');}finally{$('txtBtn').disabled=state.busy||!state.script;}
 }
 async function copyScript(){const value=getScript();if(!value)return;try{if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(value);else{const area=document.createElement('textarea');area.value=value;area.style.position='fixed';area.style.opacity='0';document.body.append(area);area.select();if(typeof document.execCommand!=='function'||!document.execCommand('copy'))throw new Error('Portapapeles no disponible.');area.remove();}$('copyBtn').textContent='✓ Copiado';setTimeout(()=>{$('copyBtn').textContent='⧉ Copiar';},1400);}catch(err){status('genStatus','No se pudo copiar el guion automáticamente. Puedes seleccionarlo manualmente.','warning');}}
 async function downloadSingleImage(index){const im=state.images[index];if(!im)return;try{status('genStatus',`Preparando imagen ${index+1}…`);savedStatus(await PulsePlatform.saveBlob(im.blob,`imagen_${index+1}.jpg`,{folder:packFolder()}),`Imagen ${index+1} guardada en`);}catch(err){status('genStatus','Error al guardar la imagen: '+err.message,'error');}}
 async function downloadImages(){if(!isComplete()){status('genStatus','Todavía no están listas las seis imágenes.','error');return;}$('imagesBtn').disabled=true;try{status('genStatus','Guardando las seis imágenes…');const files=state.images.map((im,i)=>({name:`imagen_${i+1}.jpg`,blob:im.blob}));const result=await PulsePlatform.saveImages(files,packFolder());showSaved('genStatus','Las seis imágenes se han guardado en',result);}catch(err){status('genStatus','Error al guardar las imágenes: '+err.message,'error');}finally{$('imagesBtn').disabled=state.busy||!isComplete();}}
 /* POST /api/settings/test/{servicio}: prueba con la clave escrita o, si está vacía, con la guardada. */
 async function testService(name){
  const btn=$(name+'TestBtn');btn.disabled=true;updateServiceState(name,'checking','Comprobando…');
  try{await PulseApi.post('/api/settings/test/'+name,{key:$(keyInputs[name]).value.trim()},{timeout:40000});updateServiceState(name,'ok','Conectado');}
  catch(err){updateServiceState(name,'error','Error');status('keyStatus',`${serviceLabel(name)}: ${err.message}`,'error');}
  finally{btn.disabled=state.busy||media.busy;}
 }
 const testGroq=()=>testService('groq');
 const testPexels=()=>testService('pexels');
 document.querySelectorAll('.cat').forEach(button=>button.addEventListener('click',()=>{state.category=button.dataset.category;document.querySelectorAll('.cat').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));}));$('searchBtn').addEventListener('click',findNews);$('openSettingsBtn').addEventListener('click',openSettings);$('closeSettingsBtn').addEventListener('click',closeSettings);$('sourceSelect').addEventListener('change',()=>status('searchStatus','Filtro actualizado. Pulsa «Buscar noticias» para aplicar el cambio.'));$('sortSelect').addEventListener('change',()=>{state.sort=$('sortSelect').value;state.items=dedupeAndSort(state.items);renderFeed();});$('searchInput').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();findNews();}});$('manualBtn').addEventListener('click',activateManual);$('filtersToggle').addEventListener('click',()=>{$('filtersBox').classList.toggle('hidden');$('filtersToggle').setAttribute('aria-expanded',String(!$('filtersBox').classList.contains('hidden')));});$('backToSearchBtn').addEventListener('click',()=>setWizardStep(1));$('backToEditorBtn').addEventListener('click',()=>setWizardStep(2));for(const id of ['manualTitle','manualSource','manualDate','manualUrl'])$(id).addEventListener('input',()=>{if(state.isManual){clearPack();renderPreview();}});$('articleText').addEventListener('input',()=>{if(!state.busy&&(state.script||state.images.length)){clearPack();status('genStatus','Se ha modificado el contenido: vuelve a generar el pack.','warning');}});$('generateBtn').addEventListener('click',generate);$('txtBtn').addEventListener('click',downloadText);$('copyBtn').addEventListener('click',copyScript);$('imagesBtn').addEventListener('click',downloadImages);$('groqTestBtn').addEventListener('click',testGroq);$('pexelsTestBtn').addEventListener('click',testPexels);for(const id of ['groqKey','pexelsKey'])$(id).addEventListener('input',syncServiceStates);document.querySelectorAll('[data-reveal]').forEach(button=>button.addEventListener('click',()=>{const input=$(button.dataset.reveal);const show=input.type==='password';input.type=show?'text':'password';button.textContent=show?'Ocultar':'Mostrar';}));setWizardStep(1,false);
 // Enlaces externos: se abren en el navegador predeterminado del sistema (POST /api/system/open-url).
 document.addEventListener('click',event=>{
  const a=event.target.closest?.('a[href]');if(!a)return;
  const href=safeHttp(a.href);if(!href||new URL(href).origin===location.origin)return;
  event.preventDefault();PulseApi.post('/api/system/open-url',{url:href}).catch(e=>status('genStatus',e.message,'error'));
 },true);
 /* ───────────── v1.6 · Hugging Face (fondo IA), miniatura y editor de vídeo ───────────── */
 const media={busy:false,
  thumb:{photo:null,person:null,aiBg:null,packBg:null,packKey:'',headlineTouched:false,ready:false,frame:0},
  video:{url:null,duration:0,starts:[0,0,0,0,0,0],ready:false,raf:0,lastDrawn:-1,audio:null,recorder:null,cancelled:false,cancel:null,result:null,resultUrl:null,resultExt:'',backgrounds:[],bgKey:'',renderer:null,engine:false,order:[0,1,2,3,4,5],words:[],captions:[],captionColor:'#ffffff'}};
 const MEDIA_CONTROLS=['thumbPhoto','thumbBgSource','thumbPrompt','thumbBgBtn','thumbCreateBtn','thumbHeadline','thumbHighlight','thumbScale','thumbX','thumbDim','videoFile','videoPlayBtn','videoSeek','videoEvenBtn','videoCutout','videoScale','videoX','videoQuality','videoExportBtn','videoSaveBtn','captionLang','captionGenBtn','captionOn','captionFont','captionStyle','captionColorCustom','captionUpper','captionSize','captionY','captionWords','backToPackBtn','backToThumbBtn','toVideoBtn','toThumbBtn','toVideoFromPackBtn'];
 function setMediaBusy(flag){
  media.busy=flag;PulseMedia.setBusy(flag);
  for(const id of MEDIA_CONTROLS){const el=$(id);if(el)el.disabled=flag;}
  timelineControls().forEach(el=>el.disabled=flag||!media.video.ready);
  document.querySelectorAll('#captionSwatches .swatch,#captionList input,#captionList button').forEach(el=>el.disabled=flag);
  $('openSettingsBtn').disabled=flag||state.busy;
  if(!flag){syncThumbButtons();syncVideoControls();syncCaptionControls();}
 }
 const packReady=()=>!!isComplete();
 // La imagen 1 siempre empieza en 0: su control queda desactivado.
 const timelineControls=()=>[...document.querySelectorAll('#timelineRows .tl-row:not(:first-child) input,#timelineRows .tl-row:not(:first-child) button')];

 // ── Hugging Face Inference Providers: llamada directa desde el cliente con el token del usuario ──
 const testHf=()=>testService('hf');
 // ── Paso 4 · Miniatura: Groq (prompt + titular) → Hugging Face (fondo) → MediaPipe (recorte) → Canvas ──
 /* POST /api/thumbnail/brief: Groq propone titular, palabra destacada y prompt visual (en el backend). */
 async function thumbnailBrief(n){
  const context=cleanString(state.script||$('articleText').value||n.summary||'',2600);
  return PulseApi.post('/api/thumbnail/brief',{news:{title:n.title,source:n.source},context},{timeout:120000});
 }
 function closeBitmap(b){if(b&&typeof b.close==='function')try{b.close();}catch{}}
 function releasePerson(p){if(p){for(const c of [p.canvas,p.silhouette])if(c){c.width=0;c.height=0;}}}
 function thumbStatus(msg,kind='info'){status('thumbStatus',msg,kind);}
 function setThumbProgress(n){$('thumbProgress').style.width=Math.max(0,Math.min(100,n))+'%';}
 function syncThumbSources(){const sel=$('thumbBgSource'),ok=packReady();[...sel.options].forEach(o=>{if(o.value.startsWith('pack-'))o.disabled=!ok;});if(!ok&&sel.value!=='ai')sel.value='ai';}
 function syncThumbButtons(){const has=!!(media.thumb.person||currentThumbBackground());for(const id of ['thumbJpgBtn','thumbPngBtn'])$(id).disabled=media.busy||!has;}
 function currentThumbBackground(){const src=$('thumbBgSource').value;return src==='ai'?media.thumb.aiBg:media.thumb.packKey===src?media.thumb.packBg:null;}
 async function ensurePackBackground(src){
  if(!src.startsWith('pack-'))return;const i=Number(src.slice(5)),im=state.images[i];if(!im)throw new Error('La imagen del pack no está disponible.');
  if(media.thumb.packKey===src&&media.thumb.packBg)return;
  closeBitmap(media.thumb.packBg);media.thumb.packBg=await createImageBitmap(im.blob);media.thumb.packKey=src;
 }
 function renderThumb(){
  cancelAnimationFrame(media.thumb.frame);
  media.thumb.frame=requestAnimationFrame(()=>{
   const n=current();
   PulseMedia.drawThumbnail($('thumbCanvas'),{background:currentThumbBackground(),person:media.thumb.person,headline:$('thumbHeadline').value.trim()||(n?.title||'Tu titular aquí'),highlight:$('thumbHighlight').value.trim(),scale:Number($('thumbScale').value)/100,x:Number($('thumbX').value)/100,dim:Number($('thumbDim').value)/100});
   $('thumbScaleOut').textContent=$('thumbScale').value+' %';$('thumbXOut').textContent=$('thumbX').value;$('thumbDimOut').textContent=$('thumbDim').value+' %';
   syncThumbButtons();
  });
 }
 async function cutoutPhoto(){
  if(!media.thumb.photo)throw new Error('Elige primero tu foto.');
  if(media.thumb.person)return media.thumb.person;
  const person=await PulseMedia.cutoutPerson(media.thumb.photo);
  releasePerson(media.thumb.person);media.thumb.person=person;return person;
 }
 async function onThumbPhoto(){
  const file=$('thumbPhoto').files?.[0];if(!file)return;
  if(!/^image\/(jpeg|png|webp|heic|heif|gif|avif)$/i.test(file.type||'')&&!/\.(jpe?g|png|webp|heic|heif|avif)$/i.test(file.name)){thumbStatus('El archivo elegido no es una imagen compatible (JPG, PNG o WebP).','error');return;}
  if(file.size>40*1024*1024){thumbStatus('La foto supera 40 MB. Elige una versión más ligera.','error');return;}
  setMediaBusy(true);
  try{
   thumbStatus('Recortando tu foto en el dispositivo… (la primera vez se carga el motor local)');setThumbProgress(15);
   let bitmap;try{bitmap=await createImageBitmap(file,{imageOrientation:'from-image'});}catch{throw new Error('No se puede leer esta foto. Prueba con un JPG o PNG.');}
   closeBitmap(media.thumb.photo);media.thumb.photo=bitmap;releasePerson(media.thumb.person);media.thumb.person=null;
   $('thumbPhotoName').textContent=file.name.slice(0,60);
   const person=await cutoutPhoto();setThumbProgress(100);renderThumb();
   thumbStatus(`Foto recortada en tu equipo (${person.engine}). Ahora pulsa «Crear miniatura».`,'success');
  }catch(err){thumbStatus(err.message,'error');setThumbProgress(0);}
  finally{$('thumbPhoto').value='';setMediaBusy(false);}
 }
 /* POST /api/thumbnail/background: el backend llama a Hugging Face con el token guardado y devuelve la imagen. */
 async function generateAiBackground(prompt){
  if(!configured('hf')){const e=new Error('Añade tu token de Hugging Face en Configuración o elige una imagen del pack como fondo.');e.settings=true;throw e;}
  const blob=await PulseApi.post('/api/thumbnail/background',{prompt,model:$('hfModel').value},{timeout:240000});
  let bitmap;
  try{bitmap=await createImageBitmap(blob);}catch{throw new Error('No se puede decodificar la imagen generada por Hugging Face.');}
  closeBitmap(media.thumb.aiBg);media.thumb.aiBg=bitmap;
 }
 async function createThumbnail(){
  if(media.busy||state.busy)return;await settingsReady;
  const n=current();if(!n||!n.title){thumbStatus('Primero elige y genera una noticia (pasos 1 y 2).','error');return;}
  if(!media.thumb.photo){thumbStatus('Elige primero tu foto (punto 1).','error');$('thumbPhoto').focus();return;}
  const src=$('thumbBgSource').value;
  if(!configured('groq')){thumbStatus('Añade tu clave de Groq en Configuración.','error');openSettings();return;}
  if(src==='ai'&&!configured('hf')){thumbStatus('Añade tu token de Hugging Face en Configuración o elige una imagen del pack como fondo.','error');openSettings();return;}
  setMediaBusy(true);let stage='';
  try{
   const cut=cutoutPhoto();cut.catch(()=>{});
   stage='Groq';setThumbProgress(8);thumbStatus('1/4 · Groq: preparando el titular y el prompt visual…');
   const brief=await thumbnailBrief(n);$('thumbPrompt').value=brief.prompt;
   if(!media.thumb.headlineTouched){$('thumbHeadline').value=brief.headline;$('thumbHighlight').value=brief.highlight;}
   renderThumb();
   if(src==='ai'){stage='Hugging Face';setThumbProgress(30);thumbStatus(`2/4 · Hugging Face: generando el fondo con ${$('hfModel').selectedOptions[0].textContent.split(' · ')[0]}… (10-40 s)`);await generateAiBackground(brief.prompt);}
   else{stage='Fondo';await ensurePackBackground(src);}
   stage='Recorte';setThumbProgress(78);thumbStatus('3/4 · Recortando tu foto en el dispositivo…');await cut;
   stage='Composición';setThumbProgress(94);thumbStatus('4/4 · Componiendo la miniatura…');renderThumb();
   setThumbProgress(100);thumbStatus('Miniatura lista. Ajusta el titular, el tamaño o la posición y guárdala en tu carpeta.','success');
  }catch(err){thumbStatus(`${stage}: ${err.message}`,'error');setThumbProgress(0);if(err.settings)openSettings();}
  finally{setMediaBusy(false);renderThumb();}
 }
 async function regenerateBackground(){
  if(media.busy||state.busy)return;await settingsReady;
  const prompt=$('thumbPrompt').value.trim();if(prompt.length<12){thumbStatus('Escribe un prompt visual (en inglés) o pulsa «Crear miniatura».','error');return;}
  $('thumbBgSource').value='ai';setMediaBusy(true);
  try{setThumbProgress(30);thumbStatus('Hugging Face: generando un fondo nuevo…');await generateAiBackground(prompt);setThumbProgress(100);renderThumb();thumbStatus('Fondo actualizado.','success');}
  catch(err){thumbStatus('Hugging Face: '+err.message,'error');setThumbProgress(0);if(err.settings)openSettings();}
  finally{setMediaBusy(false);}
 }
 function mediaSaved(target,result,label){showSaved(target,label,result);}
 async function saveThumb(type){
  if(media.busy)return;const jpg=type==='jpg';setMediaBusy(true);
  try{
   const blob=await new Promise((resolve,reject)=>$('thumbCanvas').toBlob(b=>b&&b.size>1000?resolve(b):reject(new Error('No se ha podido exportar la miniatura.')),jpg?'image/jpeg':'image/png',0.93));
   thumbStatus('Guardando la miniatura…');
   mediaSaved('thumbStatus',await PulsePlatform.saveBlob(blob,`miniatura_tiktok_${stampNow()}.${jpg?'jpg':'png'}`,{folder:packFolder()}),'Miniatura guardada en');
  }catch(err){thumbStatus('Error al guardar la miniatura: '+err.message,'error');}
  finally{setMediaBusy(false);}
 }
 const stampNow=()=>new Date().toISOString().replace(/[-:]/g,'').replace('T','_').slice(0,15);
 function resetNewsMedia(){
  // La noticia ha cambiado: fondo, prompt, titular y vídeo exportado ya no son válidos. La foto recortada se conserva.
  closeBitmap(media.thumb.aiBg);closeBitmap(media.thumb.packBg);media.thumb.aiBg=media.thumb.packBg=null;media.thumb.packKey='';media.thumb.headlineTouched=false;
  for(const id of ['thumbPrompt','thumbHeadline','thumbHighlight'])if($(id))$(id).value='';
  releaseVideoBackgrounds();discardVideoResult();
 }

 // ── Paso 5 · Editor de vídeo: fondo por línea de tiempo + sujeto sin fondo + MediaRecorder ──
 const V=media.video,MAX_VIDEO_SECONDS=600,MAX_VIDEO_INPUT=2048*1024*1024;
 const fmt=s=>{s=Math.max(0,Number(s)||0);const m=Math.floor(s/60),r=Math.floor(s%60);return `${m}:${String(r).padStart(2,'0')}`;};
 const fmtPrecise=s=>{s=Math.max(0,Number(s)||0);return `${fmt(s)}.${Math.floor((s%1)*10)}`;};
 function videoStatus(msg,kind='info'){status('videoStatus',msg,kind);}
 function renderer(){if(!V.renderer)V.renderer=new PulseMedia.ReelRenderer($('videoCanvas'));return V.renderer;}
 function releaseVideoBackgrounds(){V.backgrounds.forEach(closeBitmap);V.backgrounds=[];V.bgKey='';}
 async function ensureVideoBackgrounds(){
  const key=state.images.map(im=>im.preview).join('|');
  if(V.bgKey===key&&V.backgrounds.length===6)return;
  releaseVideoBackgrounds();
  // 720 × 1280 basta para el fondo del reel y reduce a la mitad la memoria de las seis imágenes.
  V.backgrounds=await Promise.all(state.images.map(im=>createImageBitmap(im.blob,{resizeWidth:720,resizeHeight:1280,resizeQuality:'high'}).catch(()=>createImageBitmap(im.blob))));V.bgKey=key;
 }
 function discardVideoResult(){if(V.resultUrl)URL.revokeObjectURL(V.resultUrl);V.result=null;V.resultUrl=null;V.resultExt='';const player=$('videoResultPlayer');if(player&&player.getAttribute('src')){player.removeAttribute('src');player.load();}$('videoResult')?.classList.add('hidden');}
 function releaseVideoSource(){
  stopLoop();const v=$('videoSource');if(v.getAttribute('src')){v.pause();v.removeAttribute('src');v.load();}
  if(V.url)URL.revokeObjectURL(V.url);V.url=null;V.file=null;V.ready=false;V.duration=0;V.lastDrawn=-1;renderer().resetMask();
 }
 function useCutout(){return $('videoCutout').checked&&V.engine;}
 // Opciones de la escena comunes a vista previa y exportación: orden de imágenes, sujeto y subtítulos.
 function captionStyle(){return {font:$('captionFont').value,style:$('captionStyle').value,color:V.captionColor,upper:$('captionUpper').checked,size:Number($('captionSize').value)/100,y:Number($('captionY').value)/100};}
 function sceneOptions(){
  return {backgrounds:V.backgrounds.length?V.order.map(i=>V.backgrounds[i]):[],starts:V.starts,duration:V.duration,
   scale:Number($('videoScale').value)/100,x:Number($('videoX').value)/100,
   captions:$('captionOn').checked&&V.captions.length?V.captions:null,captionStyle:captionStyle()};
 }
 function drawVideoFrame(force){
  const v=$('videoSource');if(!force&&v.currentTime===V.lastDrawn)return;V.lastDrawn=v.currentTime;
  try{renderer().draw({...sceneOptions(),video:V.ready?v:null,time:v.currentTime||0,segmentFrame:useCutout()?PulseMedia.segmentFrame:null});}
  catch(err){V.engine=false;$('videoCutout').checked=false;videoStatus('El recorte en tiempo real ha fallado y se muestra el vídeo en recuadro: '+err.message,'warning');}
  const d=V.duration||0,t=v.currentTime||0;$('videoSeek').value=String(t);$('videoTime').textContent=`${fmt(t)} / ${fmt(d)}`;
  $('timelineHead').style.left=d?`${Math.min(100,t/d*100)}%`:'0';
  if(V.recorder&&d)$('videoProgress').style.width=`${Math.min(100,t/d*100)}%`;
 }
 function startLoop(){cancelAnimationFrame(V.raf);const tick=()=>{V.raf=requestAnimationFrame(tick);drawVideoFrame(false);};V.raf=requestAnimationFrame(tick);}
 function stopLoop(){cancelAnimationFrame(V.raf);V.raf=0;}
 function once(target,ok,fail,wait){return new Promise((resolve,reject)=>{const t=setTimeout(()=>done(reject,new Error('timeout')),wait);function done(fn,val){clearTimeout(t);target.removeEventListener(ok,okH);if(fail)target.removeEventListener(fail,failH);fn(val);}function okH(e){done(resolve,e);}function failH(){done(reject,new Error(fail));}target.addEventListener(ok,okH);if(fail)target.addEventListener(fail,failH);});}
 async function seekTo(t){const v=$('videoSource');const target=Math.max(0,Math.min(V.duration||0,t));if(Math.abs(v.currentTime-target)<0.001){drawVideoFrame(true);return;}const wait=once(v,'seeked',null,5000).catch(()=>{});v.currentTime=target;await wait;renderer().resetMask();drawVideoFrame(true);}
 async function ensureVideoEngine(){
  if(!$('videoCutout').checked){V.engine=false;return;}
  if(V.engine)return;
  videoStatus('Cargando el motor local de recorte… (solo la primera vez)');
  try{
   await PulseMedia.getSegmenter('VIDEO');
   if(!V.ready)return;
   // Calibración con un fotograma real: si la GPU de este equipo devuelve máscaras vacías, se prueba otra configuración.
   const v=$('videoSource'),back=v.currentTime;await seekTo(Math.min(1,V.duration/2));
   const cal=await PulseMedia.calibrateVideo(v);await seekTo(back);
   if(!cal.ok){V.engine=false;$('videoCutout').checked=false;videoStatus(`No se detecta a ninguna persona en el vídeo, así que se mostrará en recuadro. (Diagnóstico: ${cal.tried.join(' · ')})`,'warning');return;}
   V.engine=true;videoStatus(`Recorte en tiempo real activo (${cal.engine}).`,'success');
  }catch(err){V.engine=false;$('videoCutout').checked=false;videoStatus(err.message+' Se usará el vídeo en recuadro.','warning');}
 }
 function ensureAudioGraph(){
  if(V.audio)return V.audio;const Ctx=window.AudioContext||window.webkitAudioContext;if(!Ctx)return null;
  const ctx=new Ctx(),source=ctx.createMediaElementSource($('videoSource')),monitor=ctx.createGain();
  source.connect(monitor).connect(ctx.destination);V.audio={ctx,source,monitor};return V.audio;
 }
 function syncVideoControls(){
  const has=V.ready&&!media.busy,pack=packReady();
  $('videoNeedsPack').classList.toggle('hidden',pack);
  for(const id of ['videoPlayBtn','videoSeek','videoEvenBtn'])$(id).disabled=!has;
  $('videoExportBtn').disabled=!has||!pack;$('videoSaveBtn').disabled=media.busy||!V.result;
  timelineControls().forEach(el=>el.disabled=!has);
  $('videoEmpty').classList.toggle('hidden',V.ready);
  $('videoPlayBtn').textContent=$('videoSource').paused?'▶':'❚❚';$('videoPlayBtn').setAttribute('aria-label',$('videoSource').paused?'Reproducir':'Pausar');
 }
 function buildTimeline(){
  const bar=$('timelineBar'),rows=$('timelineRows');bar.querySelectorAll('.tl-seg').forEach(el=>el.remove());rows.replaceChildren();
  V.order=[0,1,2,3,4,5]; // pack nuevo: orden original
  for(let i=0;i<6;i++){
   const seg=document.createElement('div');seg.className='tl-seg';seg.dataset.index=String(i);const img=state.images[V.order[i]];if(img)seg.style.backgroundImage=`url("${img.preview}")`;const tag=document.createElement('span');tag.textContent=String(i+1);seg.append(tag);bar.insertBefore(seg,$('timelineHead'));
   const row=document.createElement('div');row.className='tl-row';const thumb=document.createElement('img');thumb.alt='';if(img)thumb.src=img.preview;
   const main=document.createElement('div');main.className='tl-main';const head=document.createElement('div');head.className='tl-head';const name=document.createElement('label');name.htmlFor=`tlStart${i}`;name.textContent=`Imagen ${i+1}`;const time=document.createElement('span');time.className='tl-time';time.id=`tlTime${i}`;head.append(name,time);
   const range=document.createElement('input');range.type='range';range.id=`tlStart${i}`;range.min='0';range.step='0.1';range.dataset.index=String(i);range.setAttribute('aria-label',`Inicio de la imagen ${i+1}`);
   range.addEventListener('input',()=>setStart(i,Number(range.value)));range.addEventListener('change',()=>seekTo(V.starts[i]+0.05));
   main.append(head,range);
   const here=document.createElement('button');here.type='button';here.className='btn ghost small tl-here';here.textContent='◎ Aquí';here.title='Empezar esta imagen en el instante actual del vídeo';here.setAttribute('aria-label',`Empezar la imagen ${i+1} en el instante actual`);
   here.addEventListener('click',()=>{setStart(i,$('videoSource').currentTime);drawVideoFrame(true);});
   if(i===0){range.disabled=true;here.disabled=true;here.classList.add('invisible');}
   row.append(thumb,main,here);rows.append(row);
  }
  syncTimeline();
 }
 function syncTimeline(){
  const d=V.duration||1;
  for(let i=0;i<6;i++){const r=$(`tlStart${i}`);if(!r)continue;r.max=String(V.duration||0);r.value=String(V.starts[i]);const end=i<5?V.starts[i+1]:V.duration;$(`tlTime${i}`).textContent=`${fmtPrecise(V.starts[i])} → ${fmtPrecise(end)}`;}
  $('timelineBar').querySelectorAll('.tl-seg').forEach((seg,i)=>{const end=i<5?V.starts[i+1]:V.duration;seg.style.flexBasis=`${Math.max(0,(end-V.starts[i])/d*100)}%`;});
 }
 /* Reordenar: mantener pulsada una imagen de la barra y arrastrarla. Los tramos de tiempo no cambian;
    cambia qué imagen ocupa cada tramo. */
 function refreshTimelineImages(){
  const segs=$('timelineBar').querySelectorAll('.tl-seg'),thumbs=$('timelineRows').querySelectorAll('.tl-row img');
  for(let i=0;i<6;i++){const img=state.images[V.order[i]];if(!img)continue;if(segs[i])segs[i].style.backgroundImage=`url("${img.preview}")`;if(thumbs[i])thumbs[i].src=img.preview;}
 }
 function moveImage(from,to){if(from===to)return;const [item]=V.order.splice(from,1);V.order.splice(to,0,item);refreshTimelineImages();}
 function markDragging(slot){$('timelineBar').querySelectorAll('.tl-seg').forEach((el,i)=>el.classList.toggle('dragging',i===slot));}
 (function setupReorder(){
  const bar=$('timelineBar');let press=null;
  const slotAt=x=>{const segs=[...bar.querySelectorAll('.tl-seg')];for(let i=0;i<segs.length;i++)if(x<segs[i].getBoundingClientRect().right)return i;return segs.length-1;};
  bar.addEventListener('contextmenu',e=>e.preventDefault());
  bar.addEventListener('pointerdown',e=>{
   const seg=e.target.closest('.tl-seg');if(!seg||media.busy||!packReady())return;
   const id=e.pointerId;
   const mouse=e.pointerType==='mouse';if(mouse&&e.button!==0)return;if(mouse)e.preventDefault();
   const begin=()=>{
    if(!press||press.dragging)return;press.dragging=true;try{bar.setPointerCapture(id);}catch{}bar.classList.add('reordering');markDragging(press.slot);
    status('videoStatus','Arrastra la imagen a su nueva posición y suelta.');
   };
   press={id,x:e.clientX,y:e.clientY,slot:Number(seg.dataset.index),dragging:false,moved:false,mouse,begin,timer:mouse?0:setTimeout(begin,320)};
  });
  bar.addEventListener('pointermove',e=>{
   if(!press||e.pointerId!==press.id)return;
   if(!press.dragging){
    const dist=Math.hypot(e.clientX-press.x,e.clientY-press.y);
    if(!press.mouse){if(dist>10){clearTimeout(press.timer);press=null;}return;}
    if(dist<4)return;press.begin();
   }
   e.preventDefault();const to=slotAt(e.clientX);
   if(to!==press.slot){moveImage(press.slot,to);press.slot=to;press.moved=true;markDragging(to);}
  });
  const end=e=>{
   if(!press||(e&&e.pointerId!==press.id))return;clearTimeout(press.timer);
   const {dragging,moved}=press;press=null;bar.classList.remove('reordering');markDragging(-1);
   if(moved){drawVideoFrame(true);status('videoStatus','Orden de las imágenes actualizado.','success');}
   else if(dragging)status('videoStatus','La imagen se ha quedado en su sitio.');
  };
  for(const type of ['pointerup','pointercancel','lostpointercapture'])bar.addEventListener(type,end);
 })();
 function evenSplit(){const d=V.duration;V.starts=Array.from({length:6},(_,i)=>Math.round(d*i/6*10)/10);V.starts[0]=0;syncTimeline();drawVideoFrame(true);}
 function setStart(i,value){
  if(i<=0||i>5||!V.duration)return;const gap=Math.min(0.5,V.duration/12);
  const lo=V.starts[i-1]+gap,hi=(i<5?V.starts[i+1]:V.duration)-gap;
  V.starts[i]=Math.round(Math.min(Math.max(value,lo),Math.max(lo,hi))*10)/10;syncTimeline();
 }
 async function readDuration(v){
  if(Number.isFinite(v.duration)&&v.duration>0)return v.duration;
  // WebM grabado con MediaRecorder no declara duración: forzar el cálculo buscando al final.
  const wait=once(v,'durationchange',null,8000).catch(()=>{});v.currentTime=1e7;await wait;const d=v.duration;v.currentTime=0;await once(v,'seeked',null,5000).catch(()=>{});
  return Number.isFinite(d)&&d>0?d:0;
 }
 async function onVideoFile(){
  const file=$('videoFile').files?.[0];if(!file)return;
  if(!/^video\//i.test(file.type||'')&&!/\.(mp4|m4v|webm|mov|3gp)$/i.test(file.name)){videoStatus('El archivo elegido no es un vídeo (MP4 o WebM).','error');$('videoFile').value='';return;}
  if(file.size>MAX_VIDEO_INPUT){videoStatus('El vídeo supera 2 GB. Recórtalo o expórtalo a menor calidad antes de usarlo.','error');$('videoFile').value='';return;}
  setMediaBusy(true);discardVideoResult();releaseVideoSource();resetCaptions();
  try{
   videoStatus('Abriendo el vídeo…');const v=$('videoSource');V.file=file;V.url=URL.createObjectURL(file);v.src=V.url;v.load();
   try{await once(v,'loadeddata','error',25000);}catch{throw new Error('Este vídeo no se puede reproducir. Usa MP4 (H.264) o WebM.');}
   const d=await readDuration(v);if(!d)throw new Error('No se ha podido leer la duración del vídeo.');
   if(d>MAX_VIDEO_SECONDS)throw new Error(`El vídeo dura ${fmt(d)}. El editor admite hasta ${fmt(MAX_VIDEO_SECONDS)}.`);
   V.duration=d;V.ready=true;$('videoFileName').textContent=`${file.name.slice(0,60)} · ${fmt(d)} · ${v.videoWidth}×${v.videoHeight}`;
   $('videoSeek').max=String(d);evenSplit();
   if(packReady())await ensureVideoBackgrounds();
   await ensureVideoEngine();await seekTo(0);
   if(!$('videoStatus').dataset.kind||$('videoStatus').dataset.kind!=='warning')videoStatus('Vídeo listo. Ajusta cuándo entra cada imagen y pulsa «Exportar vídeo».','success');
  }catch(err){releaseVideoSource();$('videoFileName').textContent='Ningún vídeo seleccionado';videoStatus(err.message,'error');}
  finally{$('videoFile').value='';setMediaBusy(false);drawVideoFrame(true);}
 }
 async function togglePlay(){
  if(!V.ready||media.busy)return;const v=$('videoSource');
  if(!v.paused){v.pause();return;}
  const audio=ensureAudioGraph();if(audio){audio.monitor.gain.value=1;try{await audio.ctx.resume();}catch{}}
  if($('videoCutout').checked&&!V.engine){setMediaBusy(true);await ensureVideoEngine();setMediaBusy(false);}
  if(v.ended||v.currentTime>=V.duration-0.05)await seekTo(0);
  try{await v.play();}catch(err){videoStatus('No se puede reproducir el vídeo: '+err.message,'error');}
 }
 // Respaldo (navegador sin WebCodecs): graba el canvas en tiempo real con MediaRecorder.
 async function exportVideoRealtime(){
  if(!V.ready||media.busy||state.busy)return;
  if(!packReady()){videoStatus('Genera primero el pack: se necesitan las seis imágenes.','error');return;}
  const canvas=$('videoCanvas');
  if(typeof MediaRecorder==='undefined'||typeof canvas.captureStream!=='function'){videoStatus('Este navegador no permite grabar vídeo (MediaRecorder). Actualiza Microsoft Edge.','error');return;}
  const mime=PulseMedia.pickRecorderMime();if(!mime){videoStatus('El navegador no ofrece ningún formato de grabación compatible (MP4/WebM).','error');return;}
  const [w,h,bits]=$('videoQuality').value==='1080'?[1080,1920,8e6]:[720,1280,5e6];
  const v=$('videoSource');let tracks=[],dest=null;
  // El audio se prepara antes de cualquier espera para conservar el gesto del usuario (política de autoplay).
  const audio=ensureAudioGraph();const resumed=audio?audio.ctx.resume().catch(()=>{}):null;
  setMediaBusy(true);discardVideoResult();V.cancelled=false;$('videoCancelBtn').classList.remove('hidden');$('videoCancelBtn').disabled=false;
  try{
   v.pause();stopLoop();await ensureVideoBackgrounds();await ensureVideoEngine();
   if(audio){await resumed;audio.monitor.gain.value=0;dest=audio.ctx.createMediaStreamDestination();audio.source.connect(dest);}
   renderer().resize(w,h);renderer().resetMask();await seekTo(0);
   const canvasStream=canvas.captureStream(30);tracks=[...canvasStream.getVideoTracks(),...(dest?dest.stream.getAudioTracks():[])];
   const recorder=new MediaRecorder(new MediaStream(tracks),{mimeType:mime,videoBitsPerSecond:bits,audioBitsPerSecond:128000});
   const chunks=[];recorder.ondataavailable=e=>{if(e.data&&e.data.size)chunks.push(e.data);};
   const stopped=new Promise((resolve,reject)=>{recorder.onstop=resolve;recorder.onerror=e=>reject(e.error||new Error('Error del grabador de vídeo.'));});
   const finished=new Promise(resolve=>{V.cancel=resolve;v.addEventListener('ended',resolve,{once:true});});
   V.recorder=recorder;
   videoStatus(`Grabando en tiempo real (${fmt(V.duration)})… Mantén Pulse Studio abierto hasta que termine.`);
   // La grabación empieza cuando el vídeo realmente se reproduce: sin segundos congelados al inicio.
   const playing=once(v,'playing',null,15000).catch(()=>{});startLoop();const play=v.play();await playing;await play;
   recorder.start(1000);const startedAt=performance.now(),framesAtStart=renderer().frames;
   await finished;
   await new Promise(r=>setTimeout(r,80));if(recorder.state!=='inactive')recorder.stop();await stopped;
   const fps=(renderer().frames-framesAtStart)/Math.max(0.5,(performance.now()-startedAt)/1000);
   if(V.cancelled){chunks.length=0;videoStatus(V.cancelReason||'Exportación cancelada.','warning');return;}
   const type=mime.split(';')[0];let blob=new Blob(chunks,{type});chunks.length=0;
   if(type==='video/webm')blob=await PulseMedia.fixWebmDuration(blob,performance.now()-startedAt);
   if(blob.size<2000)throw new Error('El vídeo exportado está vacío.');
   V.result=blob;V.resultExt=type==='video/mp4'?'mp4':'webm';V.resultUrl=URL.createObjectURL(blob);
   $('videoResultPlayer').src=V.resultUrl;$('videoResult').classList.remove('hidden');
   $('videoResultInfo').textContent=`${V.resultExt.toUpperCase()} · ${w}×${h} · ${fmt(V.duration)} · ${(blob.size/1048576).toFixed(1)} MB · ~${Math.round(fps)} fps · recorte ${useCutout()?'activado':'desactivado'}`;
   $('videoProgress').style.width='100%';
   if(fps<15)videoStatus(`Vídeo exportado, pero el equipo solo ha procesado ~${Math.round(fps)} fotogramas por segundo y puede verse a saltos. Prueba con 720p, cierra otros programas o desactiva el recorte.`,'warning');
   else videoStatus('Vídeo exportado. Revísalo y guárdalo.','success');
  }catch(err){videoStatus('Error al exportar el vídeo: '+(err.message||err),'error');$('videoProgress').style.width='0';}
  finally{
   stopLoop();v.pause();tracks.forEach(t=>t.stop());if(dest&&V.audio){try{V.audio.source.disconnect(dest);}catch{}}if(V.audio)V.audio.monitor.gain.value=1;
   V.recorder=null;V.cancel=null;V.cancelReason='';$('videoCancelBtn').classList.add('hidden');
   renderer().resize(720,1280);setMediaBusy(false);await seekTo(0);
  }
 }
 function cancelExport(reason){if(!V.recorder&&!V.exporting)return;V.cancelled=true;V.cancelReason=reason||'';$('videoSource').pause();V.cancel?.();}

 /* Exportación OFFLINE fotograma a fotograma (v1.6.2). No depende de la velocidad del equipo:
    Mediabunny decodifica el vídeo original con WebCodecs a 30 fps exactos, cada fotograma se compone
    (fondo + sujeto recortado) sin prisa y se codifica en H.264 con su marca de tiempo exacta; el audio
    original se copia sin recodificar. Resultado: MP4 fluido a 30 fps aunque el equipo procese pocos fps. */
 const FPS=30;let mediabunny=null;
 function loadMediabunny(){if(!mediabunny)mediabunny=import(new URL('vendor/mediabunny/mediabunny.js',location.href).href).catch(err=>{mediabunny=null;throw err;});return mediabunny;}
 const offlineCapable=()=>typeof VideoEncoder==='function'&&typeof VideoDecoder==='function'&&!!V.file;
 function fallbackError(msg){const e=new Error(msg);e.fallback=true;return e;}
 let wakeLock=null;
 // Evita que el equipo entre en reposo durante una exportación larga (Screen Wake Lock API de Chromium).
 async function keepScreenOn(on){
  try{if(on&&navigator.wakeLock)wakeLock=await navigator.wakeLock.request('screen');else if(!on&&wakeLock){await wakeLock.release();wakeLock=null;}}catch{}
 }
 /* Destino de escritura por trozos (1.6.4). BufferTarget reservaba UN bloque contiguo del tamaño del vídeo y lo
    duplicaba al crecer: en móviles reales (WebView de 32 bits / memoria limitada) fallaba con «Array buffer
    allocation failed» al cerrar el MP4. Aquí cada trozo se guarda como Blob (el navegador lo saca de la memoria de
    la página y lo pasa a disco si hace falta) y las reescrituras del final (cabecera mdat) se aplican cortando Blobs. */
 function createBlobWriter(){
  let parts=[],size=0; // [{pos,len,blob}] ordenados y sin solaparse
  function put(pos,data){
   const end=pos+data.byteLength;
   if(pos>size){parts.push({pos:size,len:pos-size,blob:new Blob([new Uint8Array(pos-size)])});size=pos;}
   if(pos===size){parts.push({pos,len:data.byteLength,blob:new Blob([data])});size=end;return;}
   const next=[];
   for(const p of parts){
    const pEnd=p.pos+p.len;
    if(pEnd<=pos||p.pos>=end){next.push(p);continue;}
    if(p.pos<pos)next.push({pos:p.pos,len:pos-p.pos,blob:p.blob.slice(0,pos-p.pos)});
    if(pEnd>end)next.push({pos:end,len:pEnd-end,blob:p.blob.slice(end-p.pos)});
   }
   next.push({pos,len:data.byteLength,blob:new Blob([data])});
   next.sort((a,b)=>a.pos-b.pos);parts=next;size=Math.max(size,end);
  }
  return {
   stream:new WritableStream({write(chunk){put(chunk.position,chunk.data);}}),
   toBlob(type){const blob=new Blob(parts.map(p=>p.blob),{type});parts=[];return blob;},
   get size(){return size;}
  };
 }
 const fmtEta=s=>s>=60?`${Math.floor(s/60)} min ${Math.round(s%60)} s`:`${Math.max(1,Math.round(s))} s`;
 async function exportVideoOffline(){
  let MB;try{MB=await loadMediabunny();}catch{throw fallbackError('No se encuentra el módulo de exportación (vendor/mediabunny).');}
  const [W,H,bits]=$('videoQuality').value==='1080'?[1080,1920,8e6]:[720,1280,5e6];
  const v=$('videoSource');let input=null,output=null,audioTask=null;
  setMediaBusy(true);discardVideoResult();V.cancelled=false;V.exporting=true;$('videoCancelBtn').classList.remove('hidden');$('videoCancelBtn').disabled=false;
  const started=performance.now();keepScreenOn(true);
  try{
   v.pause();stopLoop();await ensureVideoBackgrounds();await ensureVideoEngine();
   videoStatus('Preparando la exportación…');
   input=new MB.Input({formats:MB.ALL_FORMATS,source:new MB.BlobSource(V.file)});
   const vt=await input.getPrimaryVideoTrack();
   if(!vt)throw fallbackError('El archivo no tiene pista de vídeo legible.');
   if(!(await vt.canDecode()))throw fallbackError('Este navegador no puede decodificar el vídeo con WebCodecs.');
   const at=await input.getPrimaryAudioTrack();
   // Índice (moov) al final: es el modo que menos memoria usa; el archivo se escribe secuencialmente por trozos.
   const format=new MB.Mp4OutputFormat({fastStart:false});
   const videoCodecs=format.getSupportedVideoCodecs();
   const codec=await MB.getFirstEncodableVideoCodec(['avc','hevc','vp9','av1'].filter(c=>videoCodecs.includes(c)),{width:W,height:H,bitrate:bits});
   if(!codec)throw fallbackError('Este navegador no ofrece un codificador de vídeo WebCodecs compatible.');
   const writer=createBlobWriter();
   output=new MB.Output({format,target:new MB.StreamTarget(writer.stream,{chunked:true,chunkSize:4*1024*1024})});
   const canvas=$('videoCanvas');renderer().resize(W,H);renderer().resetMask();
   const videoSource=new MB.CanvasSource(canvas,{codec,quality:new MB.Quality({bitrate:bits}),keyFrameInterval:2});
   output.addVideoTrack(videoSource,{frameRate:FPS});
   const duration=V.duration,total=Math.max(1,Math.round(duration*FPS));
   // Audio: copia directa de los paquetes originales (sin pérdida). Si el códec no cabe en MP4, se recodifica.
   let audioNote='sin audio';
   if(at){
    const audioCodecs=format.getSupportedAudioCodecs();
    if(at.codec&&audioCodecs.includes(at.codec)){
     const audioSource=new MB.EncodedAudioPacketSource(at.codec);output.addAudioTrack(audioSource);audioNote=`audio original (${String(at.codec).toUpperCase()})`;
     audioTask=async()=>{const sink=new MB.EncodedPacketSink(at),config=await at.getDecoderConfig();let first=true;
      for await(const packet of sink.packets()){if(V.cancelled)break;if(packet.timestamp>=duration)break;await audioSource.add(packet,first?{decoderConfig:config}:undefined);first=false;}audioSource.close();};
    }else if(await at.canDecode()){
     const target=await MB.getFirstEncodableAudioCodec(['aac','opus'].filter(c=>audioCodecs.includes(c)));
     if(target){
      const audioSource=new MB.AudioSampleSource({codec:target,quality:new MB.Quality({bitrate:128000})});output.addAudioTrack(audioSource);audioNote=`audio ${target.toUpperCase()}`;
      audioTask=async()=>{const sink=new MB.AudioSampleSink(at);for await(const sample of sink.samples(0,duration)){if(V.cancelled){sample.close();break;}await audioSource.add(sample);sample.close();}audioSource.close();};
     }
    }
   }
   await output.start();
   const audioRun=audioTask?audioTask():Promise.resolve();audioRun.catch(()=>{});
   const dw=vt.displayWidth,dh=vt.displayHeight;
   const sink=new MB.CanvasSink(vt,dh>=dw?{height:Math.min(dh,H),poolSize:2}:{width:Math.min(dw,H),poolSize:2});
   const stamps=function*(){for(let i=0;i<total;i++)yield i/FPS;};
   const scene=sceneOptions(),segment=useCutout()?PulseMedia.segmentFrame:null;
   let i=0,frame=null,lastUi=0;
   for await(const wrapped of sink.canvasesAtTimestamps(stamps())){
    if(V.cancelled)break;
    const t=i/FPS;if(wrapped)frame=wrapped.canvas;
    renderer().draw({...scene,frame,duration,time:t,segmentFrame:segment});
    await videoSource.add(t,1/FPS);i++;
    const now=performance.now();
    if(now-lastUi>250||i===total){
     lastUi=now;const speed=i/((now-started)/1000),pct=i/total*100;
     $('videoProgress').style.width=`${pct.toFixed(1)}%`;$('videoSeek').value=String(t);$('videoTime').textContent=`${fmt(t)} / ${fmt(duration)}`;$('timelineHead').style.left=`${Math.min(100,pct)}%`;
     videoStatus(`Exportando fotograma ${i} de ${total} (${Math.floor(pct)} %) · ${speed.toFixed(1)} fps de proceso · quedan ~${fmtEta((total-i)/Math.max(0.1,speed))}. El resultado será fluido a ${FPS} fps.`);
     await new Promise(r=>setTimeout(r,0)); // deja respirar a la interfaz
    }
   }
   if(V.cancelled){await output.cancel().catch(()=>{});output=null;videoStatus(V.cancelReason||'Exportación cancelada.','warning');$('videoProgress').style.width='0';return;}
   if(i===0)throw new Error('No se ha podido leer ningún fotograma del vídeo.');
   videoSource.close();await audioRun;videoStatus('Cerrando el archivo MP4…');await output.finalize();
   const blob=writer.toBlob('video/mp4');output=null;
   if(blob.size<2000)throw new Error('El vídeo exportado está vacío.');
   V.result=blob;V.resultExt='mp4';V.resultUrl=URL.createObjectURL(blob);
   $('videoResultPlayer').src=V.resultUrl;$('videoResult').classList.remove('hidden');
   const secs=(performance.now()-started)/1000,label={avc:'H.264',hevc:'H.265',vp9:'VP9',av1:'AV1'}[codec]||codec;
   $('videoResultInfo').textContent=`MP4 · ${label} · ${W}×${H} · ${FPS} fps · ${fmt(duration)} · ${(blob.size/1048576).toFixed(1)} MB · ${audioNote} · recorte ${segment?'activado':'desactivado'}${scene.captions?` · ${scene.captions.length} subtítulos`:''} · exportado en ${fmtEta(secs)}`;
   $('videoProgress').style.width='100%';videoStatus('Vídeo exportado fotograma a fotograma. Revísalo y guárdalo.','success');
  }catch(err){
   if(output){try{await output.cancel();}catch{}}
   if(err.fallback)throw err;
   videoStatus('Error al exportar el vídeo: '+(err.message||err),'error');$('videoProgress').style.width='0';
  }finally{
   try{input?.dispose?.();}catch{}
   keepScreenOn(false);V.exporting=false;V.cancel=null;V.cancelReason='';$('videoCancelBtn').classList.add('hidden');
   renderer().resize(720,1280);renderer().resetMask();setMediaBusy(false);await seekTo(0);
  }
 }
 /* ── Subtítulos automáticos: audio del propio vídeo → Groq Whisper (clave del usuario) → palabras con tiempo ── */
 function captionStatus(msg,kind='info'){status('captionStatus',msg,kind);}
 function resetCaptions(){V.words=[];V.captions=[];$('captionOn').checked=false;renderCaptionList();syncCaptionControls();}
 function syncCaptionControls(){
  const has=V.captions.length>0,busy=media.busy;
  $('captionGenBtn').disabled=busy||!V.ready;$('captionOn').disabled=busy||!has;
  $('captionGenBtn').textContent=has?'↻ Volver a generar subtítulos':'✦ Generar subtítulos';
 }
 function renderCaptionList(){
  const list=$('captionList');list.replaceChildren();$('captionCount').textContent=String(V.captions.length);
  V.captions.forEach((c,i)=>{
   const row=document.createElement('div');row.className='cap-row';
   const time=document.createElement('button');time.type='button';time.className='cap-time';time.textContent=fmtPrecise(c.start);time.setAttribute('aria-label',`Ir al subtítulo ${i+1}`);
   time.addEventListener('click',()=>{$('videoSource').pause();seekTo(c.start+0.01);});
   const input=document.createElement('input');input.value=c.text;input.maxLength=80;input.setAttribute('aria-label',`Texto del subtítulo ${i+1}`);
   input.addEventListener('input',()=>{c.text=input.value;drawVideoFrame(true);});
   row.append(time,input);list.append(row);
  });
 }
 async function extractAudio(){
  let MB;try{MB=await loadMediabunny();}catch{throw new Error('No se encuentra el módulo de vídeo (vendor/mediabunny).');}
  const input=new MB.Input({formats:MB.ALL_FORMATS,source:new MB.BlobSource(V.file)});
  try{
   const at=await input.getPrimaryAudioTrack();if(!at)throw new Error('El vídeo no tiene pista de audio.');
   const codec=at.codec;let format,type,name,source,feed;
   // Copia directa del audio (sin recodificar) en un contenedor que Groq acepta; si no, se recodifica a Opus.
   if(codec==='aac'){format=new MB.Mp4OutputFormat({fastStart:false});type='audio/mp4';name='audio.m4a';}
   else if(codec==='opus'||codec==='vorbis'){format=new MB.WebMOutputFormat();type='audio/webm';name='audio.webm';}
   else if(codec==='mp3'){format=new MB.Mp3OutputFormat();type='audio/mpeg';name='audio.mp3';}
   if(format){
    source=new MB.EncodedAudioPacketSource(codec);
    feed=async()=>{const sink=new MB.EncodedPacketSink(at),config=await at.getDecoderConfig();let first=true;for await(const packet of sink.packets()){await source.add(packet,first?{decoderConfig:config}:undefined);first=false;}};
   }else{
    if(!(await at.canDecode()))throw new Error('No se puede leer el audio de este vídeo.');
    format=new MB.WebMOutputFormat();type='audio/webm';name='audio.webm';
    source=new MB.AudioSampleSource({codec:'opus',quality:new MB.Quality({bitrate:64000})});
    feed=async()=>{const sink=new MB.AudioSampleSink(at);for await(const sample of sink.samples()){await source.add(sample);sample.close();}};
   }
   const writer=createBlobWriter();
   const output=new MB.Output({format,target:new MB.StreamTarget(writer.stream,{chunked:true,chunkSize:4*1024*1024})});output.addAudioTrack(source);await output.start();
   await feed();source.close();await output.finalize();
   return {blob:writer.toBlob(type),name};
  }finally{try{input.dispose?.();}catch{}}
 }
 /* POST /api/transcribe: el audio (ya extraído en el equipo) va al backend, que llama a Groq Whisper. */
 async function transcribe(blob,name,_key,lang){
  const n=current(),hint=cleanString([n?.title,String(state.script||'').replace(/\[[^\]]*\]/g,' ')].filter(Boolean).join('. ').replace(/\s+/g,' '),500);
  const data=await PulseApi.upload('/api/transcribe?'+new URLSearchParams({name,lang:lang||'',context:hint}),blob,{timeout:300000});
  if(!data.words?.length)throw new Error('No se ha reconocido voz en el vídeo.');
  return data.words;
 }
 async function generateCaptions(){
  if(!V.ready||media.busy||state.busy)return;await settingsReady;
  const key='';if(!configured('groq')){captionStatus('Añade tu clave de Groq en Configuración: se usa también para transcribir.','error');openSettings();return;}
  setMediaBusy(true);
  try{
   captionStatus('1/2 · Extrayendo el audio del vídeo en tu equipo…');
   const {blob,name}=await extractAudio();
   if(blob.size>24*1024*1024)throw new Error('El audio extraído supera 24 MB, el límite de Groq en el plan gratuito. Usa un vídeo más corto.');
   captionStatus(`2/2 · Groq (Whisper) está transcribiendo ${(blob.size/1048576).toFixed(1)} MB de audio…`);
   V.words=await transcribe(blob,name,key,$('captionLang').value);
   V.captions=PulseMedia.buildCaptions(V.words,Number($('captionWords').value));
   $('captionOn').checked=true;renderCaptionList();
   captionStatus(`${V.captions.length} subtítulos generados. Ajusta el estilo y revisa los textos antes de exportar.`,'success');
  }catch(err){captionStatus('Subtítulos: '+(err.message||err),'error');}
  finally{setMediaBusy(false);syncCaptionControls();drawVideoFrame(true);}
 }
 function setCaptionColor(color){V.captionColor=color;document.querySelectorAll('#captionSwatches .swatch').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.color===color)));drawVideoFrame(true);}
 $('captionGenBtn').addEventListener('click',generateCaptions);
 $('captionOn').addEventListener('change',()=>drawVideoFrame(true));
 for(const id of ['captionFont','captionStyle','captionUpper'])$(id).addEventListener('change',()=>drawVideoFrame(true));
 for(const id of ['captionSize','captionY'])$(id).addEventListener('input',()=>{$(id+'Out').textContent=$(id).value+' %';drawVideoFrame(true);});
 $('captionWords').addEventListener('input',()=>{$('captionWordsOut').textContent=$('captionWords').value;if(!V.words.length)return;V.captions=PulseMedia.buildCaptions(V.words,Number($('captionWords').value));renderCaptionList();drawVideoFrame(true);});
 document.querySelectorAll('#captionSwatches .swatch').forEach(b=>b.addEventListener('click',()=>setCaptionColor(b.dataset.color)));
 $('captionColorCustom').addEventListener('input',()=>setCaptionColor($('captionColorCustom').value));

 async function exportVideo(){
  if(!V.ready||media.busy||state.busy)return;
  if(!packReady()){videoStatus('Genera primero el pack: se necesitan las seis imágenes.','error');return;}
  if(offlineCapable()){
   try{await exportVideoOffline();return;}
   catch(err){if(!err.fallback){videoStatus('Error al exportar el vídeo: '+(err.message||err),'error');return;}
    videoStatus(`${err.message} Se usará la grabación en tiempo real.`,'warning');await new Promise(r=>setTimeout(r,1200));}
  }
  return exportVideoRealtime();
 }
 async function saveVideo(){
  if(!V.result||media.busy)return;setMediaBusy(true);
  try{videoStatus(`Guardando el vídeo (${(V.result.size/1048576).toFixed(1)} MB)…`);mediaSaved('videoStatus',await PulsePlatform.saveBlob(V.result,`pulse_reel_${stampNow()}.${V.resultExt}`,{folder:packFolder()}),'Vídeo guardado en');}
  catch(err){videoStatus('Error al guardar el vídeo: '+err.message,'error');}
  finally{setMediaBusy(false);}
 }
 async function enterVideoStep(){
  syncVideoControls();
  if(!packReady()){videoStatus('Genera primero el pack (paso 2): el editor usa las seis imágenes como fondo.','warning');return;}
  if(!$('timelineRows').children.length||V.bgKey!==state.images.map(im=>im.preview).join('|'))buildTimeline();
  try{await ensureVideoBackgrounds();}catch(err){videoStatus('No se han podido preparar las imágenes del pack: '+err.message,'error');}
  drawVideoFrame(true);
 }
 // Liberación de memoria cuando nada está en uso.
 PulseMedia.onTrim(()=>{if(media.busy)return;V.engine=false;V.renderer?.releaseScratch();if(wizardStep!==5)releaseVideoBackgrounds();if(V.ready&&!$('videoSource').paused)$('videoSource').pause();});
 document.addEventListener('visibilitychange',()=>{if(!document.hidden)return;if(V.recorder||V.exporting)cancelExport('La ventana se ha minimizado u ocultado y la exportación se ha cancelado. Mantén Pulse Studio visible hasta que termine.');else if(V.ready)$('videoSource').pause();});
 window.addEventListener('pagehide',()=>{releaseVideoSource();discardVideoResult();releaseVideoBackgrounds();closeBitmap(media.thumb.photo);closeBitmap(media.thumb.aiBg);closeBitmap(media.thumb.packBg);releasePerson(media.thumb.person);if(V.audio)V.audio.ctx.close().catch(()=>{});});

 // Eventos de los pasos 4 y 5.
 $('toThumbBtn').addEventListener('click',()=>setWizardStep(4));$('toVideoFromPackBtn').addEventListener('click',()=>setWizardStep(5));
 $('backToPackBtn').addEventListener('click',()=>setWizardStep(3));$('toVideoBtn').addEventListener('click',()=>setWizardStep(5));$('backToThumbBtn').addEventListener('click',()=>setWizardStep(4));
 $('thumbPhoto').addEventListener('change',onThumbPhoto);$('thumbCreateBtn').addEventListener('click',createThumbnail);$('thumbBgBtn').addEventListener('click',regenerateBackground);
 $('thumbBgSource').addEventListener('change',async()=>{const src=$('thumbBgSource').value;try{await ensurePackBackground(src);}catch(err){thumbStatus(err.message,'error');}renderThumb();});
 $('thumbHeadline').addEventListener('input',()=>{media.thumb.headlineTouched=true;renderThumb();});$('thumbHighlight').addEventListener('input',()=>{media.thumb.headlineTouched=true;renderThumb();});
 for(const id of ['thumbScale','thumbX','thumbDim'])$(id).addEventListener('input',renderThumb);
 $('thumbJpgBtn').addEventListener('click',()=>saveThumb('jpg'));$('thumbPngBtn').addEventListener('click',()=>saveThumb('png'));
 $('videoFile').addEventListener('change',onVideoFile);$('videoPlayBtn').addEventListener('click',togglePlay);
 $('videoSeek').addEventListener('input',()=>{const v=$('videoSource');v.pause();seekTo(Number($('videoSeek').value));});
 $('videoEvenBtn').addEventListener('click',evenSplit);$('videoExportBtn').addEventListener('click',exportVideo);$('videoCancelBtn').addEventListener('click',()=>cancelExport('Exportación cancelada.'));$('videoSaveBtn').addEventListener('click',saveVideo);
 $('videoCutout').addEventListener('change',async()=>{renderer().resetMask();if($('videoCutout').checked&&V.ready){setMediaBusy(true);await ensureVideoEngine();setMediaBusy(false);}else V.engine=false;drawVideoFrame(true);});
 for(const id of ['videoScale','videoX'])$(id).addEventListener('input',()=>{$(id+'Out').textContent=id==='videoScale'?$(id).value+' %':$(id).value;drawVideoFrame(true);});
 $('videoSource').addEventListener('play',()=>{startLoop();syncVideoControls();});
 $('videoSource').addEventListener('pause',()=>{if(!V.recorder)stopLoop();syncVideoControls();drawVideoFrame(true);});
 $('videoSource').addEventListener('ended',()=>{if(!V.recorder){stopLoop();syncVideoControls();}});
 $('hfTestBtn').addEventListener('click',testHf);$('hfKey').addEventListener('input',syncServiceStates);
 syncVideoControls();syncCaptionControls();
 /* ── Configuración: GET/PUT /api/settings (las claves se cifran en el backend y nunca vuelven a la interfaz) ── */
 function applySettings(info){
  settingsInfo=info;
  const placeholders={groq:'gsk_…',pexels:'Clave personal de Pexels',hf:'hf_…'};
  for(const name of ['groq','pexels','hf']){
   const el=$(keyInputs[name]),s=info.services[name];el.value='';
   el.placeholder=s.configured?`Guardada ${s.hint||''} · escribe otra para sustituirla`.replace('  ',' '):placeholders[name];
  }
  if(info.groqModel)$('modelName').value=info.groqModel;
  if(info.hfModel)$('hfModel').value=info.hfModel;
  $('outputDir').value=info.outputDir||'';
  syncServiceStates();
 }
 const settingsReady=(async()=>{
  $('saveKeysBtn').disabled=true;$('clearKeysBtn').disabled=true;
  try{
   applySettings(await PulseApi.get('/api/settings',{timeout:20000}));
   const any=['groq','pexels','hf'].some(configured);
   status('keyStatus',any?'Claves cargadas del almacén cifrado de este equipo. Puedes probar cada servicio.':'Introduce tus claves y pulsa «Guardar cambios» para conservarlas cifradas en este equipo.',any?'success':'info');
   if(!configured('groq')||!configured('pexels'))openSettings();
  }catch(err){status('keyStatus',err.message,'error');openSettings();}
  finally{$('saveKeysBtn').disabled=false;$('clearKeysBtn').disabled=false;}
 })();
 $('saveKeysBtn').addEventListener('click',async()=>{
  await settingsReady;if(state.busy)return;$('saveKeysBtn').disabled=true;$('clearKeysBtn').disabled=true;
  try{
   const keys={};for(const name of ['groq','pexels','hf']){const v=$(keyInputs[name]).value.trim();if(v)keys[name]=v;}
   applySettings(await PulseApi.put('/api/settings',{keys,groqModel:$('modelName').value,hfModel:$('hfModel').value}));
   status('keyStatus','Configuración guardada. Las claves se han cifrado en este equipo.','success');
  }catch(err){status('keyStatus',err.message,'error');}
  finally{$('saveKeysBtn').disabled=state.busy;$('clearKeysBtn').disabled=state.busy;}
 });
 $('clearKeysBtn').addEventListener('click',async()=>{
  await settingsReady;if(state.busy)return;$('saveKeysBtn').disabled=true;$('clearKeysBtn').disabled=true;
  try{applySettings(await PulseApi.del('/api/settings/keys'));status('keyStatus','Claves eliminadas de este equipo.','success');}
  catch(err){status('keyStatus',err.message,'error');}
  finally{$('saveKeysBtn').disabled=state.busy;$('clearKeysBtn').disabled=state.busy;}
 });
 // Carpeta de salida: selector nativo (backend) y acceso directo en el Explorador.
 $('chooseOutputBtn').addEventListener('click',async()=>{
  $('chooseOutputBtn').disabled=true;
  try{applySettings(await PulseApi.post('/api/settings/choose-folder',{},{timeout:600000}));status('keyStatus','Carpeta de salida actualizada.','success');}
  catch(err){status('keyStatus',err.message,'error');}finally{$('chooseOutputBtn').disabled=false;}
 });
 $('openOutputBtn').addEventListener('click',()=>PulseApi.post('/api/system/reveal',{path:$('outputDir').value}).catch(err=>status('keyStatus',err.message,'error')));
 window.addEventListener('pagehide',()=>state.images.forEach(im=>URL.revokeObjectURL(im.preview)));
})();
