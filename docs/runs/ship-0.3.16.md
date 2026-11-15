# Ship provenance - 0.3.16

Raw output of `tooling\h41_ship.cmd v0316` (build identity, certificate
continuity, hashes, install smoke).

```text
SUBTASK H41 - SHIP (tag=v0316)
============================================================================
[1/6] assembleDebug + assembleRelease ...
      build rc=0  (see build.log)
[2/6] aapt2 dump badging ...
      versionCode=19 versionName=0.3.16
package: name='com.docscanai' versionCode='19' versionName='0.3.16' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'26'
targetSdkVersion:'35'
application-label:'DocScan AI'
native-code: 'arm64-v8a' 'x86_64'
[3/6] apksigner verify --print-certs ...
      apksigner rc=0
Signer #1 certificate DN: CN=DocScanAI, OU=Release, O=DocScanAI, L=Istanbul, C=TR
Signer #1 certificate SHA-256 digest: 7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc
Signer #1 certificate SHA-1 digest: 006e52ad2063247ab2ded48856402e8f2e3501b9
Signer #1 certificate MD5 digest: 849d4e0bf06cd6b5720220dc505aac16
expected: 7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc
present : True
      CERT CONTINUITY OK
[4/6] stage + hash ...
      DocScanAI-0.3.16.apk  125561618 B  SHA-256 7a2a17a4ba63f0974c25bdb8f24b148bdf449f2a8b5535ff2aeacfc56cd68065
      DocScanAI-0.3.16-debug.apk  144683979 B  SHA-256 81ca946a736a632b2e401ecdb4947155a7db32969f3087f3a4ceee0c63885b61
DocScanAI-0.3.16.apk  125561618 bytes
SHA-256 7a2a17a4ba63f0974c25bdb8f24b148bdf449f2a8b5535ff2aeacfc56cd68065
DocScanAI-0.3.16-debug.apk  144683979 bytes
SHA-256 81ca946a736a632b2e401ecdb4947155a7db32969f3087f3a4ceee0c63885b61
versionCode=19 versionName=0.3.16
cert SHA-256 7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc
[5/6] install + launch smoke ...
      install rc=0
      smoke OK - no crash markers (launch_screen.png
      smoke CRASH MARKERS FOUND - see smoke_logcat.txt
[6/6] done - assets under C:\Users\Kuzey\Desktop\DocScanAI\tooling\out\h41\v0316
============================================================================
```
