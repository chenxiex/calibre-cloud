# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/devcontainers/base:2-trixie

SHELL ["/bin/bash", "-e", "-o", "pipefail", "-c"]

ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_API_LEVEL=36
ARG ANDROID_BUILD_TOOLS_VERSION=36.0.0
ARG GRADLE_VERSION=9.3.1
ARG GRADLE_SHA256=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06

# Google's Linux SDK binaries require x86-64. The devcontainer selects this platform.
# Android builds use Trixie's JDK 21; the Java editor extension bundles its own runtime.
RUN test "$(dpkg --print-architecture)" = amd64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        libusb-1.0-0 \
        openjdk-21-jdk-headless \
        python3 \
        ripgrep \
        shellcheck \
        sqlite3 \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    ANDROID_SDK_SEED=/opt/android-sdk-seed \
    ANDROID_HOME=/home/vscode/.local/share/android-sdk \
    ANDROID_USER_HOME=/home/vscode/.android \
    GRADLE_USER_HOME=/home/vscode/.gradle \
    XDG_CACHE_HOME=/home/vscode/.cache \
    ANDROID_API_LEVEL=${ANDROID_API_LEVEL} \
    ANDROID_BUILD_TOOLS_VERSION=${ANDROID_BUILD_TOOLS_VERSION} \
    BUNDLED_GRADLE_VERSION=${GRADLE_VERSION}

ENV ANDROID_SDK_ROOT=${ANDROID_HOME} \
    GRADLE_HOME=/opt/gradle/gradle-${GRADLE_VERSION} \
    GRADLE_DISTRIBUTION_URL=https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip

# Keep the image's SDK separate from the writable SDK volume so a mount cannot hide it.
# sdkmanager is given the seed path explicitly while building the image.
RUN mkdir -p "${ANDROID_SDK_SEED}/cmdline-tools" \
    && curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip" \
        --output /tmp/android-command-line-tools.zip \
    && printf '%s  %s\n' "${ANDROID_COMMAND_LINE_TOOLS_SHA256}" /tmp/android-command-line-tools.zip | sha256sum --check - \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_SDK_SEED}/cmdline-tools/latest" \
    && (set +o pipefail; yes | "${ANDROID_SDK_SEED}/cmdline-tools/latest/bin/sdkmanager" \
        --sdk_root="${ANDROID_SDK_SEED}" --licenses > /dev/null) \
    && "${ANDROID_SDK_SEED}/cmdline-tools/latest/bin/sdkmanager" \
        --sdk_root="${ANDROID_SDK_SEED}" --channel=0 \
        "platform-tools" \
        "platforms;android-${ANDROID_API_LEVEL}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip

# Retain the verified ZIP as a Wrapper cache seed; install Gradle for project bootstrap.
RUN mkdir -p /opt/gradle /opt/gradle-distributions \
    && curl --fail --location --retry 3 "${GRADLE_DISTRIBUTION_URL}" \
        --output "/opt/gradle-distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && printf '%s  %s\n' "${GRADLE_SHA256}" "/opt/gradle-distributions/gradle-${GRADLE_VERSION}-bin.zip" | sha256sum --check - \
    && unzip -q "/opt/gradle-distributions/gradle-${GRADLE_VERSION}-bin.zip" -d /opt/gradle

COPY scripts/post-create.sh scripts/seed-gradle-wrapper.py /usr/local/share/calibre-devcontainer/

ENV PATH=${JAVA_HOME}/bin:${GRADLE_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}:${PATH}

USER vscode
RUN mkdir -p "${GRADLE_USER_HOME}" "${ANDROID_HOME}" "${ANDROID_USER_HOME}" "${XDG_CACHE_HOME}"
