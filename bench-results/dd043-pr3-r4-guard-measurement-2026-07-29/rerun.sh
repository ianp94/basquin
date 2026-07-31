#!/usr/bin/env bash
# DD-043 PR-3 round-4 guard measurement — regenerates every log in logs/.
#
# Writes ONLY into $WORK (default: a mktemp dir). Never writes into the repository tree and never uses
# ~/.m2/repository as the local repository. Uses `git show` (read-only) to read one file's content at
# one historical commit; it never runs `git checkout`/`stash`/`reset` and never touches the working tree.
#
# Requires: network (Maven Central), a Maven 3.9.15 distribution, a JDK 17, and com/basquin/*:0.3.0
# reachable from $BASQUIN_SRC_REPO (default: ~/.m2/repository, read-only).
#
# THREE injector jars, not two (round-5 approver finding S6):
#   stock          — this checkout's HEAD. As of commit 8cadf8a this includes
#                    failOnUnusableSiblingDeclaration, the guard S2 (below) motivated.
#   noguard        — stock + injector-noguard.diff (one guard branch neutered), for the S1 cells that
#                    need Maven's real behaviour under a managed com.basquin entry, unintercepted.
#   stock-preguard — BasquinInjector.java / InjectorVersion.java as they read at bffcbba, the commit
#                    this directory's cells were actually measured against (see provenance.txt) — the
#                    commit immediately BEFORE failOnUnusableSiblingDeclaration landed. Extracted via
#                    `git show bffcbba:<path>`, never via checkout, so the working tree is untouched.
#
# The `dcv`, `dsc`, `dprov` cells are built with stock-preguard so they reproduce the BUILD SUCCESS
# recorded in logs/{dcv,dsc,dprov}-stock-list.log. Built with today's `stock` instead, all three now
# hard-fail at "Scanning for projects" — that is failOnUnusableSiblingDeclaration doing exactly what it
# was added to do, not a regression. Three extra "-guard-verify" cells at the bottom demonstrate that,
# writing to logs that were never part of the original 17 and are not compared against them.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/basquin-maven-injector/src/main/java/com/basquin/maven"
PREGUARD_COMMIT="bffcbbad50e4df1398efbd88d5ea40016ff374e9"  # last commit before failOnUnusableSiblingDeclaration (8cadf8a)
MVN="${MVN:-$HOME/.m2/wrapper/dists/apache-maven-3.9.15/9925cc1d/bin/mvn}"
BASQUIN_SRC_REPO="${BASQUIN_SRC_REPO:-$HOME/.m2/repository}"
WORK="${WORK:-$(mktemp -d)}"
OUT="$WORK/logs"
mkdir -p "$OUT" "$WORK/src-stock/com/basquin/maven" "$WORK/src-noguard/com/basquin/maven" \
         "$WORK/src-stock-preguard/com/basquin/maven" "$WORK/res/META-INF/sisu"
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

# --- the third jar: source as it read at $PREGUARD_COMMIT, read via `git show` only -----------------
# Read-only: this never runs `git checkout`/`stash`/`reset` and never touches the working tree, which
# other agents are editing concurrently. It only asks git to print one blob's content per file.
for f in BasquinInjector.java InjectorVersion.java; do
  git -C "$REPO" show "$PREGUARD_COMMIT:basquin-maven-injector/src/main/java/com/basquin/maven/$f" \
    > "$WORK/src-stock-preguard/com/basquin/maven/$f"
done
rm -rf "$WORK/classes-stock-preguard"; mkdir -p "$WORK/classes-stock-preguard"
javac -nowarn -cp "$MHOME/lib/*" -d "$WORK/classes-stock-preguard" \
  "$WORK/src-stock-preguard/com/basquin/maven/"*.java
cp -r "$WORK/res/." "$WORK/classes-stock-preguard/"
( cd "$WORK/classes-stock-preguard" && jar cf "$WORK/injector-stock-preguard.jar" . )
diff -u "$WORK/src-stock-preguard/com/basquin/maven/BasquinInjector.java" \
        "$WORK/src-stock/com/basquin/maven/BasquinInjector.java" \
        > "$WORK/injector-guard-added.diff" || true
echo "stock-preguard built from $PREGUARD_COMMIT — diff to current stock: $WORK/injector-guard-added.diff"

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
# dcv/dsc/dprov: built with stock-preguard (bffcbba), so these reproduce the committed BUILD SUCCESS —
# see logs/{dcv,dsc,dprov}-stock-list.log. Running these three with today's `stock` instead hard-fails
# under failOnUnusableSiblingDeclaration; that is done separately below and is NOT a reproduction of
# these rows.
run dcv   stock-preguard dcv-stock
run dag   stock   dag-stock
run dsc   stock-preguard dsc-stock
run dprov stock-preguard dprov-stock
run dty   stock   dty-stock
run dcl   stock   dcl-stock
run dex   stock   dex-stock

echo
echo "--- guard verification (round-5 approver S6): same three poms, today's shipped injector --------"
echo "Expected: all three ABORT at 'Scanning for projects' with failOnUnusableSiblingDeclaration's"
echo "message and a nonzero mvn exit code. That is the guard added in 8cadf8a doing its job — it is"
echo "not a reproduction of dcv-stock-list.log/dsc-stock-list.log/dprov-stock-list.log above and is not"
echo "expected to match them. A BUILD SUCCESS here, or an abort with a DIFFERENT message, would be the"
echo "actual regression to chase."
run dcv   stock   dcv-guard-verify
run dsc   stock   dsc-guard-verify
run dprov stock   dprov-guard-verify

echo
echo "logs in $OUT — regenerate cell-results.txt / citations.txt from them with the awk/python in README.md"
echo "(the three *-guard-verify-list.log files are NOT part of the original 17 and are not inputs to"
echo " cell-results.txt / citations.txt; they exist only to demonstrate the guard fires today.)"
