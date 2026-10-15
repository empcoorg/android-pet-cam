const CSS = `
:root{color-scheme:dark;--bg:#0e1116;--panel:#171c24;--line:#2a3140;--fg:#e6edf3;--muted:#8b98a9;--accent:#3b82f6;--rec:#ef4444;--ok:#22c55e;--warn:#f59e0b}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
.wrap{max-width:1000px;margin:0 auto;padding:16px}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
h1{font-size:18px;margin:0}
.live{position:relative;background:#000;border-radius:12px;overflow:hidden;aspect-ratio:16/9;max-height:78vh;display:flex;align-items:center;justify-content:center}
.live img{max-width:100%;max-height:100%;display:block}.live img[hidden]{display:none}
.badges{position:absolute;top:8px;left:8px;display:flex;gap:6px}
.badge{padding:2px 8px;border-radius:6px;font-size:12px;font-weight:600;color:#fff;background:#444}
.badge.rec{background:var(--rec)}.badge.motion{background:var(--warn);color:#000}.badge.on{background:var(--ok)}.badge.off{background:#555}
.grid{display:grid;grid-template-columns:1fr;gap:12px}@media(min-width:800px){.grid{grid-template-columns:3fr 2fr}}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px}
.kv{display:grid;grid-template-columns:auto 1fr;gap:4px 12px;font-size:14px}.kv dt{color:var(--muted)}.kv dd{margin:0}
button,.btn{background:var(--accent);color:#fff;border:0;border-radius:8px;padding:8px 12px;font:inherit;cursor:pointer;text-decoration:none;display:inline-block}
button.ghost,.btn.ghost{background:transparent;border:1px solid var(--line);color:var(--fg)}button.danger{background:var(--rec)}
button:disabled{opacity:.5;cursor:default}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:8px 6px;border-bottom:1px solid var(--line);vertical-align:middle}
td.actions{white-space:nowrap;text-align:right}td.actions button,td.actions .btn{padding:4px 8px;font-size:13px;margin-left:4px}
.bar{height:8px;background:#222;border-radius:4px;overflow:hidden}.bar i{display:block;height:100%;background:var(--accent)}
.muted{color:var(--muted)}code{background:#0b0e13;padding:2px 6px;border-radius:4px;font-size:13px}
#player{position:fixed;inset:0;background:rgba(0,0,0,.92);display:none;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:16px}
#player video,#player img{max-width:100%;max-height:85vh;border-radius:8px}
.login{max-width:360px;margin:15vh auto;display:flex;flex-direction:column;gap:12px}
input[type=password],input[type=text]{padding:10px;border-radius:8px;border:1px solid var(--line);background:#0b0e13;color:var(--fg);font:inherit}
input.code{font:600 22px/1 ui-monospace,monospace;letter-spacing:4px;text-align:center}
.err{color:var(--rec)}
.qrbox{background:#fff;border-radius:8px;padding:12px;width:220px;margin:4px auto}
.qrbox svg{display:block;width:100%;height:100%}
.filterRow{gap:6px}.filterBtn{padding:6px 10px;font-size:13px}
.spinner{display:inline-block;width:13px;height:13px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:spin .7s linear infinite;vertical-align:-2px}
@keyframes spin{to{transform:rotate(360deg)}}
`;

export function loginPage(error: boolean): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Security Cam</title><style>${CSS}</style></head><body><div class="wrap"><form class="login panel" method="post" action="/login">
<h1>Security Cam</h1><p class="muted">Enter the viewer password to see the camera.</p>
${error ? '<p class="err">Wrong password.</p>' : ""}
<input type="password" name="password" placeholder="Password" autofocus required autocomplete="current-password">
<button type="submit">Sign in</button></form></div></body></html>`;
}

export function totpSetupPage(otpauthUri: string, secretBase32: string, qrSvg: string, error: boolean): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Security Cam</title><style>${CSS}</style></head><body><div class="wrap"><form class="login panel" method="post" action="/totp-setup">
<h1>Set up an authenticator app</h1>
<p class="muted">This browser hasn't been verified before. Scan this once with Google Authenticator (or any TOTP app), then enter the 6-digit code it shows to finish.</p>
<div class="qrbox">${qrSvg}</div>
<p class="muted" style="font-size:13px;margin:0">Can't scan? Enter this key manually: <code style="word-break:break-all">${secretBase32}</code></p>
${error ? '<p class="err">Wrong code. Try the current one from the app.</p>' : ""}
<input class="code" type="text" name="code" inputmode="numeric" pattern="[0-9]*" autocomplete="one-time-code" placeholder="000000" maxlength="6" autofocus required>
<button type="submit">Verify &amp; trust this device</button>
</form></div></body></html>`;
}

export function totpVerifyPage(error: boolean): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Security Cam</title><style>${CSS}</style></head><body><div class="wrap"><form class="login panel" method="post" action="/totp-verify">
<h1>Enter your authenticator code</h1>
<p class="muted">This browser hasn't been verified before. Enter the 6-digit code from your authenticator app.</p>
${error ? '<p class="err">Wrong code. Try the current one from the app.</p>' : ""}
<input class="code" type="text" name="code" inputmode="numeric" pattern="[0-9]*" autocomplete="one-time-code" placeholder="000000" maxlength="6" autofocus required>
<button type="submit">Verify &amp; trust this device</button>
</form></div></body></html>`;
}

export function appPage(): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Security Cam</title><style>${CSS}</style></head><body><div class="wrap">
<header><h1>Security Cam <span id="label" class="muted"></span></h1>
<div class="row"><span id="conn" class="badge off">connecting…</span>
<form method="post" action="/logout" style="margin:0"><button class="ghost" type="submit">Sign out</button></form></div></header>

<div id="installBanner" class="panel" hidden style="margin-bottom:12px;border-color:var(--accent)">
  <b>No camera is online.</b> To use this phone as the camera:
  <ol style="margin:6px 0 10px 18px;padding:0;line-height:1.7">
    <li>Tap the button below and open the downloaded file. Allow installing from this source if asked.</li>
    <li>Open Security Cam, grant permissions, go to <b>Settings → Remote access</b> and paste the token (Copy button below).</li>
    <li>Type the server URL, turn on <i>Upload</i> and <i>Live view</i>, go to <b>Camera</b> and tap <b>Start</b>.</li>
  </ol>
  <div class="row"><a class="btn" href="/app.apk" style="font-size:16px;padding:12px 16px">⬇ Install Security Cam on this phone</a>
    <button class="ghost" id="copyTok2">Copy device token</button> <span class="muted" id="copyTok2Msg"></span></div>
</div>

<div class="grid">
  <div>
    <div class="live"><img id="frame" alt="" hidden><div class="badges" id="badges"></div><div id="liveclock" style="position:absolute;right:8px;top:8px;background:rgba(0,0,0,.6);color:#fff;font:600 13px monospace;padding:2px 8px;border-radius:6px;display:none"></div>
      <div id="nodevice" class="muted" style="position:absolute">Camera is offline</div></div>
    <div class="row" style="margin-top:10px">
      <button id="mediaVideo" class="ghost">Video</button>
      <button id="mediaPhotoOnly" class="ghost" title="Full-res stills only — no continuous video stream, for weak hardware">Photo only</button>
      <span class="muted" style="font-size:12px">· trigger:</span>
      <button id="modeMotion" class="ghost">Motion</button>
      <button id="modeCont" class="ghost">Continuous</button>
      <button id="snap">Take photo</button>
      <button class="ghost" id="rotate" title="Rotate the live view 90°">↻ Rotate view</button>
      <span id="fps" class="muted"></span>
    </div>
    <div class="row" style="margin-top:6px">
      <span class="muted" style="font-size:12px">mounting:</span>
      <button class="ghost" data-orient="landscape_left" title="Landscape, camera on top">Landscape</button>
      <button class="ghost" data-orient="landscape_right" title="Landscape, rotated 180°">Landscape ⟲</button>
      <button class="ghost" data-orient="portrait">Portrait</button>
    </div>
    <div class="row" id="photoIntervalRow" style="margin-top:8px" hidden>
      <span class="muted" style="font-size:12px" id="burstLabel">Burst every</span>
      <input type="number" id="photoBurstInterval" min="1" max="60" step="1" style="width:56px;padding:6px 8px;border-radius:8px;border:1px solid var(--line);background:#0b0e13;color:var(--fg);font:inherit">
      <span class="muted" style="font-size:12px" id="thenLabel">sec while moving, then every</span>
      <input type="number" id="photoInterval" min="2" max="300" step="1" style="width:56px;padding:6px 8px;border-radius:8px;border:1px solid var(--line);background:#0b0e13;color:var(--fg);font:inherit">
      <span class="muted" style="font-size:12px" id="tailLabel">sec for</span>
      <input type="number" id="photoPostMotion" min="0" max="3600" step="1" style="width:60px;padding:6px 8px;border-radius:8px;border:1px solid var(--line);background:#0b0e13;color:var(--fg);font:inherit">
      <span class="muted" style="font-size:12px" id="tailUnitLabel">sec after</span>
      <button class="ghost" id="applyInterval">Set</button>
      <span class="muted" id="intervalMsg" style="font-size:12px"></span>
    </div>
  </div>
  <div class="panel">
    <h3 style="margin:0 0 8px">Status</h3>
    <dl class="kv" id="kv"></dl>
    <h3 style="margin:14px 0 6px">Cloud storage</h3>
    <div class="bar"><i id="cloudBar" style="width:0"></i></div>
    <div id="cloudText" class="muted" style="font-size:13px;margin-top:4px"></div>
  </div>
</div>

<div class="panel" style="margin-top:12px">
  <div class="row" style="justify-content:space-between"><h3 style="margin:0">Phone setup</h3>
    <button class="ghost" id="toggleSetup">Show</button></div>
  <div id="setup" hidden style="margin-top:10px;font-size:14px">
    <ol style="margin:0 0 10px 18px;padding:0;line-height:1.7">
      <li>On the phone, open this page in the browser and sign in.</li>
      <li><a class="btn" href="/app.apk">Download the Security Cam app (APK)</a> <span class="muted">— allow installing from this source if asked.</span></li>
      <li>Open Security Cam → <b>Settings</b> → <b>Remote access</b>, paste these two values, enable <i>Upload</i> and <i>Live view</i>.</li>
    </ol>
    <div class="kv"><dt>Worker URL</dt><dd><code id="suUrl"></code> <button class="ghost" data-copy="suUrl">Copy</button></dd>
      <dt>Device token</dt><dd><code id="suTok" style="word-break:break-all"></code> <button class="ghost" data-copy="suTok">Copy</button></dd></div>
    <p class="muted" style="margin:8px 0 0">Anyone signed in to this page can see the token. Rotate it with <code>wrangler secret put DEVICE_TOKEN</code> if needed.</p>
  </div>
</div>

<div class="panel" style="margin-top:12px">
  <div class="row" style="justify-content:space-between"><h3 style="margin:0">Trusted devices</h3>
    <button class="ghost" id="toggleDevices">Show</button></div>
  <div id="devicesBox" hidden style="margin-top:10px">
    <p class="muted" style="font-size:13px;margin:0 0 8px">Browsers that passed the authenticator-app check. They skip it again for 90 days unless revoked here.</p>
    <table><thead><tr><th>Device</th><th>First verified</th><th>Last used</th><th></th></tr></thead><tbody id="devices"></tbody></table>
  </div>
</div>

<div class="panel" style="margin-top:12px">
  <div class="row" style="justify-content:space-between"><h3 style="margin:0">Clips</h3>
    <div class="row filterRow">
      <button class="ghost filterBtn" data-type="all">All</button>
      <button class="ghost filterBtn" data-type="photos" title="Photos only">📷</button>
      <button class="ghost filterBtn" data-type="videos" title="Videos only">🎥</button>
      <button class="ghost filterBtn" id="importantToggle" title="Important only — combines with All/Photos/Videos">★</button>
      <button class="ghost" id="refresh" title="Refresh">⟳</button>
    </div></div>
  <table><thead><tr><th>When</th><th>Type</th><th>Size</th><th></th></tr></thead><tbody id="clips"></tbody></table>
</div>
</div>

<div id="player"><div id="playerBody"></div><button class="ghost" id="closePlayer">Close</button></div>

<script>
(() => {
  const $ = (id) => document.getElementById(id);
  const frame = $('frame'), badges = $('badges'), kv = $('kv'), conn = $('conn'), nodevice = $('nodevice');
  let ws, lastUrl = null, frames = 0, lastFpsAt = Date.now(), status = null, deviceOnline = false, utcOffsetMinutes = null;

  const fmtBytes = (b) => b >= 1<<30 ? (b/(1<<30)).toFixed(2)+' GB' : b >= 1<<20 ? Math.round(b/(1<<20))+' MB' : Math.round(b/1024)+' KB';
  // "UTC-6" / "UTC+5:30" — labels every timestamp with the camera's own timezone (file names
  // and burned-in stamps are in the camera's local time, not the viewer's).
  const tzLabel = () => {
    if (utcOffsetMinutes === null) return '';
    const sign = utcOffsetMinutes >= 0 ? '+' : '-';
    const abs = Math.abs(utcOffsetMinutes);
    const h = Math.floor(abs / 60), m = abs % 60;
    return ' UTC' + sign + h + (m ? ':' + String(m).padStart(2, '0') : '');
  };
  const DOW = ['SUN','MON','TUE','WED','THU','FRI','SAT'];
  const fmtKey = (k) => {
    const m = /(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})/.exec(k);
    if (!m) return k;
    // The numbers are already the camera's local wall-clock time; reading them back as UTC
    // sidesteps the viewer's own timezone while still landing on the correct day of week.
    const dow = DOW[new Date(Date.UTC(+m[1], +m[2]-1, +m[3], +m[4], +m[5], +m[6])).getUTCDay()];
    return m[1]+'-'+m[2]+'-'+m[3]+' '+m[4]+':'+m[5]+':'+m[6]+' '+dow+tzLabel();
  };

  function connect() {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { conn.textContent = 'connected'; conn.className = 'badge on'; };
    ws.onclose = () => { conn.textContent = 'reconnecting…'; conn.className = 'badge off'; setTimeout(connect, 2000); };
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        let m; try { m = JSON.parse(ev.data); } catch { return; }
        if (m.type === 'status') { status = m; render(); }
        else if (m.type === 'device') { deviceOnline = m.online; render(); }
      } else {
        const url = URL.createObjectURL(new Blob([ev.data], {type: 'image/jpeg'}));
        frame.onload = () => { if (lastUrl) URL.revokeObjectURL(lastUrl); lastUrl = url; frame.hidden = false; applyRotation(); };
        frame.src = url; frames++;
        const now = Date.now();
        if (now - lastFpsAt >= 2000) { $('fps').textContent = (frames / ((now - lastFpsAt)/1000)).toFixed(1) + ' fps'; frames = 0; lastFpsAt = now; }
      }
    };
  }

  function render() {
    nodevice.style.display = deviceOnline ? 'none' : 'block';
    if (!deviceOnline) { frame.hidden = true; $('fps').textContent = ''; }
    const s = status || {};
    applyRotation();
    $('installBanner').hidden = deviceOnline;
    $('label').textContent = deviceOnline && s.label ? '· ' + s.label : '';
    if (s.profile === 'evidence' && deviceOnline) {
      nodevice.textContent = 'High-res evidence mode — live view is off. Recording ' + (s.recording ? 'ON' : 'idle') + '. Clips are in the list below.';
      nodevice.style.display = 'block'; frame.hidden = true;
    } else {
      nodevice.textContent = s.ts && !deviceOnline ? 'Camera is offline · last seen ' + new Date(s.ts).toLocaleString() : 'Camera is offline';
    }
    badges.innerHTML = (deviceOnline && s.recording ? '<span class="badge rec">REC</span>' : '') + (deviceOnline && s.motion ? '<span class="badge motion">MOTION</span>' : '')
      + '<span class="badge ' + (deviceOnline ? 'on' : 'off') + '">' + (deviceOnline ? 'camera online' : 'camera offline') + '</span>';
    const photoOnly = s.mediaType === 'photo_only';
    const rows = [
      ['Media', (s.mediaType === 'photo_only' ? 'Photo only' : 'Video') + ' · ' + (s.mode || '—')],
      ['Recording', photoOnly ? '—' : (s.recording ? 'yes' : 'no')], ['Camera', (s.camera || '—') + (s.quality ? ' · ' + s.quality : '')],
      ['Battery', s.battery != null && s.battery >= 0 ? s.battery + '%' + (s.charging ? ' ⚡' : '') : '—'],
      ['Phone storage', s.usedBytes != null ? fmtBytes(s.usedBytes) + ' / ' + fmtBytes(s.limitBytes) : '—'],
      ['Session', (s.clips ?? 0) + ' clips · ' + (s.photos ?? 0) + ' photos'],
      ['Resolution', (s.resolution || '—') + (s.stillResolution ? ' · stills ' + s.stillResolution : '')],
      ['Night vision', s.night ? 'ON' + (s.nightMode ? ' (' + s.nightMode + ')' : '') : (s.nightMode ? 'off (' + s.nightMode + ')' : '—')],
    ];
    if (s.wifiNetwork) rows.push(['WiFi', s.wifiNetwork + (s.wifiActive ? ' · in use' : ' · standby')]);
    if (s.cellularNetwork) rows.push(['Mobile', s.cellularNetwork + (s.cellularActive ? ' · in use' : ' · standby')]);
    if (!s.wifiNetwork && !s.cellularNetwork) rows.push(['Network', '—']);
    rows.push(['App', s.version || '—'], ['Last update', s.ts ? new Date(s.ts).toLocaleTimeString() : '—']);
    if (s.error) rows.push(['Error', s.error]);
    kv.innerHTML = rows.map(([k, v]) => '<dt>' + k + '</dt><dd>' + v + '</dd>').join('');
    $('mediaVideo').style.borderColor = !photoOnly ? 'var(--accent)' : '';
    $('mediaPhotoOnly').style.borderColor = photoOnly ? 'var(--accent)' : '';
    $('modeMotion').style.borderColor = s.mode === 'motion' ? 'var(--accent)' : '';
    $('modeCont').style.borderColor = s.mode === 'continuous' ? 'var(--accent)' : '';
    for (const id of ['mediaVideo','mediaPhotoOnly','modeMotion','modeCont','snap']) $(id).disabled = !deviceOnline;
    const showBurst = deviceOnline && photoOnly && s.mode === 'motion';
    const showTail = showBurst;
    $('photoIntervalRow').hidden = !(deviceOnline && photoOnly);
    $('photoBurstInterval').hidden = !showBurst; $('burstLabel').hidden = !showBurst; $('thenLabel').hidden = !showBurst;
    $('photoPostMotion').hidden = !showTail; $('tailLabel').hidden = !showTail; $('tailUnitLabel').hidden = !showTail;
    if (!showBurst) { $('thenLabel').textContent = 'Full-res photo every'; $('tailLabel').hidden = true; $('tailUnitLabel').hidden = true; }
    else { $('thenLabel').textContent = 'sec while moving, then every'; }
    if (deviceOnline && photoOnly) {
      if (document.activeElement !== $('photoInterval') && s.photoOnlyIntervalSeconds != null) $('photoInterval').value = s.photoOnlyIntervalSeconds;
      if (document.activeElement !== $('photoBurstInterval') && s.photoBurstIntervalSeconds != null) $('photoBurstInterval').value = s.photoBurstIntervalSeconds;
      if (document.activeElement !== $('photoPostMotion') && s.photoOnlyPostMotionSeconds != null) $('photoPostMotion').value = s.photoOnlyPostMotionSeconds;
    }
  }

  let viewRot = 0;
  try { viewRot = (parseInt(localStorage.getItem('securitycam_rot') || '0', 10) || 0) % 360; } catch (e) {}
  function applyRotation() {
    const base = (status && status.liveRotation) || 0;
    const rot = ((base + viewRot) % 360 + 360) % 360;
    const box = frame.parentElement;
    const nw = frame.naturalWidth || 16, nh = frame.naturalHeight || 9;
    // Effective aspect after rotation; size the box to it so the picture fills the width upright.
    const ar = (rot % 180) ? (nh / nw) : (nw / nh);
    if (ar > 0.2 && ar < 5) box.style.aspectRatio = ar.toFixed(4);
    frame.style.transformOrigin = 'center center';
    frame.style.transform = rot ? 'rotate(' + rot + 'deg)' : '';
    if (rot % 180) { frame.style.maxWidth = box.clientHeight + 'px'; frame.style.maxHeight = box.clientWidth + 'px'; }
    else { frame.style.maxWidth = '100%'; frame.style.maxHeight = '100%'; }
  }
  for (const b of document.querySelectorAll('[data-orient]')) b.onclick = () => cmd('setOrientation', b.dataset.orient);
  $('rotate').onclick = () => {
    viewRot = (viewRot + 90) % 360;
    try { localStorage.setItem('securitycam_rot', String(viewRot)); } catch (e) {}
    applyRotation();
  };
  window.addEventListener('resize', applyRotation);

  const cmd = (name, value) => ws && ws.readyState === 1 && ws.send(JSON.stringify({type: 'cmd', name, value}));
  const cmdSeconds = (name, seconds) => ws && ws.readyState === 1 && ws.send(JSON.stringify({type: 'cmd', name, seconds}));
  $('mediaVideo').onclick = () => cmd('setMediaType', 'video');
  $('mediaPhotoOnly').onclick = () => cmd('setMediaType', 'photo_only');
  $('modeMotion').onclick = () => cmd('setMode', 'motion');
  $('modeCont').onclick = () => cmd('setMode', 'continuous');
  $('snap').onclick = () => cmd('snapshot');
  $('applyInterval').onclick = () => {
    const msg = $('intervalMsg');
    const iv = parseInt($('photoInterval').value, 10);
    if (!Number.isFinite(iv) || iv < 2 || iv > 300) { msg.textContent = 'interval: 2-300 only'; return; }
    cmdSeconds('setPhotoOnlyInterval', iv);
    if (!$('photoBurstInterval').hidden) {
      const bv = parseInt($('photoBurstInterval').value, 10);
      if (!Number.isFinite(bv) || bv < 1 || bv > 60) { msg.textContent = 'burst: 1-60 only'; return; }
      cmdSeconds('setPhotoBurstInterval', bv);
    }
    if (!$('photoPostMotion').hidden) {
      const tv = parseInt($('photoPostMotion').value, 10);
      if (!Number.isFinite(tv) || tv < 0 || tv > 3600) { msg.textContent = 'tail: 0-3600 only'; return; }
      cmdSeconds('setPhotoOnlyPostMotion', tv);
    }
    msg.textContent = 'Set';
    setTimeout(() => { msg.textContent = ''; }, 1500);
  };

  // Type (all/photos/videos) is a single-select group; important is an independent toggle that
  // combines with whichever type is active — e.g. "important photos only" is a valid combination.
  let typeFilter = 'all';
  let importantOnly = false;
  function setType(t) {
    typeFilter = t;
    for (const b of document.querySelectorAll('.filterBtn[data-type]')) {
      b.style.borderColor = b.dataset.type === t ? 'var(--accent)' : '';
    }
    loadClips();
  }
  function toggleImportant() {
    importantOnly = !importantOnly;
    $('importantToggle').style.borderColor = importantOnly ? 'var(--accent)' : '';
    loadClips();
  }

  async function loadClips() {
    const refreshBtn = $('refresh');
    const prevRefreshHtml = refreshBtn.innerHTML;
    refreshBtn.disabled = true;
    refreshBtn.innerHTML = '<span class="spinner"></span>';
    try {
      const r = await fetch('/api/clips' + (importantOnly ? '?important=1' : '')); if (!r.ok) return;
      const data = await r.json();
      utcOffsetMinutes = data.utcOffsetMinutes ?? null;
      $('cloudBar').style.width = Math.min(100, 100 * data.totalBytes / data.limitBytes) + '%';
      $('cloudText').textContent = fmtBytes(data.totalBytes) + ' of ' + fmtBytes(data.limitBytes) + ' · ' + data.clips.length + ' files' + (data.importantCount ? ' · ★ ' + data.importantCount + ' important' : '');
      const tb = $('clips'); tb.innerHTML = '';
      for (const c of data.clips) {
        if (typeFilter === 'photos' && c.type !== 'photo') continue;
        if (typeFilter === 'videos' && c.type !== 'video') continue;
        const tr = document.createElement('tr');
        const href = '/api/clip/' + encodeURIComponent(c.key);
        const star = c.important ? ' <span title="flagged important by AI" style="color:#f59e0b">★</span>' : '';
        tr.innerHTML = '<td>' + fmtKey(c.key) + star + '</td><td>' + c.type + (c.key.includes('_motion') ? ' · motion' : c.key.includes('_continuous') ? ' · continuous' : '') + '</td><td>' + fmtBytes(c.size) + '</td>'
          + '<td class="actions"><button data-play>' + (c.type === 'photo' ? 'View' : 'Play') + '</button><a class="btn ghost" href="' + href + '?download=1" download>Save</a><button class="danger" data-del>Delete</button></td>';
        tr.querySelector('[data-play]').onclick = () => play(c, href);
        tr.querySelector('[data-del]').onclick = async (e) => {
          const b = e.currentTarget;
          if (b.dataset.armed !== '1') { b.dataset.armed = '1'; b.textContent = 'Confirm'; setTimeout(() => { b.dataset.armed = ''; b.textContent = 'Delete'; }, 3000); return; }
          await fetch(href, {method: 'DELETE'}); loadClips();
        };
        tb.appendChild(tr);
      }
    } finally {
      refreshBtn.disabled = false;
      refreshBtn.innerHTML = prevRefreshHtml;
    }
  }
  function play(c, href) {
    const body = $('playerBody');
    const stamp = fmtKey(c.key);
    const media = c.type === 'video' ? '<video controls autoplay playsinline src="' + href + '"></video>' : '<img src="' + href + '" alt="">';
    // Top, not bottom: a burned-in stamp (when the clip has one) sits bottom-left on the media
    // itself, and video controls also live at the bottom — top-left is clear of both.
    body.innerHTML = '<div style="position:relative;display:inline-block">' + media +
      '<div style="position:absolute;left:8px;top:8px;background:rgba(0,0,0,.6);color:#fff;font:600 13px monospace;padding:2px 8px;border-radius:6px;pointer-events:none">' + stamp + '</div></div>';
    $('player').style.display = 'flex';
  }
  $('closePlayer').onclick = () => { $('playerBody').innerHTML = ''; $('player').style.display = 'none'; };
  $('refresh').onclick = loadClips;
  for (const b of document.querySelectorAll('.filterBtn[data-type]')) b.onclick = () => setType(b.dataset.type);
  $('importantToggle').onclick = toggleImportant;
  // Set initial state directly (not via setType/toggleImportant, which each also call loadClips —
  // that would double-fetch here) and style buttons to match before the single real initial load.
  document.querySelector('.filterBtn[data-type="all"]').style.borderColor = 'var(--accent)';
  if (new URLSearchParams(location.search).get('important') === '1') {
    importantOnly = true;
    $('importantToggle').style.borderColor = 'var(--accent)';
  }

  $('copyTok2').onclick = async () => {
    const r = await fetch('/api/setup'); if (!r.ok) return; const d = await r.json();
    try { await navigator.clipboard.writeText(d.deviceToken); $('copyTok2Msg').textContent = 'Token copied'; }
    catch { $('suTok').textContent = d.deviceToken; $('setup').hidden = false; $('copyTok2Msg').textContent = 'See token below'; }
  };
  async function loadDevices() {
    const r = await fetch('/api/devices'); if (!r.ok) return;
    const data = await r.json();
    const tb = $('devices'); tb.innerHTML = '';
    for (const d of data.devices) {
      const tr = document.createElement('tr');
      const mine = d.isThisDevice ? ' <span class="badge on" style="font-size:10px">this device</span>' : '';
      tr.innerHTML = '<td>' + d.label + mine + '</td><td>' + new Date(d.created).toLocaleDateString() + '</td>'
        + '<td>' + new Date(d.last_seen).toLocaleString() + '</td><td class="actions"><button class="danger" data-revoke>Revoke</button></td>';
      tr.querySelector('[data-revoke]').onclick = async (e) => {
        const b = e.currentTarget;
        if (b.dataset.armed !== '1') { b.dataset.armed = '1'; b.textContent = 'Confirm'; setTimeout(() => { b.dataset.armed = ''; b.textContent = 'Revoke'; }, 3000); return; }
        await fetch('/api/devices/' + encodeURIComponent(d.id) + '/revoke', { method: 'POST' }); loadDevices();
      };
      tb.appendChild(tr);
    }
  }
  $('toggleDevices').onclick = () => {
    const box = $('devicesBox'); box.hidden = !box.hidden; $('toggleDevices').textContent = box.hidden ? 'Show' : 'Hide';
    if (!box.hidden) loadDevices();
  };

  $('toggleSetup').onclick = async () => {
    const box = $('setup'); box.hidden = !box.hidden; $('toggleSetup').textContent = box.hidden ? 'Show' : 'Hide';
    if (!box.hidden && !$('suTok').textContent) {
      const r = await fetch('/api/setup'); if (!r.ok) return; const d = await r.json();
      $('suUrl').textContent = d.workerUrl; $('suTok').textContent = d.deviceToken;
    }
  };
  for (const b of document.querySelectorAll('[data-copy]')) b.onclick = async () => {
    const t = $(b.dataset.copy).textContent;
    try { await navigator.clipboard.writeText(t); b.textContent = 'Copied'; } catch { const r = document.createRange(); r.selectNodeContents($(b.dataset.copy)); const s = getSelection(); s.removeAllRanges(); s.addRange(r); b.textContent = 'Selected'; }
    setTimeout(() => b.textContent = 'Copy', 1500);
  };

  setInterval(() => {
    const lc = $('liveclock');
    const show = status && (status.profile === 'hires_live' || status.profile === 'max') && deviceOnline && !frame.hidden;
    lc.style.display = show ? 'block' : 'none';
    if (show) {
      const now = new Date();
      const label = status.label ? '  ' + status.label : '';
      if (utcOffsetMinutes !== null) {
        // The camera's own local clock, not the viewer's browser timezone: shift the current UTC
        // instant by the camera's offset, then read it back with UTC getters so the browser's own
        // timezone isn't also applied on top.
        const d = new Date(now.getTime() + utcOffsetMinutes * 60000);
        const pad = (n) => String(n).padStart(2, '0');
        lc.textContent = d.getUTCFullYear()+'-'+pad(d.getUTCMonth()+1)+'-'+pad(d.getUTCDate())+' '+pad(d.getUTCHours())+':'+pad(d.getUTCMinutes())+':'+pad(d.getUTCSeconds())+' '+DOW[d.getUTCDay()]+tzLabel()+label;
      } else {
        lc.textContent = now.getFullYear()+'-'+String(now.getMonth()+1).padStart(2,'0')+'-'+String(now.getDate()).padStart(2,'0')+' '+now.toLocaleTimeString()+' '+DOW[now.getDay()]+label;
      }
    }
  }, 1000);
  connect(); render(); loadClips(); setInterval(loadClips, 60000);
})();
</script></body></html>`;
}
