#!/usr/bin/env python3
"""Post-process APK: inject lib/arm64/ for Huawei devices and re-sign."""
import zipfile, os, sys, subprocess, tempfile

apk = sys.argv[1]
tmp = apk + '.tmp'

print(f"Processing: {apk}")

# Step 1: Add lib/arm64/libreasonix.so (copy from arm64-v8a)
with zipfile.ZipFile(apk, 'r') as zin:
    data = zin.read('lib/arm64-v8a/libreasonix.so')
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if not item.filename.startswith('META-INF/'):
                zout.writestr(item, zin.read(item.filename))
        info = zipfile.ZipInfo('lib/arm64/libreasonix.so')
        zout.writestr(info, data)

os.replace(tmp, apk)
print(f"Added lib/arm64/libreasonix.so, size={os.path.getsize(apk)}")

# Step 2: Generate keystore and re-sign
ks = os.path.expanduser('~/.android/huawei_debug.keystore')
os.makedirs(os.path.dirname(ks), exist_ok=True)

subprocess.run([
    'keytool', '-genkey', '-v',
    '-keystore', ks,
    '-alias', 'debug',
    '-keyalg', 'RSA', '-keysize', '2048',
    '-validity', '10000',
    '-storepass', 'android',
    '-keypass', 'android',
    '-dname', 'CN=Android,O=Android,C=US'
], check=False, capture_output=True)

android_home = os.environ.get('ANDROID_HOME', '/usr/local/lib/android/sdk')
apksigner = os.path.join(android_home, 'build-tools/34.0.0/apksigner')

subprocess.run([
    apksigner, 'sign',
    '--ks', ks,
    '--ks-pass', 'pass:android',
    '--ks-key-alias', 'debug',
    '--key-pass', 'pass:android',
    apk
], check=True)

print("Re-signed successfully")
