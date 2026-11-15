# Ship provenance - 0.3.15

Raw output of `tooling\h41_ship.cmd v0315` (build identity, certificate
continuity, hashes, install smoke).

```text
SUBTASK H41 - SHIP (tag=v0315)
============================================================================
[1/6] assembleDebug + assembleRelease ...
      build rc=0  (see build.log)
[2/6] aapt2 dump badging ...
      versionCode=18 versionName=0.3.15
package: name='com.docscanai' versionCode='18' versionName='0.3.15' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
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
      DocScanAI-0.3.15.apk  125561614 B  SHA-256 dfbcb1ea1bfab77af322aff8bf0b005825b14d09ab655e36193bf4c811d5c806
      DocScanAI-0.3.15-debug.apk  144486553 B  SHA-256 e07d43b534a43f73a09ba96d23cfd0311757fd2e07387fc6ee792703cfbd52e5
DocScanAI-0.3.15.apk  125561614 bytes
SHA-256 dfbcb1ea1bfab77af322aff8bf0b005825b14d09ab655e36193bf4c811d5c806
DocScanAI-0.3.15-debug.apk  144486553 bytes
SHA-256 e07d43b534a43f73a09ba96d23cfd0311757fd2e07387fc6ee792703cfbd52e5
versionCode=18 versionName=0.3.15
cert SHA-256 7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc
[5/6] install + launch smoke ...
      no device attached - smoke SKIPPED
[6/6] done - assets under C:\Users\Kuzey\Desktop\DocScanAI\tooling\out\h41\v0315
============================================================================
```
