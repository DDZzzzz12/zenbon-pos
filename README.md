# Zenbon POS

Point-of-sale system for restaurants and retail — a server plus Android and Linux desktop clients.

Releases are published here. Download the assets for your platform from the
[latest release](../../releases/latest).

## Components

| Asset | What it is |
|---|---|
| `zenbon-pos-server-<version>.zip` | Server package — application JAR, bundled Java runtime, startup and service-install scripts |
| `zenbon-pos-<version>.apk` | Android client |
| `zenbon-pos-<version>.deb` | Linux desktop client (x86-64) |

The server bundles its own minimal Java runtime and, by default, an embedded
database — no separate Java or PostgreSQL install is required.

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

Linux desktop:

```bash
sudo dpkg -i zenbon-pos-<version>.deb
```

Clients discover the server over the local network; point them at the server's
host and port on first launch.

## TSE hardware support

German deployments require a certified TSE (Technische Sicherheitseinrichtung).
The server talks to TSE hardware in two ways:

- **REMOTE mode** — the server talks to a TSE connector over the network. Works
  out of the box with these releases; nothing extra to install.
- **LOCAL mode** — the server drives a directly attached Swissbit TSE through its
  JNI SDK. **These public builds do not ship that SDK**, because it is proprietary
  and cannot be redistributed here. You must obtain it from Swissbit and install
  it yourself.

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
    ├── linux32/
    │   ├── libWormAPI.so
    │   └── libWormAPIJni.so
    ├── windows64/
    │   ├── WormAPI.dll
    │   └── WormAPIJni.dll
    └── windows32/
        ├── WormAPI.dll
        └── WormAPIJni.dll
```

Only the subfolder matching your platform is needed; `run.sh` selects `linux64`
or `linux32` from `uname -m`. Ship all four if you build one image for mixed
hardware.

The server starts and runs normally when these files are absent — it prints a
warning at startup and TSE LOCAL mode stays unavailable. REMOTE mode and every
non-fiscal feature are unaffected.

## Support

Report problems through the [issue tracker](../../issues). Include the version
from the `VERSION` file in your server package and the relevant lines from
`~/POS Server/.data/log/`.

## License

Released under the MIT License — see [LICENSE](LICENSE).

The Swissbit TSE SDK is **not** covered by that license and is not distributed
here. Obtain it from Swissbit under their terms.
