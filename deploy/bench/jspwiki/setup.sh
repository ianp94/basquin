#!/usr/bin/env bash
# Explode JSPWiki.war -> webapp/, inject a filesystem-store config, and seed content-rich pages.
# Reproducible: re-run to rebuild the exploded webapp + seed store from scratch.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$D/JSPWiki.war" ] || { echo "JSPWiki.war missing — curl it (see deploy/bench/README.md)"; exit 1; }

rm -rf "$D/webapp" "$D/pages" "$D/work"
mkdir -p "$D/webapp" "$D/pages" "$D/work"
( cd "$D/webapp" && jar xf "$D/JSPWiki.war" )

# Filesystem page store + minimal config on the classpath (JSPWiki reads jspwiki-custom.properties).
cat > "$D/webapp/WEB-INF/classes/jspwiki-custom.properties" <<'PROPS'
jspwiki.applicationName = BasquinBenchWiki
jspwiki.pageProvider = VersioningFileProvider
jspwiki.fileSystemPath = /var/jspwiki/pages
jspwiki.workDir = /var/jspwiki/work
jspwiki.baseURL = http://localhost:8080/
jspwiki.frontPage = Main
PROPS

# Seed pages: system stubs the default template references, plus N content pages with rich markup
# (headings, links, tables, code, lists) so the render path does real work.
seed() { printf '%s\n' "$2" > "$D/pages/$1.txt"; }
seed Main       "# BasquinBenchWiki\n\nWelcome. See [About], [Sandbox], and pages [One] through [Sixty].\n\n| Feature | State |\n|-------|-------|\n| Render | ok |\n| Search | ok |"
seed LeftMenu   "* [Main]\n* [About]\n* [SandBox]"
seed LeftMenuFooter "Basquin bench"
seed PageHeader "BasquinBenchWiki"
seed PageFooter "footer"
seed About      "!! About\n\nThis wiki exists to exercise the JSPWiki render + servlet stack under Basquin.\n\n{{{\ncode block\n}}}"
seed SandBox    "Edit me. [Main]"
for i in $(seq 1 60); do
  body="!! Page $i\n\nBody with a [Main] link, a list:\n* alpha\n* beta\n* gamma\n\nA table:\n| a | b |\n| $i | $((i*i)) |\n\nInline {{code}} and a paragraph of prose repeated to give the parser work. "
  body="$body$body$body"
  printf '%b\n' "$body" > "$D/pages/Page$(printf '%02d' "$i" 2>/dev/null || echo $i).txt"
done
# Friendly names One..Sixty aliases (a few, so Main's links resolve)
for n in One Two Three; do printf '%b\n' "See [Main]. Content for $n. * x * y" > "$D/pages/$n.txt"; done

echo "Seeded $(ls "$D/pages"/*.txt | wc -l) pages into $D/pages"
