package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import org.json.JSONObject
import java.util.Locale

/** RCTV: the phone page that links IMDb on the TV (see [ImdbLinkServer]), in the app's language. */
internal object ImdbLinkWebPage {

    /** The app's language as the user chose it in the app (the phone page and its replies use it). */
    fun localized(base: Context): Context {
        val tag = runCatching {
            base.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        }.getOrNull()
        if (tag.isNullOrEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    fun html(rawContext: Context?): String {
        val context = rawContext?.let(::localized)
        fun text(id: Int, fallback: String): String = context?.getString(id) ?: fallback

        val appName = text(R.string.app_name, "RCTV")
        val texts = JSONObject()
            .put("linking", text(R.string.web_imdb_linking, "Linking… this can take a minute"))
            .put("syncing", text(R.string.imdb_syncing, "Syncing…"))
            .put("errorEmpty", text(R.string.web_imdb_error_empty, "Paste your IMDb link first"))
            .put("errorConnection", text(R.string.web_imdb_error_connection, "Couldn't reach the TV. Keep IMDb open in the TV's settings and try again."))
            .put("forProfile", text(R.string.web_imdb_for_profile, "For the profile %1\$s"))
            .put("unlinkConfirm", text(R.string.web_imdb_unlink_confirm, "Unlink IMDb from the TV?"))
            .toString()
            .replace("</", "<\\/")

        val title = context?.getString(R.string.web_imdb_title, appName) ?: "Link IMDb to $appName"
        val step1 = text(R.string.web_imdb_step1, "Open your IMDb watchlist (sign in to IMDb if it asks).")
        val openImdb = text(R.string.web_imdb_open, "Open IMDb")
        val step2 = text(R.string.web_imdb_step2, "Copy the page's link (Share › Copy link, or from the address bar).")
        val step3 = text(R.string.web_imdb_step3, "Paste it here and tap Link.")
        val label = text(R.string.web_imdb_label, "Your IMDb link or id")
        val placeholder = text(R.string.imdb_dialog_placeholder, "ur12345678 or your profile link")
        val linkAction = text(R.string.web_imdb_link, "Link")
        val syncAction = text(R.string.imdb_sync_title, "Sync now")
        val unlinkAction = text(R.string.imdb_unlink_title, "Unlink IMDb")
        val publicNote = text(R.string.web_imdb_public_note, "Make your IMDb watchlist public so it shows on the TV (Watchlist › Edit › Public). Your ratings sync either way.")
        val privacyNote = text(R.string.web_imdb_privacy_note, "Only your profile's link goes to the TV, never your IMDb password.")
        val loading = text(R.string.web_custom_poster_loading, "Loading...")

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(appName)} - IMDb</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#000;color:#fff;display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:520px;width:100%;padding:24px 0 40px}
.brand{display:inline-block;background:#F5C518;color:#000;font-weight:800;font-size:18px;
letter-spacing:-0.02em;padding:4px 10px;border-radius:6px;margin-bottom:14px}
h1{font-size:22px;font-weight:600;margin-bottom:4px}
.profile{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:20px;min-height:16px}
.state{padding:14px 16px;border-radius:16px;background:rgba(255,255,255,0.05);
border:1px solid rgba(255,255,255,0.08);margin-bottom:22px}
.state .t{font-size:15px;font-weight:600}
.state .d{font-size:13px;color:rgba(255,255,255,0.55);margin-top:4px}
.state .p{font-size:13px;color:#ffb74d;margin-top:6px}
.state.on{border-color:rgba(245,197,24,0.45)}
.row{display:flex;gap:10px;margin-top:12px}
ol{list-style:none;counter-reset:s;margin-bottom:18px}
ol li{counter-increment:s;position:relative;padding-left:34px;margin-bottom:12px;font-size:14px;
line-height:1.45;color:rgba(255,255,255,0.85)}
ol li:before{content:counter(s);position:absolute;left:0;top:-1px;width:24px;height:24px;border-radius:50%;
background:rgba(255,255,255,0.1);display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600}
a.open{display:inline-block;margin-top:8px;padding:8px 16px;border-radius:100px;background:#F5C518;
color:#000;font-weight:600;font-size:13px;text-decoration:none}
label{display:block;font-size:0.75rem;font-weight:500;color:rgba(255,255,255,0.35);
letter-spacing:0.1em;text-transform:uppercase;margin-bottom:6px}
textarea{width:100%;min-height:76px;padding:12px 1rem;border-radius:16px;border:1px solid rgba(255,255,255,0.14);
background:transparent;color:#fff;font-family:inherit;font-size:15px;line-height:1.4;resize:vertical;outline:none}
textarea:focus{border-color:rgba(255,255,255,0.42)}
textarea::placeholder{color:rgba(255,255,255,0.25)}
button{flex:1;padding:0.85rem 1.2rem;border:1px solid rgba(255,255,255,0.2);border-radius:100px;
background:transparent;color:#fff;font-family:inherit;font-size:0.9rem;font-weight:500;cursor:pointer}
button.main{background:#fff;color:#000;border-color:#fff;width:100%;margin-top:12px}
button:disabled{opacity:.45}
.msg{margin-top:14px;font-size:14px;text-align:center;min-height:20px}
.msg.ok{color:#4caf50}.msg.err{color:#ef5350}.msg.wait{color:rgba(255,255,255,0.6)}
.note{margin-top:18px;font-size:12px;color:rgba(255,255,255,0.4);line-height:1.5}
.hidden{display:none}
</style>
</head>
<body>
<div class="card">
<div class="brand">IMDb</div>
<h1>${esc(title)}</h1>
<div class="profile" id="profile"></div>
<div class="state" id="state"><div class="t">${esc(loading)}</div></div>
<ol>
<li>${esc(step1)}<br><a class="open" href="https://www.imdb.com/list/watchlist" target="_blank" rel="noopener">${esc(openImdb)}</a></li>
<li>${esc(step2)}</li>
<li>${esc(step3)}</li>
</ol>
<label for="input">${esc(label)}</label>
<textarea id="input" placeholder="${esc(placeholder)}" autocapitalize="off" autocomplete="off" spellcheck="false"></textarea>
<button class="main" id="link">${esc(linkAction)}</button>
<div class="row hidden" id="actions">
<button id="sync">${esc(syncAction)}</button>
<button id="unlink">${esc(unlinkAction)}</button>
</div>
<div class="msg" id="msg"></div>
<p class="note">${esc(publicNote)}</p>
<p class="note">${esc(privacyNote)}</p>
</div>
<script>
const T = $texts;
const ${'$'} = id => document.getElementById(id);
let busy = false;
function show(text, kind){ const m = ${'$'}('msg'); m.textContent = text || ''; m.className = 'msg ' + (kind || ''); }
function render(s){
  if(!s) return;
  ${'$'}('profile').textContent = s.profile ? T.forProfile.replace('%1${'$'}s', s.profile) : '';
  const box = ${'$'}('state');
  box.className = 'state' + (s.linked ? ' on' : '');
  box.innerHTML = '';
  const t = document.createElement('div'); t.className = 't'; t.textContent = s.title; box.appendChild(t);
  const detail = s.syncing ? T.syncing : s.details;
  if(detail){ const d = document.createElement('div'); d.className = 'd'; d.textContent = detail; box.appendChild(d); }
  if(s.problem){ const p = document.createElement('div'); p.className = 'p'; p.textContent = s.problem; box.appendChild(p); }
  ${'$'}('actions').classList.toggle('hidden', !s.linked);
}
async function call(path, body){
  const r = await fetch(path, { method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body) });
  if(!r.ok) throw new Error('http ' + r.status);
  return r.json();
}
async function refresh(){ if(busy) return; try { render(await call('/api/status')); } catch(e) {} }
function setBusy(on){ busy = on; ['link','sync','unlink'].forEach(id => ${'$'}(id).disabled = on); }
async function act(path, body, waitText){
  if(busy) return;
  setBusy(true); show(waitText || '', 'wait');
  try {
    const reply = await call(path, body);
    render(reply.status);
    show(reply.message, reply.ok ? 'ok' : 'err');
    return reply;
  } catch(e) { show(T.errorConnection, 'err'); }
  finally { setBusy(false); }
}
${'$'}('link').onclick = async () => {
  const input = ${'$'}('input').value.trim();
  if(!input){ show(T.errorEmpty, 'err'); return; }
  const reply = await act('/api/link', { input: input }, T.linking);
  if(reply && reply.ok) ${'$'}('input').value = '';
};
${'$'}('sync').onclick = () => act('/api/sync', {}, T.syncing);
${'$'}('unlink').onclick = () => { if(confirm(T.unlinkConfirm)) act('/api/unlink', {}); };
refresh();
setInterval(refresh, 5000);
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
