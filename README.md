# Zenbon POS

Point-of-sale system for restaurants and retail — a server with a web back office,
an Android client, and optional browser tills.

Releases are published here. Download the assets for your platform from the
[latest release](../../releases/latest). The repository also carries the source code
of each release, as one snapshot commit per version (`Zenbon POS vX.Y.Z source`).

## Components

| Asset | What it is |
|---|---|
| `zenbon-pos-server-<version>.zip` | Server package — application JAR, bundled Java runtime (Eclipse Temurin 21), startup and service-install scripts, license files |
| `zenbon-pos-<version>.apk` | Android client |
| `pos-server-<version>.jar`, `pos-server-<version>.jar.cdir.gz` | The server JAR on its own, for the in-app updater's small delta downloads — you don't need these for a fresh install |
| `SHA256SUMS` | Checksums of the assets — verify with `sha256sum -c SHA256SUMS` |
| `THIRD-PARTY-LICENSES.txt`, `sbom.json` | Licenses of all bundled third-party software, and a CycloneDX software bill of materials (releases after v1.1.0) |

The server bundles its own minimal Java runtime and, by default, an embedded
database — no separate Java or PostgreSQL install is required.

**The server needs an x86_64 (amd64) Linux host.** The bundled database binaries
and the TSE driver libraries are x86_64 only; on ARM the server refuses to start
the embedded database and says so.

## Installing the server

```bash
unzip zenbon-pos-server-<version>.zip
cd deploy
chmod +x run.sh
./run.sh
```

To install it as a systemd service instead:

```bash
sudo ./install-service.sh
```

Configuration, logs, and the embedded database live under `~/POS Server/`.
Set `DATABASE_MODE=external` to point the server at your own PostgreSQL instance
rather than the bundled one.

## Installing the clients

Android — enable installation from unknown sources, then install the APK:

```bash
adb install zenbon-pos-<version>.apk
```

Clients discover the server over the local network; point them at the server's
host and port on first launch.

**Back office and browser tills:** open `http://<server>:<port>/` in a browser.
The start page links to the back office (`/admin/`) and, once a browser-station
role is enabled in the back office under *Settings → Browser-Stationen*, to the
browser till (`/pos/`). A browser till is online-only and prints through the
server.

## TSE hardware support

German deployments require a certified TSE (Technische Sicherheitseinrichtung).
The server drives a Swissbit TSE attached to the server host directly, through
Swissbit's JNI SDK. **These public builds do not ship that SDK**, because it is
proprietary and cannot be redistributed here. You must obtain it from Swissbit
and install it yourself.

### Supplying your own TSE runtime

Create a `native/` folder next to `pos-server.jar` and place the SDK inside it.
The server loads `native/WormAPI.jar` through a `Class-Path` entry in its own
manifest, and the JNI libraries through `java.library.path`, which `run.sh` sets
to the architecture subfolder. **The names and locations below must match exactly** —
they are what `System.loadLibrary` and the manifest look for.

```
deploy/
├── pos-server.jar
├── run.sh
└── native/
    ├── WormAPI.jar               ← Swissbit Java SDK
    ├── linux64/
    │   ├── libWormAPI.so
    │   └── libWormAPIJni.so
    └── linux32/
        ├── libWormAPI.so
        └── libWormAPIJni.so
```

Only the subfolder matching your platform is needed; `run.sh` selects `linux64`
or `linux32` from `uname -m`.

Without these files the server still starts and every non-fiscal feature works,
but **a German till cannot sell**:

- the local TSE connection cannot be selected (the server rejects it);
- in NORMAL mode terminals cannot register a TSE client, so bills are refused
  before they are created.

SIMULATION mode produces fake signatures and is for development only. Outside
Germany, set the TSE mode to DISABLED.

## Building from source

The tree is the source of the released version, without the proprietary Swissbit TSE SDK
(see above) and without the project's internal notes and tooling. It is a Gradle build
(JDK 21; the Android client also needs the Android SDK):

```bash
./gradlew :pos-server:run          # server in development mode
```

The `pos-tse` module (the TSE driver) compiles against Swissbit's `WormAPI.jar`, which is
**not** in this repository. Before building, copy the jar from your SDK to
`pos-tse/lib/WormAPI.jar`; to run against real TSE hardware also copy the matching JNI
libraries to `pos-tse/src/main/resources/native/<linux64|linux32|windows64|windows32>/`.
Use the SDK version your TSE hardware needs (TSE 2.0 needs 6.0 or newer). Without the jar,
`:pos-tse` and everything that depends on it does not compile.

Only the server's compile step (`:pos-server:compileKotlin`) has been verified from this
public tree; the release assets are built from the full private tree.

## Support

Report problems through the [issue tracker](../../issues). Include the version
from the `VERSION` file in your server package and the relevant lines from
`~/POS Server/.data/log/`.

## License

Zenbon POS is released under the MIT License — see [LICENSE](LICENSE). From the
release after v1.1.0, each server package also carries a `LICENSE.md` saying
the same for that package.

The software it is built with keeps its own licenses. From the release after
v1.1.0, every release lists them,
with their full texts, in `THIRD-PARTY-LICENSES.txt` (inside the server package
and attached to the release), together with a CycloneDX SBOM, `sbom.json`. Both
are generated from what is actually shipped. Releases contain only open-source,
freely redistributable components (permissive licenses, plus LGPL, MPL-2.0, EPL
and CDDL), enforced by a license check on every release build, with these
proprietary exceptions, which are **not** covered by the MIT License:

- **Google ML Kit and Google Play services** (Android app only) — document and
  QR-code scanning, under Google's own terms. Replacing it with an open-source
  scanner is planned.
- **Swissbit TSE SDK** — not distributed here and not part of any release in this
  repository. Obtain it from Swissbit under their terms (see above).
