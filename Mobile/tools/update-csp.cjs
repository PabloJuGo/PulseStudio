// Run after changing any inline JavaScript in the single HTML UI.
// script-src = 'self' (solo los archivos locales de MediaPipe que MainActivity sirve por lista exacta)
//            + 'wasm-unsafe-eval' (compilar WebAssembly; NO habilita eval de JavaScript)
//            + los hashes SHA-256 de los tres scripts embebidos.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const file=path.join(__dirname,'../app/src/main/assets/www/index.html');
let html=fs.readFileSync(file,'utf8');
const hashes=[...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m=>"'sha256-"+crypto.createHash('sha256').update(m[1]).digest('base64')+"'");
if(hashes.length!==3)throw Error('Expected three embedded script blocks (pulse-bridge, pulse-media, pulse-app)');
html=html.replace(/script-src [^;]+;/,"script-src 'self' 'wasm-unsafe-eval' "+hashes.join(' ')+';');
fs.writeFileSync(file,html);console.log('Updated CSP hashes for three embedded scripts.');
