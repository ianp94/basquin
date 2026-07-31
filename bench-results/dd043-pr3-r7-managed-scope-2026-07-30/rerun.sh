#!/usr/bin/env bash
# DD-043 PR-3 round-7 managed-scope measurement — regenerates every log in logs/.
#
# Writes ONLY into $WORK (default: a mktemp dir). Never writes into the repository tree and never uses
# ~/.m2/repository as the local repository. Uses `git show` (read-only) to read two files' content at
# one historical commit; it never runs `git checkout`/`stash`/`reset` and never touches the working tree.
#
# Requires: network (Maven Central), a Maven 3.9.15 distribution, a JDK 17, and com/basquin/*:0.3.0
# reachable from $BASQUIN_SRC_REPO (default: ~/.m2/repository, read-only).
#
# TWO injector jars:
#   stock   — BasquinInjector.java / InjectorVersion.java exactly as they read at 865ba35, the commit
#             PR #103's round-6 approver reviewed and the last commit WITHOUT the managed-scope branch.
#             Extracted via `git show 865ba35:<path>`, never via checkout. The four *-stock cells need
#             this jar because they demonstrate a SILENT bypass; the current checkout's injector aborts
#             two of them loudly, which is the fix doing its job and would measure nothing about the
#             hazard.
#   guarded — this checkout's HEAD (any commit at or after the round-7 fix). The four *-guarded cells
#             demonstrate the scope branch firing on mscore/mtcore and staying out of the way on
#             ctl/moptcore.
#
# Every cell manages com.basquin:basquin-core at the AGREEING version 0.3.0, so neither the managed-
# version branch nor any declared-path guard can be what fires or stays silent; only the managed
# attribute under test varies. Unlike the r4 directory there are no synthetic artifacts.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
STOCK_COMMIT="865ba35603badb55378a405410799f54b14f7a95"  # reviewed head; last commit without the scope branch
MVN="${MVN:-$HOME/.m2/wrapper/dists/apache-maven-3.9.15/9925cc1d/bin/mvn}"
BASQUIN_SRC_REPO="${BASQUIN_SRC_REPO:-$HOME/.m2/repository}"
WORK="${WORK:-$(mktemp -d)}"
OUT="$WORK/logs"
mkdir -p "$OUT" "$WORK/src-stock/com/basquin/maven" "$WORK/src-guarded/com/basquin/maven" \
         "$WORK/res/META-INF/sisu"
MHOME="$(dirname "$(dirname "$MVN")")"
echo "work dir: $WORK"

# --- the two injector jars ------------------------------------------------------------------------
for f in BasquinInjector.java InjectorVersion.java; do
  git -C "$REPO" show "$STOCK_COMMIT:basquin-maven-injector/src/main/java/com/basquin/maven/$f" \
    > "$WORK/src-stock/com/basquin/maven/$f"
done
cp "$REPO/basquin-maven-injector/src/main/java/com/basquin/maven/"*.java \
   "$WORK/src-guarded/com/basquin/maven/"
cp "$REPO/basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named" \
   "$WORK/res/META-INF/sisu/"
grep -E "^version = " "$REPO/basquin-maven-injector/build.gradle" | tr -d "\r" \
  | sed -E "s/^version = '(.*)'$/version=\1/" > "$WORK/res/basquin-injector.properties"
for v in stock guarded; do
  rm -rf "$WORK/classes-$v"; mkdir -p "$WORK/classes-$v"
  javac -nowarn -cp "$MHOME/lib/*" -d "$WORK/classes-$v" "$WORK/src-$v/com/basquin/maven/"*.java
  cp -r "$WORK/res/." "$WORK/classes-$v/"
  ( cd "$WORK/classes-$v" && jar cf "$WORK/injector-$v.jar" . )
done
diff -u "$WORK/src-stock/com/basquin/maven/BasquinInjector.java" \
        "$WORK/src-guarded/com/basquin/maven/BasquinInjector.java" \
        > "$WORK/injector-guard-added.diff" || true

# --- the file:// repository the injector is pointed at --------------------------------------------
BR="$WORK/basquinrepo"
for a in basquin-core basquin-quarkus basquin-quarkus-deployment; do
  mkdir -p "$BR/com/basquin/$a/0.3.0"
  cp "$BASQUIN_SRC_REPO/com/basquin/$a/0.3.0/$a-0.3.0.jar" \
     "$BASQUIN_SRC_REPO/com/basquin/$a/0.3.0/$a-0.3.0.pom" "$BR/com/basquin/$a/0.3.0/"
done

# --- the cells ------------------------------------------------------------------------------------
# For each stock cell: dependency:list (what resolved, at what scope) AND
# dependency:build-classpath -DincludeScope=runtime (whether basquin-core actually reaches the
# runtime classpath — the list line alone names a scope; the classpath is the consequence).
# The committed ctl-stock-coldcache-list.log is the same ctl cell run first, against the then-empty
# local repository; ctl-stock-list.log is its warm rerun so its line numbers align with the others'.
run_stock() { # $1=cell
  local d="$WORK/work/$1-stock"; rm -rf "$d"; mkdir -p "$d"; cp "$HERE/cells/$1.pom.xml" "$d/pom.xml"
  ( cd "$d" && "$MVN" -B -Dmaven.repo.local="$WORK/localrepo" \
      -Dmaven.ext.class.path="$WORK/injector-stock.jar" -Dbasquin.inject.repo.url="file://$BR/" \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list ) \
      > "$OUT/$1-stock-list.log" 2>&1 || true
  ( cd "$d" && "$MVN" -B -Dmaven.repo.local="$WORK/localrepo" \
      -Dmaven.ext.class.path="$WORK/injector-stock.jar" -Dbasquin.inject.repo.url="file://$BR/" \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
      -DincludeScope=runtime -Dmdep.outputFile="$d/runtime-cp.txt" ) \
      > "$OUT/$1-stock-runtimecp.log" 2>&1 || true
  cp "$d/runtime-cp.txt" "$OUT/$1-stock-runtime-cp.txt"
  tr ':' '\n' < "$OUT/$1-stock-runtime-cp.txt" > "$OUT/$1-stock-runtime-cp-entries.txt"
  printf '%-10s (stock)   -> %s\n' "$1" "$1-stock-list.log"
}
run_guarded() { # $1=cell
  local d="$WORK/work/$1-guarded"; rm -rf "$d"; mkdir -p "$d"; cp "$HERE/cells/$1.pom.xml" "$d/pom.xml"
  ( cd "$d" && "$MVN" -B -Dmaven.repo.local="$WORK/localrepo" \
      -Dmaven.ext.class.path="$WORK/injector-guarded.jar" -Dbasquin.inject.repo.url="file://$BR/" \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list ) \
      > "$OUT/$1-guarded-list.log" 2>&1 || true
  printf '%-10s (guarded) -> %s\n' "$1" "$1-guarded-list.log"
}
run_stock ctl
mv "$OUT/ctl-stock-list.log" "$OUT/ctl-stock-coldcache-list.log"
run_stock ctl      # warm rerun — this is the cited control
run_stock mscore
run_stock mtcore
run_stock moptcore
run_guarded ctl
run_guarded mscore    # expected: abort at 'Scanning for projects', nonzero exit — the fix firing
run_guarded mtcore    # expected: same
run_guarded moptcore  # expected: BUILD SUCCESS — optional is measured harmless and must stay accepted

echo
echo "logs in $OUT — copy them over logs/ and run 'python3 regen-derived.py' from this directory to"
echo "rebuild cell-results.txt / citations.txt (it exits nonzero if any citation stops resolving)"
