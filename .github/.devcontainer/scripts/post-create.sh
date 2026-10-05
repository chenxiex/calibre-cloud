#!/usr/bin/env bash
set -euo pipefail

# Named volumes may retain an earlier UID/GID after Dev Containers updates the user.
user_id=$(id -u)
group_id=$(id -g)
for directory in "$GRADLE_USER_HOME" "$ANDROID_HOME" "$ANDROID_USER_HOME" "$XDG_CACHE_HOME"; do
    sudo mkdir -p "$directory"
    if [[ $(stat -c '%u:%g' "$directory") != "$user_id:$group_id" ]]; then
        sudo chown -R "$user_id:$group_id" "$directory"
    fi
done

# Copy whole missing packages. Preserve SDK packages installed or upgraded in the volume.
sdk_staging=""
license_staging=""
cleanup() {
    if [[ -n "$sdk_staging" ]]; then
        rm -rf -- "$sdk_staging"
    fi
    if [[ -n "$license_staging" ]]; then
        rm -f -- "$license_staging"
    fi
}
trap cleanup EXIT
for package in \
    "cmdline-tools/latest" \
    "platform-tools" \
    "platforms/android-$ANDROID_API_LEVEL" \
    "build-tools/$ANDROID_BUILD_TOOLS_VERSION"; do
    destination="$ANDROID_HOME/$package"
    if [[ ! -d "$destination" ]]; then
        sdk_staging=$(mktemp -d "$ANDROID_HOME/.devcontainer-seed.XXXXXX")
        rsync -rlpt "$ANDROID_SDK_SEED/$package/" "$sdk_staging/package/"
        mkdir -p "$(dirname "$destination")"
        mv "$sdk_staging/package" "$destination"
        rmdir "$sdk_staging"
        sdk_staging=""
    fi
done

mkdir -p "$ANDROID_HOME/licenses"
# License files contain accepted hashes; retain old hashes and add the seed's new ones.
for license in "$ANDROID_SDK_SEED/licenses/"*; do
    [[ -f "$license" ]] || continue
    destination="$ANDROID_HOME/licenses/$(basename "$license")"
    license_staging=$(mktemp "$ANDROID_HOME/licenses/.devcontainer-license.XXXXXX")
    {
        cat "$license"
        if [[ -f "$destination" ]]; then
            cat "$destination"
        fi
    } | awk 'NF' | sort -u > "$license_staging"
    mv "$license_staging" "$destination"
    license_staging=""
done

python3 /usr/local/share/calibre-devcontainer/seed-gradle-wrapper.py \
    "$GRADLE_DISTRIBUTION_URL" \
    "/opt/gradle-distributions/gradle-$BUNDLED_GRADLE_VERSION-bin.zip" \
    "$GRADLE_USER_HOME"

printf '%s\n' 'Android 开发环境已初始化；SDK 和 Gradle 缓存位于持久 volume。'
