# mega-downloader CLI, containerized for a headless cloud VM.
#
# IMPORTANT, empirically confirmed during development: this image does NOT
# run with java.awt.headless=true. MainPanelView and the Settings dialogs
# are kept in the codebase as never-shown objects rather than deleted (see
# the project's migration notes) -- constructing a real JFrame/JDialog
# throws HeadlessException immediately under true AWT headless mode (not
# just on setVisible(true)), so the entrypoint starts a virtual framebuffer
# (Xvfb) instead, giving AWT a real (if invisible) display to construct
# against. Nothing in the CLI path ever calls setVisible(true), so nothing
# is actually rendered -- Xvfb just satisfies AWT's constructor checks.
#
# ExpressVPN's Linux app is distributed as a self-extracting .run installer
# (not a .deb) -- you must supply it yourself (download it from your
# ExpressVPN account) as a build context file. See README.md for the full
# build/run instructions.

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Cache dependency resolution separately from source changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
# Debian-based (not Alpine/musl): ExpressVPN's installer targets glibc, and
# Xvfb/its X11 dependencies are far better supported on Debian than Alpine.
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# xvfb/ffmpeg/sqlite3/ca-certificates are needed by mega-downloader itself
# (verified). procps/psmisc/libatomic1/libglib2.0-0/libbrotli1/
# libnl-genl-3-200/libnl-route-3-200/dbus are exactly what ExpressVPN's own
# .run installer's dependency checker and internal `apt-get install` calls
# have demanded across real install attempts -- iptables/network-manager
# are its commonly-cited *runtime* deps (its CLI shells out to nmcli/
# iptables for DNS and firewall rules), still best-effort.
#
# Deliberately NOT `rm -rf /var/lib/apt/lists/*` here (moved to after the
# ExpressVPN install below instead): the installer shells out to its own
# `apt-get install` for at least one additional dependency
# (libnl-route-3-200), and an empty package index at that point fails with
# "Unable to locate package" even though the package exists -- confirmed
# on a real build attempt.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        ffmpeg \
        sqlite3 \
        ca-certificates \
        iptables \
        network-manager \
        libnl-genl-3-200 \
        libnl-route-3-200 \
        procps \
        psmisc \
        libatomic1 \
        libglib2.0-0 \
        libbrotli1 \
        dbus

# ExpressVPN .run installer, supplied at build time: place the file you
# downloaded from your ExpressVPN account next to this Dockerfile (default
# expected name: expressvpn.run), or point at a different filename with:
#   docker build --build-arg EXPRESSVPN_RUN=expressvpn_x.y.z_amd64.run .
ARG EXPRESSVPN_RUN=expressvpn.run
COPY ${EXPRESSVPN_RUN} /tmp/expressvpn.run
# .run installers are self-extracting shell scripts (makeself-style) and
# are commonly interactive (EULA prompt, etc.), which a `docker build` step
# has no TTY to answer. `yes |` auto-answers any yes/no prompt with "y" --
# confirmed working against the real ExpressVPN installer (it gets past
# the archive-extraction/EULA stage fine).
#
# The installer also tries to register/enable a systemd service
# (expressvpn-service) via systemctl/D-Bus, which fails outright in a
# plain container -- confirmed on a real build attempt ("System has not
# been booted with systemd as init system... Failed to connect to bus").
# Every other install step (copying files, adding groups, setting
# permissions) succeeds; only that last systemd-registration step doesn't,
# and it aborts the whole script when it fails. Two combined workarounds,
# both scoped to just this RUN layer (a background dbus-daemon started
# here does NOT survive into later layers, so it must run in the same
# instruction that needs it):
#   1. /run/systemd/system is systemd's own on-disk signal that "systemd
#      is the running init" (sd_booted()) -- satisfies tools that only
#      check for this before deciding whether to attempt anything further.
#   2. A real (temporary, build-time-only) system D-Bus daemon, in case the
#      installer needs an actual bus connection, not just the marker.
# Neither makes systemd actually manage the expressvpn-service unit --
# docker-entrypoint.sh starts the expressvpnd daemon directly instead,
# which is what actually matters at runtime. This is a best-effort fix
# based on the exact error text the installer produced, not a confirmed
# working combination -- if the build still fails here, the next error
# will narrow it down further.
RUN mkdir -p /run/dbus /run/systemd/system \
    && dbus-daemon --system --fork \
    && sleep 1 \
    && chmod +x /tmp/expressvpn.run \
    && (yes | /tmp/expressvpn.run) \
    && rm -f /tmp/expressvpn.run \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/mega-downloader-*-jar-with-dependencies.jar /app/mega-downloader.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# All persisted state (SQLite DB, downloaded chunks if configured there,
# ExpressVPN activation state) lives under $HOME. Deliberately NOT the
# app's "portable mode" (a marker file next to the jar) -- that would pin
# MEGABASTERD_HOME_DIR to /app itself, which would need the whole /app
# directory (jar included) inside the volume. A dedicated $HOME=/data
# keeps app binaries and user data cleanly separated, and survives image
# upgrades (MainPanel's own version-migration logic copies the DB forward
# the first time a new image version sees an existing /data volume).
ENV HOME=/data
RUN mkdir -p /data
VOLUME ["/data"]

# Requires, at `docker run` time: --cap-add=NET_ADMIN --device=/dev/net/tun
# (ExpressVPN needs to create a tun interface) -- see README.md.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["--help"]
