<h1>mega-downloader</h1>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

<p align="center"><b>IMPORTANT:</b> You are not authorized to use this tool in any way that violates <a href="https://mega.io/es/terms"><b>MEGA's terms of use</b></a>.</p>

A command-line MEGA downloader/uploader/streaming tool for headless/cloud use, with ExpressVPN-based bandwidth-quota bypass. This is a fork of <a href="https://github.com/tonikelope/megabasterd">tonikelope/megabasterd</a> (GPLv3), a Swing desktop app that used an open-proxy pool to dodge MEGA's daily bandwidth quota. This fork:

- Removes the Swing GUI from the runtime path -- everything is driven from a command-line interface, intended for running unattended on a cloud VM or in Docker. (The Swing classes are still in the source tree, kept as inert never-shown plumbing rather than deleted, to avoid destabilizing the underlying download/upload engine -- see "Internals" below.)
- Replaces the open-proxy pool with ExpressVPN-based IP rotation -- open proxies are unreliable and risky for an unattended server process; a real VPN subscription is not.

## Running the CLI directly

```
java -jar mega-downloader-8.58-jar-with-dependencies.jar --help
java -jar mega-downloader-8.58-jar-with-dependencies.jar download https://mega.nz/... --dir /path/to/downloads
java -jar mega-downloader-8.58-jar-with-dependencies.jar accounts add you@example.com
java -jar mega-downloader-8.58-jar-with-dependencies.jar settings set vpn_enabled yes
java -jar mega-downloader-8.58-jar-with-dependencies.jar vpn status
```

Run `<subcommand> --help` for each subcommand's options (`download`, `upload`, `stream`, `folder-list`, `accounts`, `settings`, `vpn`). Requires Java 17+ to run the prebuilt jar (built targeting bytecode 1.8, so it should also run under Java 8-16, but that's untested).

**Note:** even though this is a CLI, the JVM does not run with `-Djava.awt.headless=true` -- the kept-but-unused Swing classes need AWT to see a real (or virtual) display to even construct, not just to be shown. On a normal desktop this is a non-issue; in Docker/on a display-less server you need a virtual framebuffer (Xvfb), which the provided Docker image sets up for you automatically.

## VPN settings

Configured the same way as any other setting, via `settings set <key> <value>`:

| Key | Meaning | Default |
|---|---|---|
| `vpn_enabled` | Master on/off switch | `no` |
| `force_vpn` | `yes` = stay connected the whole run; `no` = connect reactively on a MEGA 509/quota error | `no` |
| `vpn_locations` | Comma-separated ExpressVPN location codes to rotate through, e.g. `usa,uk,germany`. Leave unset/empty to let ExpressVPN pick ("smart location") | *(smart)* |
| `vpn_cooldown` | Seconds before a just-used/failed location is eligible again | `300` |
| `vpn_connect_timeout` | Max seconds to wait for `expressvpn connect` before falling back to a direct connection | `30` |
| `vpn_509_recheck_window` | How long (seconds) a download stays in "VPN-protected" mode after its last 509, even if a chunk succeeds in between | `3600` |

Requires the `expressvpn` CLI to be installed, activated, and on `PATH` (the Docker image handles this at build/run time -- see below).

## Docker

```
# 1. Download your ExpressVPN Linux installer (a self-extracting .run
#    file) from your ExpressVPN account and place it in this directory as
#    expressvpn.run (or pass --build-arg EXPRESSVPN_RUN=<filename> if you
#    keep its original name).
# 2. Copy .env.example to .env and fill in EXPRESSVPN_ACTIVATION_CODE.

docker compose build
docker compose run --rm mega-downloader download https://mega.nz/... --dir /data/downloads
docker compose run --rm mega-downloader vpn status
```

Or with plain `docker run` (the two flags after `--cap-add` are both required -- ExpressVPN needs to create a `tun` network interface):

```
docker build --build-arg EXPRESSVPN_RUN=expressvpn.run -t mega-downloader .
docker run --rm \
  --cap-add=NET_ADMIN --device=/dev/net/tun \
  -e EXPRESSVPN_ACTIVATION_CODE=your-code-here \
  -v mega-downloader-data:/data \
  mega-downloader download https://mega.nz/... --dir /data/downloads
```

This needs a real VM-backed Docker host (a plain cloud VM with Docker Engine installed) -- managed/serverless container platforms (Cloud Run, Fargate, etc.) generally don't allow `NET_ADMIN`/TUN devices.

The ExpressVPN installer's exact invocation, dependencies, and daemon-start command can vary by version; `docker-entrypoint.sh` and the `Dockerfile` document where to adjust them if the build or `expressvpn activate`/`connect` fails inside the container. This Docker setup has not been build/run-tested against a real ExpressVPN installer in development (no installer or activation code was available) -- verify it end-to-end with your own before relying on it.

## Internals

For anyone digging into the source: the Swing GUI classes (`MainPanelView`, the `*Dialog` classes) are intentionally still present and still compile, but are never constructed as visible windows in the CLI path -- deleting them turned out to cascade into signature changes across core, non-GUI classes (`MegaCrypterAPI`, etc.), so they were left in place as inert plumbing instead. `MainPanel.isCliMode()` (not AWT's own headless flag) is what actually routes the handful of blocking-dialog call sites (login/master-password/2FA prompts) to the console instead. See the CLI entry point at `src/main/java/com/tonikelope/megabasterd/cli/CliMain.java` and the VPN manager at `src/main/java/com/tonikelope/megabasterd/VpnRotationManager.java`.

## License

GPLv3, inherited from the upstream project. See `LICENSE`.
