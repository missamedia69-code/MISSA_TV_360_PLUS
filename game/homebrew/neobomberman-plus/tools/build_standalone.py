#!/usr/bin/env python3
"""Build one self-contained playable HTML file with the original C game as WASM.

Requires Zig 0.13 (`ZIG=/path/to/zig python3 tools/build_standalone.py`).
No CDN, no server, no BIOS, no commercial ROM and no network calls at runtime.
"""
import base64
import os
from pathlib import Path
import subprocess
import tempfile

project = Path(__file__).resolve().parents[1]
web = project / 'web'
zig = os.environ.get('ZIG', 'zig')

with tempfile.TemporaryDirectory() as d:
    binary = Path(d) / 'bomber.wasm'
    subprocess.run([zig, 'cc', '-target', 'wasm32-wasi', '-mexec-model=reactor', '-O2',
                    '-Wl,--export=web_new', '-Wl,--export=web_tick',
                    '-Wl,--export=web_state', '-Wl,--export-memory',
                    str(project / 'bomber.c'), str(web / 'bridge.c'), '-o', str(binary)], check=True)
    wasm = base64.b64encode(binary.read_bytes()).decode('ascii')

html = (web / 'index.html').read_text()
css = (web / 'style.css').read_text()
css = '\n'.join(line for line in css.splitlines() if not line.startswith('@import'))
js = (web / 'game.js').read_text()
original_api = "async function api(endpoint,body){const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!response.ok)throw Error('La connexion au moteur de jeu a échoué.');return response.json();}"
wasm_api = '''let instance;
const decodeState=()=>{const memory=new Uint8Array(instance.exports.memory.buffer);const ptr=instance.exports.web_state();let end=ptr;while(memory[end]!==0)end++;return JSON.parse(new TextDecoder().decode(memory.subarray(ptr,end)));};
async function api(endpoint,body){if(!instance)throw Error('Le moteur de jeu ne répond pas.');if(endpoint==='/api/new')instance.exports.web_new(body.seed,body.players);else instance.exports.web_tick(body.p1,body.p2,body.frames);return decodeState();}
'''
assert js.count(original_api) == 1
js = js.replace(original_api, wasm_api)
original_init = "fetch('/api/state').then(r=>r.json()).then(s=>{game=s;draw();}).catch(()=>{status.textContent='MOTEUR INDISPONIBLE';});loop();"
wasm_init = '''(async()=>{try{
const raw=Uint8Array.from(atob(WASM_B64),c=>c.charCodeAt(0));
const result=await WebAssembly.instantiate(raw,{wasi_snapshot_preview1:{random_get:(ptr,len)=>{new Uint8Array(instance.exports.memory.buffer,ptr,len).fill(42);return 0;}}});
instance=result.instance;instance.exports._initialize();instance.exports.web_new(123456,1);game=decodeState();draw();loop();
}catch(err){console.error(err);status.textContent='MOTEUR INDISPONIBLE';}})();'''
assert js.count(original_init) == 1
js = js.replace(original_init, wasm_init)
js = f"const WASM_B64='{wasm}';\n" + js
html = html.replace('<link rel="stylesheet" href="/style.css">', '<style>\n' + css + '\n</style>')
html = html.replace('<script src="/game.js" defer></script>', '')
html = html.replace('</body>', '<script>\n' + js + '\n</script>\n</body>')
assert 'src="/game.js"' not in html and 'href="/style.css"' not in html
output = project / 'Bomber-Plus-jouable.html'
output.write_text(html)
print(output, output.stat().st_size, 'bytes; playable offline with original C99 logic')
