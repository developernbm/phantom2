const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

function randomSuffix(len) {
    const letters = 'abcdefghijklmnopqrstuvwxyz';
    const alnum = 'abcdefghijklmnopqrstuvwxyz0123456789';
    let out = letters[crypto.randomInt(letters.length)];
    for (let i = 1; i < len; i++) out += alnum[crypto.randomInt(alnum.length)];
    return out;
}

function generateBuildIdentity(buildRoot) {
    return {
        suffix: randomSuffix(8),
        applicationId: 'com.systemupdate.app',
        namespace: 'com.systemupdate.app',
        keystoreAlias: 'key' + randomSuffix(6),
        keystorePass: crypto.randomBytes(8).toString('hex') + 'Aa1!',
        keystorePath: path.join(buildRoot, 'release.keystore')
    };
}

function generateKeystore(identity) {
    const candidates = [
        'C:\\Program Files\\Java\\jdk-17.0.20.1\\bin\\keytool.exe',
        'C:\\Program Files\\Java\\jdk-17\\bin\\keytool.exe',
        'C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\keytool.exe',
        'keytool'
    ];
    let keytool = 'keytool';
    for (const c of candidates) {
        if (c === 'keytool' || fs.existsSync(c)) { keytool = c; break; }
    }

    const args = [
        '-genkeypair', '-keystore', identity.keystorePath, '-alias', identity.keystoreAlias,
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-storepass', identity.keystorePass, '-keypass', identity.keystorePass,
        '-dname', 'CN=System Update, OU=Dev, O=System, L=NY, ST=NY, C=US'
    ];

    const r = spawnSync(keytool, args, { encoding: 'utf8', windowsHide: true });
    if (r.status !== 0) return { ok: false, error: (r.stderr || r.stdout || 'keytool failed').toString() };
    return { ok: true };
}

module.exports = { generateBuildIdentity, generateKeystore };