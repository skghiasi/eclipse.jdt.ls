#!/usr/bin/env bash
# Builds this fork's archive: the official JDT Language Server 1.61.0 with the classes of the files listed in
# patched-sources.txt -- compiled from this tree -- put into its core jar. Everything else in the archive is the
# official release, byte for byte.
#
#   batch-facts/build.sh [output dir]        (default: ./dist)
#
# Needs: bash, git, curl, tar, unzip, sha256sum, and a JDK 21 or newer (JAVA_HOME, or `javac` and `jar` on the PATH).
# Prints the name and SHA-256 of the archive it wrote, and also writes it under the fixed name
# jdt-language-server-batch.tar.gz (with a .sha256 beside it) for clients that fetch the newest release.
set -euo pipefail

OFFICIAL_URL="https://download.eclipse.org/jdtls/milestones/1.61.0/jdt-language-server-1.61.0-202609031315.tar.gz"
# The official archive's checksum, as Eclipse publishes it. The build refuses to start from anything else.
OFFICIAL_SHA256="338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64"
BASE_TAG="v1.61.0"
BASE_VERSION="1.61.0"
REVISION="${ARCHIVE_REVISION:-1}"
NAME="jdt-language-server-${BASE_VERSION}-batch.${REVISION}.tar.gz"

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
out=${1:-dist}
mkdir -p "$out"
out=$(cd "$out" && pwd)   # absolute: the archive is written from inside another directory
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
jar_tool=${JAVA_HOME:+$JAVA_HOME/bin/}jar
command -v "$javac" >/dev/null || { echo "no javac: set JAVA_HOME to a JDK 21 or newer" >&2; exit 1; }

# The list must be every Java file this tree changes under the core plugin since the release: a changed file that is
# not on it would be built nowhere, and the archive would silently lack the change.
changed=$(git -C "$root" diff --name-only "$BASE_TAG" -- 'org.eclipse.jdt.ls.core/src' 'org.eclipse.jdt.ls.core/plugin.xml' | sort)
listed=$(sort "$here/patched-sources.txt")
[ "$changed" = "$listed" ] || {
  echo "the files changed since $BASE_TAG are not the files in batch-facts/patched-sources.txt:" >&2
  diff <(echo "$listed") <(echo "$changed") >&2 || true
  exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/tree" "$work/classes"

echo "downloading the official archive"
curl -fsSL --retry 3 -o "$work/official.tar.gz" "$OFFICIAL_URL"
echo "$OFFICIAL_SHA256  $work/official.tar.gz" | sha256sum -c - >/dev/null \
  || { echo "the official archive does not match its published checksum; not building" >&2; exit 1; }
tar -xzf "$work/official.tar.gz" -C "$work/tree"

core=$(ls "$work"/tree/plugins/org.eclipse.jdt.ls.core_*.jar)
[ "$(echo "$core" | wc -l)" = 1 ] || { echo "expected exactly one org.eclipse.jdt.ls.core jar" >&2; exit 1; }
classpath=$(ls "$work"/tree/plugins/*.jar | tr '\n' ':')

echo "compiling the patched classes"
"$javac" --release 21 -nowarn -cp "$classpath" -d "$work/classes" $(sed "s|^|$root/|" "$here/patched-sources.txt")

# The core jar is rebuilt with the JDK's own `jar` tool and a fixed date, so the same inputs give the same bytes.
# Its signature files go: the jar is no longer the signed one, and nothing in jdtls checks them at start.
mkdir "$work/core"
(cd "$work/core" && unzip -q "$core")
cp -r "$work/classes/." "$work/core/"
rm -f "$work"/core/META-INF/*.SF "$work"/core/META-INF/*.RSA "$work"/core/META-INF/*.DSA
rm "$core"
"$jar_tool" --create --file "$core" --manifest "$work/core/META-INF/MANIFEST.MF" --date 2026-09-03T00:00:00Z -C "$work/core" .

# The file-facts extension (batch-facts/extension) is a bundle of its own. It goes into plugins/ and into the list of
# bundles every config_* directory starts, so the server has the command `java.batch.fileFacts` from its first start,
# with nothing for the client to pass.
bundle="jdtls.batchfacts_0.1.0.jar"
"$here/extension/build-bundle.sh" "$work/tree" "$work/tree/plugins/$bundle" >/dev/null
for ini in "$work"/tree/config_*/config.ini; do
  grep -q '^osgi.bundles=' "$ini" || { echo "no osgi.bundles in $ini" >&2; exit 1; }
  sed -i "s|^osgi.bundles=|osgi.bundles=reference\\\\:file\\\\:$bundle@4,|" "$ini"
done

cat > "$work/tree/PATCHES.txt" <<P
This is the Eclipse JDT Language Server ${BASE_VERSION} (https://projects.eclipse.org/projects/eclipse.jdt.ls),
with changes made in this fork. Not an official Eclipse build. The changes are the commits after the ${BASE_TAG} tag
of https://github.com/skghiasi/eclipse.jdt.ls (branch batch-facts/1.61); everything else here is the official release.

  org.eclipse.jdt.ls.core: CallHierarchyHandler -- a caller in a library class without source no longer fails the
  whole callHierarchy/incomingCalls request (eclipse-jdtls/eclipse.jdt.ls#3298).

  plugins/jdtls.batchfacts_0.1.0.jar -- an added bundle (source: batch-facts/extension) with the command
  java.batch.fileFacts: for one file, where every name is defined, which methods override which, and the calls
  the identifier-by-identifier requests cannot see. It is started with the other bundles (config_*/config.ini).
P

# Same layout as the official archive (members at the top level), and the same bytes for the same inputs.
(cd "$work/tree" && tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-09-03 00:00:00 UTC' \
  -cf - $(ls -A) | gzip -n -9 > "$out/$NAME")
sum=$(sha256sum "$out/$NAME" | cut -d' ' -f1)
echo "$sum  $NAME" > "$out/$NAME.sha256"
# The same archive again under a name that does not change from release to release, with its checksum beside it, so
# that a client can fetch "the newest build" from one fixed address (`releases/latest/download/<this name>`).
STABLE="jdt-language-server-batch.tar.gz"
cp "$out/$NAME" "$out/$STABLE"
echo "$sum  $STABLE" > "$out/$STABLE.sha256"
echo "wrote $out/$NAME ($(du -h "$out/$NAME" | cut -f1))"
echo "sha256 $sum"
