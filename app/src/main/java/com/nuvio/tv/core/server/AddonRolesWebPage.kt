package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import org.json.JSONObject

/** RCTV: the phone page that sets up each server's addon (see [AddonRolesServer]), in the app's language. */
internal object AddonRolesWebPage {

    fun html(rawContext: Context?): String {
        val context = rawContext?.let(ImdbLinkWebPage::localized)
        fun text(id: Int, fallback: String): String = context?.getString(id) ?: fallback

        val appName = text(R.string.app_name, "RCTV")
        val texts = JSONObject()
            .put("notSet", text(R.string.web_addon_roles_not_set, "Not set"))
            .put("save", text(R.string.web_addon_roles_save, "Save"))
            .put("remove", text(R.string.web_addon_roles_remove, "Remove"))
            .put("placeholder", text(R.string.web_addon_roles_placeholder, "https://…/manifest.json"))
            .put("saving", text(R.string.web_addon_roles_saving, "Saving…"))
            .put("errorEmpty", text(R.string.web_addon_roles_error_empty, "Paste the addon's link first"))
            .put("errorConnection", text(R.string.web_addon_roles_error_connection, "Couldn't reach the TV. Keep the QR open on the TV and try again."))
            .put("spanish", text(R.string.web_addon_roles_spanish, "Spanish"))
            .toString()
            .replace("</", "<\\/")

        val title = text(R.string.web_addon_roles_title, "Set up your servers")
        val intro = text(R.string.web_addon_roles_intro, "Paste each addon's link (it ends in manifest.json) next to its server and tap Save. The TV installs it and knows which one it is.")
        val plugins = text(R.string.web_addon_roles_plugins, "Plugins")
        val pluginHint = text(R.string.web_addon_roles_plugin_hint, "Plugin repository link (All-in-One-Nuvio)")
        val add = text(R.string.web_addon_roles_add, "Add")
        val privacy = text(R.string.web_addon_roles_privacy, "The links stay on your TV. This page only shows each server's addon name and site.")

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(appName)} - Add-ons</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#000;color:#fff;display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:24px 0 40px}
h1{font-size:22px;font-weight:600;margin-bottom:8px}
.intro{font-size:14px;color:rgba(255,255,255,0.7);line-height:1.45;margin-bottom:18px}
h2{font-size:12px;font-weight:600;color:rgba(255,255,255,0.4);letter-spacing:0.1em;text-transform:uppercase;margin:22px 0 10px}
.slot{padding:14px 16px;border-radius:16px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.08);margin-bottom:12px}
.slot.on{border-color:rgba(76,175,80,0.5)}
.slot .t{font-size:15px;font-weight:600}
.slot .d{font-size:13px;color:rgba(255,255,255,0.55);margin-top:3px;word-break:break-all}
.slot.on .d{color:#81c784}
input{width:100%;margin-top:10px;padding:11px 14px;border-radius:12px;border:1px solid rgba(255,255,255,0.14);
background:transparent;color:#fff;font-family:inherit;font-size:14px;outline:none}
input:focus{border-color:rgba(255,255,255,0.42)}
input::placeholder{color:rgba(255,255,255,0.25)}
.row{display:flex;gap:8px;margin-top:10px}
button{flex:1;padding:0.7rem 1rem;border:1px solid rgba(255,255,255,0.2);border-radius:100px;background:transparent;
color:#fff;font-family:inherit;font-size:0.85rem;font-weight:500;cursor:pointer}
button.main{background:#fff;color:#000;border-color:#fff}
button:disabled{opacity:.45}
.msg{margin-top:8px;font-size:13px;min-height:16px}
.msg.ok{color:#4caf50}.msg.err{color:#ef5350}.msg.wait{color:rgba(255,255,255,0.6)}
.note{margin-top:18px;font-size:12px;color:rgba(255,255,255,0.4);line-height:1.5}
</style>
</head>
<body>
<div class="card">
<h1>${esc(title)}</h1>
<p class="intro">${esc(intro)}</p>
<div id="slots"></div>
<h2>${esc(plugins)}</h2>
<div class="slot" id="plugin">
<div class="t">${esc(plugins)}</div>
<div class="d" id="pluginList"></div>
<input id="pluginUrl" placeholder="${esc(pluginHint)}" autocapitalize="off" autocomplete="off" spellcheck="false">
<div class="row"><button class="main" id="pluginAdd">${esc(add)}</button></div>
<div class="msg" id="pluginMsg"></div>
</div>
<p class="note">${esc(privacy)}</p>
</div>
<script>
const T = $texts;
const ${'$'} = id => document.getElementById(id);
const GROUPS = [['AIO Stream', k => k.startsWith('aio_')], ['Torrentio', k => k.startsWith('torrentio_')],
  [T.spanish, k => k === 'latinodebrid' || k === 'latam']];
let busy = false, built = false;
async function call(path, body){
  const r = await fetch(path, { method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body) });
  if(!r.ok) throw new Error('http ' + r.status);
  return r.json();
}
function el(tag, cls, text){ const e = document.createElement(tag); if(cls) e.className = cls; if(text) e.textContent = text; return e; }
function build(s){
  const root = ${'$'}('slots'); root.innerHTML = '';
  GROUPS.forEach(([name, test]) => {
    const slots = s.slots.filter(x => test(x.key));
    if(!slots.length) return;
    root.appendChild(el('h2', '', name));
    slots.forEach(slot => {
      const box = el('div', 'slot'); box.id = 'slot-' + slot.key;
      box.appendChild(el('div', 't', slot.label));
      const d = el('div', 'd'); d.id = 'state-' + slot.key; box.appendChild(d);
      const input = el('input'); input.id = 'url-' + slot.key; input.placeholder = T.placeholder;
      input.autocapitalize = 'off'; input.autocomplete = 'off'; input.spellcheck = false; box.appendChild(input);
      const row = el('div', 'row');
      const save = el('button', 'main', T.save); save.onclick = () => saveSlot(slot.key); row.appendChild(save);
      const remove = el('button', '', T.remove); remove.id = 'remove-' + slot.key; remove.onclick = () => clearSlot(slot.key); row.appendChild(remove);
      box.appendChild(row);
      const m = el('div', 'msg'); m.id = 'msg-' + slot.key; box.appendChild(m);
      root.appendChild(box);
    });
  });
  built = true;
}
function render(s){
  if(!s) return;
  if(!built) build(s);
  s.slots.forEach(slot => {
    const on = !!slot.name;
    const box = ${'$'}('slot-' + slot.key); if(!box) return;
    box.className = 'slot' + (on ? ' on' : '');
    ${'$'}('state-' + slot.key).textContent = on ? (slot.name + (slot.host ? ' · ' + slot.host : '')) : T.notSet;
    ${'$'}('remove-' + slot.key).style.display = on ? '' : 'none';
  });
  ${'$'}('pluginList').textContent = (s.plugins && s.plugins.length) ? s.plugins.join(' · ') : T.notSet;
}
function msg(id, text, kind){ const m = ${'$'}(id); m.textContent = text || ''; m.className = 'msg ' + (kind || ''); }
async function refresh(){ if(busy) return; try { render(await call('/api/status')); } catch(e) {} }
async function act(msgId, path, body){
  if(busy) return null;
  busy = true; msg(msgId, T.saving, 'wait');
  try { const reply = await call(path, body); render(reply.status); msg(msgId, reply.message, reply.ok ? 'ok' : 'err'); return reply; }
  catch(e) { msg(msgId, T.errorConnection, 'err'); return null; }
  finally { busy = false; }
}
async function saveSlot(key){
  const input = ${'$'}('url-' + key); const url = input.value.trim();
  if(!url){ msg('msg-' + key, T.errorEmpty, 'err'); return; }
  const reply = await act('msg-' + key, '/api/save', { role: key, url: url });
  if(reply && reply.ok) input.value = '';
}
function clearSlot(key){ act('msg-' + key, '/api/clear', { role: key }); }
${'$'}('pluginAdd').onclick = async () => {
  const input = ${'$'}('pluginUrl'); const url = input.value.trim();
  if(!url){ msg('pluginMsg', T.errorEmpty, 'err'); return; }
  const reply = await act('pluginMsg', '/api/plugin', { url: url });
  if(reply && reply.ok) input.value = '';
};
refresh();
setInterval(refresh, 6000);
</script>
</body>
</html>
""".trimIndent()
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
