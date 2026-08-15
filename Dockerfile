# MegaBasterd CLI, containerized for a headless cloud VM.
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

# xvfb/ffmpeg/sqlite3/ca-certificates are needed by MegaBasterd itself
# (verified). iptables/network-manager/libnl-genl-3-200 are ExpressVPN's
# commonly-cited Linux dependencies (its CLI shells out to nmcli/iptables
# for DNS and firewall rules) -- best-effort, NOT verified against a real
# ExpressVPN package in this environment (none was available to test
# against). If `expressvpn activate`/`connect` complains about a missing
# tool, install it here.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        ffmpeg \
        sqlite3 \
        ca-certificates \
        iptables \
        network-manager \
        libnl-genl-3-200 \
    && rm -rf /var/lib/apt/lists/*

# ExpressVPN .run installer, supplied at build time: place the file you
# downloaded from your ExpressVPN account next to this Dockerfile (default
# expected name: expressvpn.run), or point at a different filename with:
#   docker build --build-arg EXPRESSVPN_RUN=expressvpn_x.y.z_amd64.run .
ARG EXPRESSVPN_RUN=expressvpn.run
COPY ${EXPRESSVPN_RUN} /tmp/expressvpn.run
# .run installers are self-extracting shell scripts (makeself-style) and
# are commonly interactive (EULA prompt, etc.), which a `docker build` step
# has no TTY to answer. `yes |` auto-answers any yes/no prompt with "y".
# UNTESTED against the real ExpressVPN installer in this environment (none
# was available) -- if the build fails here, run
# `sh expressvpn.run --help` (or just `sh expressvpn.run` interactively)
# locally first to find its actual non-interactive/silent-install flag
# (often something like --quiet, --accept-eula, or -y) and use that
# instead of piping `yes`.
RUN chmod +x /tmp/expressvpn.run \
    && (yes | /tmp/expressvpn.run) \
    && rm -f /tmp/expressvpn.run

WORKDIR /app

COPY --from=build /build/target/MegaBasterd-*-jar-with-dependencies.jar /app/megabasterd.jar
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
