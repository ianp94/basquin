#!/usr/bin/env bash
# DD-043 PR-3 round-4 guard measurement — regenerates every log in logs/.
#
# Writes ONLY into $WORK (default: a mktemp dir). Never writes into the repository tree and never uses
# ~/.m2/repository as the local repository.
#
# Requires: network (Maven Central), a Maven 3.9.15 distribution, a JDK 17, and com/basquin/*:0.3.0
# reachable from $BASQUIN_SRC_REPO (default: ~/.m2/repository, read-only).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/basquin-maven-injector/src/main/java/com/basquin/maven"
MVN="${MVN:-$HOME/.m2/wrapper/dists/apache-maven-3.9.15/9925cc1d/bin/mvn}"
BASQUIN_SRC_REPO="${BASQUIN_SRC_REPO:-$HOME/.m2/repository}"
WORK="${WORK:-$(mktemp -d)}"
OUT="$WORK/logs"; mkdir -p "$OUT" "$WORK/src-stock/com/basquin/maven" "$WORK/src-noguard/com/basquin/maven" "$WORK/res/META-INF/sisu"
MHOME="$(dirname "$(dirname "$MVN")")"
echo "work dir: $WORK"

# --- the two injector jars, both compiled from the module source in this checkout ------------------
cp "$SRC"/*.java "$WORK/src-stock/com/basquin/maven/"
cp "$SRC"/*.java "$WORK/src-noguard/com/basquin/maven/"
python3 - "$WORK/src-noguard/com/basquin/maven/BasquinInjector.java" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "        DependencyManagement dm = p.getModel().getDependencyManagement();"
new = ("        if (true) { return; } // MEASUREMENT NEUTERING (scratchpad copy only) — guard disabled so Maven's\n"
       "                              // real behaviour under a managed com.basquin entry can be observed.\n" + old)
assert s.count(old) == 1, "neutering anchor moved; update rerun.sh"
open(p, 'w').write(s.replace(old, new))
PY
cp "$REPO/basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named" "$WORK/res/META-INF/sisu/"
grep -E "^version = " "$REPO/basquin-maven-injector/build.gradle" | tr -d "\r" \
  | sed -E "s/^version = '(.*)'$/version=\1/" > "$WORK/res/basquin-injector.properties"
cat "$WORK/res/basquin-injector.properties"
for v in stock noguard; do
  rm -rf "$WORK/classes-$v"; mkdir -p "$WORK/classes-$v"
  javac -nowarn -cp "$MHOME/lib/*" -d "$WORK/classes-$v" "$WORK/src-$v/com/basquin/maven/"*.java
  cp -r "$WORK/res/." "$WORK/classes-$v/"
  ( cd "$WORK/classes-$v" && jar cf "$WORK/injector-$v.jar" . )
done
diff -u "$WORK/src-stock/com/basquin/maven/BasquinInjector.java" \
        "$WORK/src-noguard/com/basquin/maven/BasquinInjector.java" > "$WORK/injector-noguard.diff" || true

# --- the file:// repository the injector is pointed at ---------------------------------------------
BR="$WORK/basquinrepo"
for a in basquin-core basquin-quarkus basquin-quarkus-deployment; do
  mkdir -p "$BR/com/basquin/$a/0.3.0"
  cp "$BASQUIN_SRC_REPO/com/basquin/$a/0.3.0/$a-0.3.0.jar" \
     "$BASQUIN_SRC_REPO/com/basquin/$a/0.3.0/$a-0.3.0.pom" "$BR/com/basquin/$a/0.3.0/"
done
# synthetic: a resolvable conflicting version, so the hazard cell can SUCCEED rather than fail loudly
mkdir -p "$BR/com/basquin/basquin-core/0.0.1-conflicting"
cp "$BR/com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.jar" \
   "$BR/com/basquin/basquin-core/0.0.1-conflicting/basquin-core-0.0.1-conflicting.jar"
sed 's|<version>0.3.0</version>|<version>0.0.1-conflicting</version>|' \
   "$BR/com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.pom" \
   > "$BR/com/basquin/basquin-core/0.0.1-conflicting/basquin-core-0.0.1-conflicting.pom"
# synthetic: a classified artifact, so the classifier cell can resolve
cp "$BR/com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.jar" \
   "$BR/com/basquin/basquin-core/0.3.0/basquin-core-0.3.0-tests.jar"

# --- the cells -------------------------------------------------------------------------------------
run() { # $1=cell  $2=stock|noguard  $3=log basename
  local d="$WORK/cells/$1"; mkdir -p "$d"; cp "$HERE/cells/$1.pom.xml" "$d/pom.xml"
  rm -f "$WORK/localrepo/com/basquin/basquin-core/0.0.1-conflicting/"*.lastUpdated
  ( cd "$d" && "$MVN" -B \
      -Dmaven.repo.local="$WORK/localrepo" \
      -Dmaven.ext.class.path="$WORK/injector-$2.jar" \
      -Dbasquin.inject.repo.url="file://$BR/" \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list ) > "$OUT/$3-list.log" 2>&1 || true
  printf '%-24s -> %s\n' "$1 ($2)" "$3-list.log"
}
run ctl   noguard ctl-noguard          # baseline; the committed ctl-noguard-coldcache log is the same
run mx    noguard mx-noguard           #   cell on a cold local repository
run msc   noguard msc-noguard
run mty   noguard mty-noguard
run mcl   noguard mcl-noguard
run mopt  noguard mopt-noguard
run mcv   noguard mcv-noguard
run mcv   stock   mcv-stock
run ctl   stock   ctl-stock
run dcv   stock   dcv-stock
run dag   stock   dag-stock
run dsc   stock   dsc-stock
run dprov stock   dprov-stock
run dty   stock   dty-stock
run dcl   stock   dcl-stock
run dex   stock   dex-stock

echo
echo "logs in $OUT — regenerate cell-results.txt / citations.txt from them with the awk/python in README.md"
