// Prueba extremo a extremo de Pulse Studio Desktop:
//   servicios externos simulados (Node) ← backend Java REAL (--no-window) ← interfaz en Chromium REAL (Playwright).
// Recorre los 5 pasos: configuración → noticias → pack → miniatura (MediaPipe) → vídeo (reordenar, subtítulos, exportar).
// Uso: node tools/e2e/desktop.e2e.cjs  (requiere build/classes compilado, playwright y tests/fixtures/*)
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const ROOT = path.resolve(__dirname, '../..');
const FIX = process.env.PULSE_FIXTURES || path.join(ROOT, 'tools/e2e/fixtures');
const OUT = process.env.PULSE_E2E_OUT || path.join(ROOT, 'build/e2e');
const TOKEN = 'e2e-token-0123456789abcdef';
const api = {groq: [], whisper: [], hf: [], pexels: 0, jina: 0, hn: 0, rss: 0, auth: new Set()};

const now = Math.floor(Date.now() / 1000);
const rssXml = key => `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>${key}</title>
<item><title>Nuevo ransomware ataca hospitales (${key})</title><link>https://example.com/${key}/ransomware-hospitales?utm_source=x</link>
<pubDate>${new Date((now - 3600) * 1000).toUTCString()}</pubDate><description><![CDATA[<p>Un grupo de ransomware cifra sistemas.</p>]]></description></item>
<item><title>Receta de paella</title><link>https://example.com/${key}/paella</link><pubDate>${new Date((now - 7200) * 1000).toUTCString()}</pubDate></item>
</channel></rss>`;

function mock(req, res) {
  const u = new URL(req.url, 'http://x');
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    api.auth.add(req.headers.authorization || '');
    const json = (obj, status = 200) => { res.writeHead(status, {'content-type': 'application/json'}); res.end(JSON.stringify(obj)); };
    const p = u.pathname;
    if (p.startsWith('/hn/')) { api.hn++; return json({hits: [
      {objectID: '1', title: 'Nvidia presenta un chip de IA para centros de datos', url: 'https://example.com/nvidia-chip', created_at: new Date((now - 600) * 1000).toISOString(), story_text: ''},
      {objectID: '2', title: 'Nvidia presenta un chip de IA para centros de datos', url: 'https://example.com/nvidia-chip?utm_medium=feed', created_at: new Date((now - 900) * 1000).toISOString()}]}); }
    if (p.startsWith('/rss/')) { api.rss++; res.writeHead(200, {'content-type': 'application/rss+xml'}); return res.end(rssXml(u.searchParams.get('source'))); }
    if (p.startsWith('/jina/')) { api.jina++; res.writeHead(200, {'content-type': 'text/plain; charset=utf-8'}); return res.end('Title: Chip\n\nMarkdown Content:\n' + 'Nvidia presenta un chip de IA para centros de datos que reduce el consumo energético a la mitad y promete abaratar el entrenamiento de modelos. '.repeat(25)); }
    if (p === '/groq/openai/v1/models') return req.headers.authorization === 'Bearer gsk_test' ? json({data: [{id: 'openai/gpt-oss-20b'}]}) : json({error: {message: 'Invalid API Key'}}, 401);
    if (p === '/groq/openai/v1/audio/transcriptions') {
      api.whisper.push(body);
      const words = 'Hola a todos hoy os cuento la noticia del nuevo chip de IA.'.split(' ').map((word, i) => ({word, start: 0.2 + i * 0.3, end: 0.45 + i * 0.3}));
      return json({text: words.map(w => w.word).join(' '), words});
    }
    if (p === '/groq/openai/v1/chat/completions') {
      const b = JSON.parse(body.toString('utf8')); api.groq.push(b);
      const content = b.response_format.json_schema.name === 'miniatura_tiktok'
        ? {prompt_visual: 'Futuristic data center corridor with glowing turquoise server racks, cinematic lighting, empty lower center', titular: 'El chip que ahorra energía', destacado: 'chip'}
        : {gancho: '¿Y si tu IA gastara la mitad?', contexto: 'Nvidia ha presentado un chip nuevo.', desarrollo: 'Reduce el consumo a la mitad.', por_que_importa: 'Abarata la IA.', cta: '¿Lo usarías?', palabras_clave: ['data center', 'microchip', 'server room', 'ai chip', 'energy', 'technology']};
      return json({choices: [{finish_reason: 'stop', message: {content: JSON.stringify(content)}}]});
    }
    if (p === '/pexels/v1/search') { api.pexels++; const n = api.pexels; return json({photos: [{id: n, src: {large2x: `http://${req.headers.host}/img/${n}.jpg`}, photographer: 'Autora ' + n, photographer_url: 'https://www.pexels.com/@a', url: 'https://www.pexels.com/photo/' + n}]}); }
    if (p.startsWith('/img/')) { const n = ((parseInt(p.match(/(\d+)/)[1]) - 1) % 6) + 1; res.writeHead(200, {'content-type': 'image/jpeg'}); return res.end(fs.readFileSync(path.join(FIX, `pexels${n}.jpg`))); }
    if (p === '/hub/api/whoami-v2') return json({name: 'tester'});
    if (p.startsWith('/hub/api/models/')) return json({inferenceProviderMapping: {'fal-ai': {status: 'live', providerId: 'fal-ai/flux', task: 'text-to-image'}, nscale: {status: 'live', providerId: 'black-forest-labs/FLUX.1-schnell', task: 'text-to-image'}}});
    if (p.startsWith('/router/')) { api.hf.push({path: p, auth: req.headers.authorization, body: JSON.parse(body.toString('utf8'))}); return json({data: [{b64_json: fs.readFileSync(path.join(FIX, 'hfbg.jpg')).toString('base64')}]}); }
    res.writeHead(404); res.end('nf ' + p);
  });
}

const listen = server => new Promise(r => server.listen(0, '127.0.0.1', () => r(server.address().port)));
const seek = (page, t) => page.evaluate(v => { const r = document.getElementById('videoSeek'); r.value = String(v); r.dispatchEvent(new Event('input', {bubbles: true})); }, t);

(async () => {
  fs.rmSync(OUT, {recursive: true, force: true}); fs.mkdirSync(OUT, {recursive: true});
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'pulse-data-')), outDir = path.join(data, 'Salida');
  const mockServer = http.createServer(mock); const M = await listen(mockServer);
  const base = `http://127.0.0.1:${M}`;
  const props = {groq: '/groq', pexels: '/pexels', pexelsImages: '/img', jina: '/jina', hn: '/hn', rss: '/rss', hfRouter: '/router', hfHub: '/hub'};
  const port = 18000 + Math.floor(Math.random() * 2000);
  const java = spawn('java', ['-Djava.awt.headless=true', ...Object.entries(props).map(([k, v]) => `-Dpulse.endpoint.${k}=${base}${v}`),
    '-cp', path.join(ROOT, 'build/classes'), 'com.pulsestudio.desktop.PulseStudioApp', '--no-window', `--port=${port}`,
    `--web=${path.join(ROOT, 'src/main/resources/web')}`, `--data=${data}`, `--token=${TOKEN}`], {stdio: ['ignore', 'pipe', 'pipe']});
  let javaLog = ''; java.stdout.on('data', d => javaLog += d); java.stderr.on('data', d => javaLog += d);
  for (let i = 0; i < 100 && !/Pulse Studio: http/.test(javaLog); i++) await new Promise(r => setTimeout(r, 100));
  assert.match(javaLog, /Pulse Studio: http:\/\/127\.0\.0\.1:\d+/);

  const browser = await chromium.launch({args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required']});
  const errors = [];
  let page;
  try {
    page = await browser.newPage({viewport: {width: 1440, height: 900}});
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); if (process.env.PULSE_E2E_DEBUG) console.log('[page]', m.text()); });
    const $ = id => page.locator('#' + id);
    const shot = name => page.screenshot({path: path.join(OUT, name)});

    // Seguridad: sin token la API responde 401; con Origin ajeno, 403.
    const noToken = await fetch(`http://127.0.0.1:${port}/api/settings`); assert.equal(noToken.status, 401);
    const foreign = await fetch(`http://127.0.0.1:${port}/api/settings`, {headers: {'X-Pulse-Token': TOKEN, Origin: 'https://evil.example'}}); assert.equal(foreign.status, 403);

    await page.goto(`http://127.0.0.1:${port}/#t=${TOKEN}`);
    await page.waitForFunction(() => location.hash === '' && sessionStorage.getItem('pulseToken'));
    await shot('0-inicio.png');

    // Configuración: claves al backend (cifradas); la interfaz solo recibe «configurado» + pista.
    // Primera ejecución sin claves: la configuración se abre sola.
    await page.waitForFunction(() => !document.getElementById('settingsScreen').classList.contains('hidden'));
    await page.evaluate(dir => PulseApi.put('/api/settings', {outputDir: dir}), outDir);
    await $('groqKey').fill('gsk_test'); await $('pexelsKey').fill('pexels_test'); await $('hfKey').fill('hf_test');
    await $('saveKeysBtn').click();
    await page.waitForFunction(() => document.querySelector('[data-service-state="hf"]').textContent === 'Configurado', null, {timeout: 15000});
    await $('groqTestBtn').click();
    await page.waitForFunction(() => document.querySelector('[data-service-state="groq"]').textContent === 'Conectado', null, {timeout: 15000});
    await $('hfTestBtn').click();
    await page.waitForFunction(() => document.querySelector('[data-service-state="hf"]').textContent === 'Conectado', null, {timeout: 15000});
    const settings = await page.evaluate(() => PulseApi.get('/api/settings'));
    assert.equal(JSON.stringify(settings).includes('gsk_test'), false, 'la clave no debe volver a la interfaz');
    assert.equal(fs.readFileSync(path.join(data, 'credentials.aesgcm')).includes('gsk_test'), false, 'la clave debe estar cifrada');
    await page.reload(); await $('openSettingsBtn').click();
    await page.waitForFunction(() => document.querySelector('[data-service-state="groq"]').textContent === 'Configurado');
    assert.equal(await $('outputDir').inputValue(), outDir);
    await page.screenshot({path: path.join(OUT, '1-configuracion.png'), fullPage: true});
    await $('closeSettingsBtn').click();

    // Paso 1: búsqueda (Hacker News + RSS, deduplicado y filtrado por tema en el backend).
    await $('searchInput').fill('chip'); await $('searchBtn').click();
    await page.waitForFunction(() => document.querySelectorAll('#feedList .item').length > 0 && !document.getElementById('searchBtn').disabled, null, {timeout: 30000});
    const titles = await page.locator('#feedList .item .title').allTextContents();
    assert.equal(titles.filter(t => /Nvidia/.test(t)).length, 1, 'duplicados: ' + titles.join(' | '));
    assert.ok(!titles.some(t => /paella/.test(t)));
    await shot('2-paso1-noticias.png');
    await page.locator('#feedList .item', {hasText: 'Nvidia'}).click();

    // Paso 2 → 3: artículo (Jina) + guion (Groq) + 6 fotos (Pexels, recortadas a 1080×1920 en Java).
    await page.waitForFunction(() => !document.getElementById('stepPanel2').classList.contains('hidden'));
    await shot('3-paso2.png');
    await $('generateBtn').click();
    await page.waitForFunction(() => !document.getElementById('stepPanel3').classList.contains('hidden') || document.getElementById('genStatus').dataset.kind === 'error', null, {timeout: 90000});
    assert.equal(await page.locator('#pictureGrid img').count(), 6, await $('genStatus').textContent());
    assert.match(await $('scriptOutput').inputValue(), /¿Y si tu IA gastara la mitad\?/);
    assert.equal(api.jina, 1); assert.equal(api.pexels, 6); assert.ok(api.auth.has('Bearer gsk_test') && api.auth.has('pexels_test'));
    const size = await page.evaluate(() => new Promise(r => { const i = new Image(); i.onload = () => r([i.naturalWidth, i.naturalHeight]); i.src = document.querySelector('#pictureGrid img').src; }));
    assert.deepEqual(size, [1080, 1920]);
    await $('txtBtn').click();
    await page.waitForFunction(() => /guardad[oa] en/.test(document.getElementById('genStatus').textContent), null, {timeout: 15000});
    await $('imagesBtn').click();
    await page.waitForFunction(() => /guardad[oa] en/.test(document.getElementById('genStatus').textContent) && /imágenes|Imágenes/.test(document.getElementById('genStatus').textContent), null, {timeout: 30000});
    const packDir = fs.readdirSync(outDir).find(n => n.startsWith('PulseStudio_'));
    assert.ok(packDir, 'carpeta del pack');
    const packFiles = fs.readdirSync(path.join(outDir, packDir)).sort();
    assert.deepEqual(packFiles, ['guion_tiktok.txt', 'imagen_1.jpg', 'imagen_2.jpg', 'imagen_3.jpg', 'imagen_4.jpg', 'imagen_5.jpg', 'imagen_6.jpg']);
    assert.match(fs.readFileSync(path.join(outDir, packDir, 'guion_tiktok.txt'), 'utf8'), /TITULAR: Nvidia presenta/);
    await page.screenshot({path: path.join(OUT, '4-paso3-pack.png'), fullPage: true});

    // Paso 4: miniatura (Groq brief + HF por el backend; recorte MediaPipe en la interfaz).
    await $('toThumbBtn').click();
    await $('thumbPhoto').setInputFiles(path.join(FIX, 'foto.jpg'));
    await page.waitForFunction(() => ['success', 'error'].includes(document.getElementById('thumbStatus').dataset.kind), null, {timeout: 120000});
    await $('thumbCreateBtn').click();
    await page.waitForFunction(() => /Miniatura lista/.test(document.getElementById('thumbStatus').textContent) || document.getElementById('thumbStatus').dataset.kind === 'error', null, {timeout: 120000});
    assert.match(await $('thumbStatus').textContent(), /Miniatura lista/);
    assert.equal(await $('thumbHeadline').inputValue(), 'El chip que ahorra energía');
    assert.equal(api.hf.length, 1); assert.equal(api.hf[0].path, '/router/nscale/v1/images/generations'); assert.equal(api.hf[0].auth, 'Bearer hf_test');
    const probe = await page.evaluate(() => { const x = document.getElementById('thumbCanvas').getContext('2d'); return [x.getImageData(40, 1300, 1, 1).data.join(','), x.getImageData(540, 1500, 1, 1).data.join(',')]; });
    assert.notEqual(probe[0], probe[1]);
    await page.evaluate(() => scrollTo(0, document.getElementById('thumbSection').getBoundingClientRect().top + scrollY - 90));
    await shot('5-paso4-miniatura.png');
    await $('thumbJpgBtn').click();
    await page.waitForFunction(() => /guardad[oa] en/.test(document.getElementById('thumbStatus').textContent), null, {timeout: 20000});
    const thumbFile = fs.readdirSync(path.join(outDir, packDir)).find(n => n.startsWith('miniatura_tiktok_'));
    assert.equal(fs.readFileSync(path.join(outDir, packDir, thumbFile)).subarray(0, 2).toString('hex'), 'ffd8');

    // Paso 5: vídeo narrado, reordenar con el ratón, subtítulos (Whisper vía backend) y exportación offline.
    await $('toVideoBtn').click();
    await $('videoFile').setInputFiles(path.join(FIX, 'narrado.webm'));
    await page.waitForFunction(() => !document.getElementById('videoExportBtn').disabled, null, {timeout: 120000});
    const bgOf = () => page.evaluate(() => [...document.querySelectorAll('#timelineBar .tl-seg')].map(el => el.style.backgroundImage));
    const before = await bgOf(), segs = page.locator('#timelineBar .tl-seg');
    const a = await segs.nth(0).boundingBox(), b = await segs.nth(3).boundingBox();
    await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2); await page.mouse.down();
    await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, {steps: 10}); await page.mouse.up();
    assert.deepEqual(await bgOf(), [before[1], before[2], before[3], before[0], before[4], before[5]]);
    const after = await bgOf(); await segs.nth(2).click(); assert.deepEqual(await bgOf(), after);
    await $('captionGenBtn').click();
    await page.waitForFunction(() => ['success', 'error'].includes(document.getElementById('captionStatus').dataset.kind), null, {timeout: 60000});
    assert.match(await $('captionStatus').textContent(), /subtítulos generados/);
    const form = api.whisper[0].toString('latin1');
    assert.match(form, /whisper-large-v3-turbo/); assert.match(form, /name="language"\r\n\r\nes/);
    await page.locator('#captionSwatches .swatch[data-color="#ffe14d"]').click();
    await $('captionStyle').selectOption('box');
    await seek(page, 0.3); await page.waitForTimeout(500);
    await seek(page, 3.2); await page.waitForTimeout(600);
    await page.evaluate(() => scrollTo(0, document.getElementById('videoSection').getBoundingClientRect().top + scrollY - 90));
    await shot('6-paso5-editor.png');
    await page.screenshot({path: path.join(OUT, '6b-paso5-completo.png'), fullPage: true});
    await $('videoExportBtn').click();
    await page.waitForFunction(() => !document.getElementById('videoResult').classList.contains('hidden') || document.getElementById('videoStatus').dataset.kind === 'error', null, {timeout: 400000});
    assert.match(await $('videoStatus').textContent(), /Vídeo exportado/);
    assert.match(await $('videoResultInfo').textContent(), /^MP4 · .* · 720×1280 · 30 fps · /);
    await $('videoSaveBtn').click();
    await page.waitForFunction(() => /guardad[oa] en/.test(document.getElementById('videoStatus').textContent), null, {timeout: 60000});
    const reel = fs.readdirSync(path.join(outDir, packDir)).find(n => n.startsWith('pulse_reel_'));
    const bytes = fs.readFileSync(path.join(outDir, packDir, reel));
    assert.ok(bytes.length > 20000); assert.equal(bytes.subarray(4, 8).toString(), 'ftyp');
    fs.copyFileSync(path.join(outDir, packDir, reel), path.join(OUT, reel));
    await page.screenshot({path: path.join(OUT, '7-exportado.png'), fullPage: true});
    await page.evaluate(() => window.PulseMedia.trimMemory(20));
    assert.deepEqual(errors, []);
    console.log('E2E OK', {pack: packFiles.length, thumb: thumbFile, reel, bytes: bytes.length});
  } catch (e) {
    console.error(e);
    if (page) { console.error(await page.evaluate(() => ['keyStatus', 'searchStatus', 'genStatus', 'thumbStatus', 'captionStatus', 'videoStatus'].map(id => id + ': ' + document.getElementById(id).textContent).join('\n')).catch(() => '')); await page.screenshot({path: path.join(OUT, 'fallo.png'), fullPage: true}).catch(() => {}); }
    console.error('--- backend ---\n' + javaLog.slice(-3000)); console.error('page errors', errors); process.exitCode = 1;
  } finally {
    await browser.close(); java.kill(); mockServer.close();
  }
})();
