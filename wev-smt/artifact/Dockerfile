# syntax=docker/dockerfile:1
#
# WEV-SMT POPL artifact image. One `docker run` reproduces every eval result:
# the 34-test suite, the weak-memory atlas (190/190/0), both scalability sweeps
# (Day-9 consistency + Day-12 fence/RMW), an optional corpus hierarchy-soundness
# check, and all plots.
#
# The build context is the wev-smt/ subproject, so the whole project is copied in
# (this is the COPY ../wev-smt that the spec calls for — Docker cannot COPY from
# outside the context, so we make wev-smt/ the context):
#
#   cd wev-smt
#   docker build --provenance=false -f artifact/Dockerfile -t wev-smt-artifact:adb65a7 .
#
# (--provenance=false drops BuildKit's attestation manifest, keeping the image
# under 1.5 GB; the build works without it too, just larger.)
#
# Run (Functional badge — no corpus needed):
#   docker run --rm wev-smt-artifact:adb65a7
# Run with the optional corpus check (Reusable badge):
#   docker run --rm -v /abs/path/to/litmus:/corpus wev-smt-artifact:adb65a7
#
# Base: Eclipse Temurin JDK 21 on Ubuntu 24.04 (noble), linux/amd64. For a fully
# reproducible build, pin by digest (FROM eclipse-temurin:21-jdk-noble@sha256:...);
# the digest is printed by `docker inspect` after the first build and is recorded
# in artifact/README.md.
FROM eclipse-temurin:21-jdk-noble

# Lean toolchain (keeps the image under the 1.5 GB target):
#   * Maven from the official Apache binary — reuses the image's JDK 21 instead of
#     dragging in a second JRE the way the apt `maven` package does (~150 MB saved).
#   * Python plotting via pip (matplotlib + pandas + numpy, headless Agg backend) —
#     the apt python3-matplotlib/python3-pandas pull a heavy scientific stack
#     (scipy/sympy/tk, ~200 MB) that these scripts never use.
#   * libgomp1 is the one native lib the Z3 shared library links against.
# Network is used at BUILD time only; reproduce.sh runs fully offline.
ARG MAVEN_VERSION=3.9.9
ENV MAVEN_HOME=/opt/maven PATH=/opt/maven/bin:$PATH
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl python3 python3-pip libgomp1; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1; \
    rm -f /tmp/maven.tar.gz; \
    pip3 install --no-cache-dir --break-system-packages matplotlib pandas; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/* /root/.cache/pip; \
    mvn -v

WORKDIR /opt/wev-smt

# wev-smt depends on the WEV visualiser's model classes, published as the shaded,
# dependency-reduced jar com.weakest:visualising-weakest-executions:1.0-SNAPSHOT
# (218 KB, ZERO transitive deps — no JavaFX/GraphStream). That jar is not on Maven
# Central, so we vendor it (artifact/lib/) and install it into the image's local
# repository. This keeps the artifact self-contained without dragging the
# visualiser's GUI dependency tree into the image.
COPY artifact/lib/ /opt/wev-smt/artifact/lib/
RUN mvn -B -q install:install-file \
      -Dfile=artifact/lib/visualising-weakest-executions-1.0-SNAPSHOT.jar \
      -DpomFile=artifact/lib/visualising-weakest-executions-1.0-SNAPSHOT.pom

# Pre-resolve dependencies from the pom alone (now that the visualiser is installed),
# so editing source does not bust the Maven-download layer. Tolerant (|| true): the
# authoritative download is the `mvn test` below; this is only a caching optimisation.
COPY pom.xml /opt/wev-smt/pom.xml
RUN mvn -B -q dependency:go-offline || true

# Copy the project (.dockerignore drops eval/corpus, target, .git, *.class, ...).
COPY . /opt/wev-smt

# Warm the cache and verify the build deterministically: this downloads JavaSMT and
# the linux-x64 Z3 native (libz3.so / libz3java.so, via the OS-activated Maven
# profile) into ~/.m2 and runs all 34 tests; `dependency:resolve-plugins` additionally
# fetches the exec-maven-plugin used by the reproduction. After this layer every
# `mvn -o` in reproduce.sh runs fully offline.
RUN mvn -B -q -DskipITs test \
 && mvn -B -q dependency:resolve-plugins \
 && chmod +x /opt/wev-smt/artifact/reproduce.sh

ENTRYPOINT ["/opt/wev-smt/artifact/reproduce.sh"]
