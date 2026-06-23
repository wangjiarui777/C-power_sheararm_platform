# Windows Server production layout

Production releases are immutable version directories:

```text
D:\phm\
  releases\2026.06.23.1\
  current -> releases\2026.06.23.1
  previous -> releases\2026.06.16.1
  data\
  logs\
  backup\
```

Only Nginx listens on the plant-facing HTTPS address. Java and Python bind to
`127.0.0.1`; MySQL, Redis, IoTDB and acquisition TCP ports are restricted by
Windows Firewall to the industrial network.

Copy `winsw.exe` beside each XML file, replace the environment placeholders,
then run `install-services.ps1` as Administrator. Never run Maven, npm, pip,
javac, or model downloads on a production server.

The signed offline bundle must contain:

- JRE 17 and the pinned Python runtime/wheels;
- `ruoyi-admin.jar`, frontend `dist`, Nginx and WinSW;
- the unified inference service and model artifacts;
- Flyway migrations, model SHA-256 manifest and SBOM;
- the reliable edge gateway reference JAR and its deployment guide;

`build-offline-package.ps1` requires explicit JRE 17, embeddable Python, Nginx,
fixed wheelhouse, WinSW and a code-signing certificate with a private key. It emits
`phm-<version>.zip`, detached `p7s` signature and the public signing certificate.
Run `verify-offline-package.ps1` before extracting or installing the package.
- these service definitions, backup scripts and smoke tests.
