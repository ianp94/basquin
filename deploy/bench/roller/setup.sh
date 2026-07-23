#!/usr/bin/env bash
# Prepare the Apache Roller 6.1.5 bench target:
#   1. fetch + verify the binary dist, explode roller.war -> webapp/
#   2. write WEB-INF/classes/roller-custom.properties (jdbc -> the `roller-db` Service)
#   3. emit seed/ — the schema Roller ships plus a fully-installed seed dataset, so the app comes up
#      PAST the first-run install wizard with a user, a weblog and 40 published entries.
# Idempotent: re-run to rebuild webapp/ + seed/ from scratch. Reproducible: everything below is
# derived from the pinned tarball; nothing is captured from a hand-clicked install.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROLLER_URL=https://archive.apache.org/dist/roller/roller-6.1/v6.1.5/apache-roller-6.1.5-binary.tar.gz
ROLLER_SHA=c0f16e0792b9475dbde8e24fa4116ca10d6e0a8a82f71f06e9d3871637207ea8
PG_URL=https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar
# Container-provided, NOT app code (Roller's install guide says to put these in the server's lib):
# the Roller binary dist ships no JavaMail, and RollerContext's listener constructs a MailProvider
# unconditionally — on a JDK 17 Tomcat with no javax.mail on the classpath the ROOT context dies with
# "One or more listeners failed to start" / ClassNotFoundException: javax.mail.Session. javax.mail
# 1.6.2 in turn needs javax.activation, which left the JDK in 11. See README "Gotchas".
MAIL_URL=https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar
ACTIVATION_URL=https://repo1.maven.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar

# ---- 1. artifact -------------------------------------------------------------------------------
[ -f "$D/roller-bin.tar.gz" ] || curl -sSLo "$D/roller-bin.tar.gz" "$ROLLER_URL"
echo "$ROLLER_SHA  $D/roller-bin.tar.gz" | sha256sum -c -
[ -f "$D/postgresql.jar" ] || curl -sSLo "$D/postgresql.jar" "$PG_URL"
mkdir -p "$D/tomcat-lib"
[ -f "$D/tomcat-lib/javax.mail.jar" ] || curl -sSLo "$D/tomcat-lib/javax.mail.jar" "$MAIL_URL"
[ -f "$D/tomcat-lib/javax.activation.jar" ] || curl -sSLo "$D/tomcat-lib/javax.activation.jar" "$ACTIVATION_URL"

rm -rf "$D/apache-roller-6.1.5" "$D/webapp" "$D/seed"   # NB: tomcat-lib/ is cached, not rebuilt
tar xzf "$D/roller-bin.tar.gz" -C "$D"
mkdir -p "$D/webapp" "$D/seed"
( cd "$D/webapp" && jar xf "$D/apache-roller-6.1.5/webapp/roller.war" )

# ---- 2. config ---------------------------------------------------------------------------------
# Roller reads roller-custom.properties from the classpath and overlays it on its packaged
# roller.properties. installation.type=auto lets Roller create/upgrade its own schema if it ever
# finds an empty database — belt-and-braces behind the seeded DB image, so a `kubectl delete pvc`
# style accident degrades to "empty blog", not "500 on every route".
cat > "$D/webapp/WEB-INF/classes/roller-custom.properties" <<'PROPS'
installation.type=auto

# jdbc, not jndi: no Resource in context.xml to maintain, and the operator re-rolls the pod with
# injected CATALINA_OPTS — one less container-managed object to keep in sync.
database.configurationType=jdbc
database.jdbc.driverClass=org.postgresql.Driver
database.jdbc.connectionURL=jdbc:postgresql://roller-db:5432/roller
database.jdbc.username=roller
database.jdbc.password=roller

# Roller's MailProvider is constructed during bootstrap. With the default (jndi + mail/Session, no
# such resource) it logs a warning and disables mail; 'properties' + a hostname keeps it quiet and
# keeps comment-notification code on a fast local failure path instead of a DNS timeout.
mail.configurationType=properties
mail.hostname=localhost

# Filesystem stores — pinned to absolute paths created (world-writable) in Dockerfile.raw. The
# defaults are under ${user.home}, which is /root in the tomcat image: unwritable once the operator
# re-rolls the pod under a non-root securityContext.
mediafiles.storage.dir=/var/roller/data/mediafiles
search.index.dir=/var/roller/data/search-index
cache.dir=/var/roller/data/planet-cache

# The bench cares about render/query cost, not about Roller's page cache serving the same bytes for
# an hour. Leave the caches ON (that is the honest production shape) but shrink the weblog-page TTL
# so a fuzz campaign of a few minutes actually re-renders instead of measuring a HashMap.
cache.weblogpage.timeout=30
cache.sitewide.timeout=30

# Roller's default comment authenticator is MathCommentAuthenticator: it stores "what is 3 + 5?" in
# the session and requires the answer in an `answer` field. Two problems for a bench target:
#  (a) the bundled themes never render the question, so the ONLY unauthenticated write path in the
#      app is unreachable by anything — browser included — with the shipped theme;
#  (b) even if it were rendered, no correlation feature can solve it: capture-and-replay can copy a
#      token, it cannot ADD TWO NUMBERS (see README "What Basquin can't express").
# DefaultCommentAuthenticator ships with Roller and always approves. This is deployment config, in
# the same spirit as JSPWiki's jspwiki-custom.properties — the WAR itself is untouched.
comment.authenticator.classname=org.apache.roller.weblogger.ui.rendering.plugins.comments.DefaultCommentAuthenticator
PROPS

# ---- 3. seed ------------------------------------------------------------------------------------
# 01-schema.sql is Roller's own DDL, verbatim from the dist — not a hand-copied snapshot.
cp "$D/webapp/WEB-INF/classes/dbscripts/postgresql/createdb.sql" "$D/seed/01-schema.sql"

# 02-seed.sql is what the first-run wizard would have produced: the runtime properties row-set, an
# admin user, its global + weblog permissions, one weblog with categories, and N published entries
# with comments and tags. Written as plain INSERTs so `kubectl apply` comes up installed with no
# manual step (see README "Why a seeded DB image").
#
# UUIDs are FIXED, not generated: a deterministic seed means entry anchors/permalinks are stable, so
# the grammar's corpus values keep pointing at real rows across a rebuild.
W=b1a5d1c0000000000000000000000001            # weblog id
U=b1a5d1c0000000000000000000000002            # user id

# THE row that decides whether Roller serves the blog or the install wizard. DatabaseInstaller
# reads roller_properties['roller.database.version'] and, finding tables but no version, concludes
# the schema is pre-3.1 and renders "Database tables need to be upgraded" on EVERY route — a
# seeded-but-versionless database looks exactly like an ancient one. The value is Roller's own
# parseVersionString(): strip non-digits from ro.version, keep the first three ("6.1.5" -> 615).
DBVER=$(sed -n 's/^ro\.version=//p' "$D/webapp/WEB-INF/classes/roller-version.properties" | tr -cd '0-9' | cut -c1-3)
{
cat <<SQL
-- Roller runtime properties. Roller's PropertiesManager fills in anything missing from
-- runtime-config-defs.xml on bootstrap, so only the rows that must NOT be defaults are listed.
insert into roller_properties (name, value) values
  ('roller.database.version', '$DBVER'),        -- else every route renders the upgrade wizard
SQL
cat <<'SQL'
  ('site.frontpage.weblog.handle', 'basquin'),  -- makes / render the weblog instead of a stub page
  ('users.registration.enabled', 'disabled'),   -- keep the fuzzer out of an unbounded user table
  ('site.name', 'Basquin Bench Roller'),
  ('site.shortName', 'BasquinBench'),
  ('site.description', 'Apache Roller as a Basquin benchmark target'),
  ('site.adminemail', 'admin@example.com'),
  ('site.absoluteurl', ''),                     -- empty => Roller derives it from the request
  ('users.comments.enabled', 'true'),
  ('users.comments.autoformat', 'true'),
  ('site.linkbacks.enabled', 'false'),
  ('uploads.enabled', 'false');                 -- no writable media store needed in the bench pod
SQL

# The user. passphrase is a BCrypt hash of the password 'basquin', stored in Spring Security's
# DelegatingPasswordEncoder form — the '{bcrypt}' PREFIX IS LOAD-BEARING. RollerContext builds a
# DelegatingPasswordEncoder whose default-for-unprefixed-matches is passwds.encryption.lazyUpgradeFrom
# (=SHA), so a bare '$2a$…' hash is read as a legacy SHA-1 digest, never matches, and login just
# bounces to /roller-ui/login.rol?error=true with nothing in any log.
# Regenerate with Roller's own bundled encoder — no third-party hash:
#   docker run --rm -v "$PWD/webapp/WEB-INF/lib:/l:ro" tomcat:9.0-jdk17-temurin jshell -q \
#     --class-path /l/spring-security-crypto-5.8.14.jar -
#   System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("basquin"));
# (bcrypt is salted, so a fresh run prints a different — equally valid — hash.)
cat <<SQL

insert into roller_user (id, username, passphrase, screenname, fullname, emailaddress, datecreated, locale, timezone, isenabled)
values ('$U', 'basquin', '{bcrypt}\$2a\$10\$br94h8Js1M4.2RgD/p6ytuVede9zqbGW3gva8VI4wNpIactevTFo6',
        'Basquin', 'Basquin Bench', 'basquin@example.com', now(), 'en_US', 'UTC', true);

-- Global roles: 'admin' unlocks /roller-ui/admin/* (a whole extra Struts surface for the fuzzer).
insert into userrole (id, rolename, username) values
  ('${U}a1', 'admin',  'basquin'),
  ('${U}a2', 'editor', 'basquin');

-- Weblog. editortheme must name a theme that ships in WEB-INF/classes/themes or every render 500s.
insert into weblog (id, name, handle, tagline, creator, enablebloggerapi, editorpage, allowcomments,
                    emailcomments, emailaddress, editortheme, locale, timezone, defaultplugins,
                    visible, isactive, datecreated, defaultallowcomments, defaultcommentdays,
                    commentmod, displaycnt, lastmodified, enablemultilang, showalllangs, about)
values ('$W', 'Basquin Bench Weblog', 'basquin', 'A weblog that exists to be fuzzed', 'basquin',
        false, 'editor-text.jsp', true, false, 'basquin@example.com', 'basic', 'en_US', 'UTC', '',
        true, true, now(), true, 3650, false, 15, now(), false, true, 'Bench target');

-- Weblog permission: without this row the weblog exists but its owner cannot open the authoring UI,
-- and every /roller-ui/authoring/* route 403s — half the interesting surface, gone.
insert into roller_permission (id, username, actions, objectid, objecttype, pending, datecreated)
values ('${U}p1', 'basquin', 'admin,post,edit_draft', '$W', 'Weblog', false, now());
SQL

# Categories — a weblog with one category exercises exactly one category route; give the
# /category/<name> render path something to branch on.
i=0
for c in Tech Notes Benchmarks Availability Misc; do
  i=$((i+1))
  printf "insert into weblogcategory (id, name, description, websiteid, position) values ('%s%02d','%s','%s posts','%s',%d);\n" \
    "${W:0:46}" "$i" "$c" "$c" "$W" "$i"
done

# Entries. 40 published posts, dated one day apart, so the date-archive, pager, category and feed
# routes all have multiple pages of real rows to sort/window instead of a single-row fast path.
# The body is long enough that the Velocity render + the Lucene index entry are not trivial.
cat_ids=(); for i in 1 2 3 4 5; do cat_ids+=("$(printf '%s%02d' "${W:0:46}" "$i")"); done
for n in $(seq 1 40); do
  cid=${cat_ids[$(( (n-1) % 5 ))]}
  eid=$(printf 'e1a5d1c0%032d' "$n"); eid=${eid:0:48}
  anchor=$(printf 'bench-entry-%02d' "$n")
  body="Entry $n of the Basquin bench weblog. "
  body="$body This paragraph exists so the Velocity render, the HTML plugin chain and the Lucene analyzer all have real text to chew through rather than a one-liner."
  body="$body <ul><li>alpha</li><li>beta</li><li>gamma</li></ul> <p>A second paragraph with a <a href=\"/basquin/\">self link</a> and some <code>inline code</code>.</p>"
  body="$body $body"
  printf "insert into weblogentry (id, anchor, creator, title, text, pubtime, updatetime, websiteid, categoryid, publishentry, allowcomments, commentdays, righttoleft, pinnedtomain, locale, status, summary, content_type) values ('%s','%s','basquin','Bench entry %02d','%s', now() - interval '%d days', now() - interval '%d days', '%s','%s', true, true, 3650, false, %s, 'en_US','PUBLISHED','Summary of bench entry %02d','text/html');\n" \
    "$eid" "$anchor" "$n" "${body//\'/\'\'}" "$n" "$n" "$W" "$cid" "$([ $n -le 2 ] && echo true || echo false)" "$n"
  # Tags — the /tags/<tag> route and the tag-aggregate query only do work if the agg table is real.
  tag=$( [ $((n % 3)) -eq 0 ] && echo bench || { [ $((n % 3)) -eq 1 ] && echo latency || echo jvm; } )
  printf "insert into roller_weblogentrytag (id, websiteid, entryid, creator, name, time) values ('t%s','%s','%s','basquin','%s', now());\n" \
    "${eid:1:47}" "$W" "$eid" "$tag"
  # Two comments per entry: the entry permalink template renders the comment list, and the comment
  # POST path (the one write the fuzzer can reach unauthenticated) appends to it.
  for c in 1 2; do
    printf "insert into roller_comment (id, entryid, name, email, url, content, posttime, notify, remotehost, status, contenttype) values ('c%d%s','%s','Reader %d','reader%d@example.com','', 'Comment %d on entry %d. Prose so the comment list render is not free.', now() - interval '%d hours', false, '10.0.0.1', 'APPROVED', 'text/plain');\n" \
      "$c" "${eid:2:46}" "$eid" "$c" "$c" "$c" "$n" "$n"
  done
done

# Tag aggregate counts — Roller reads roller_weblogentrytagagg for the tag cloud; a missing row set
# makes the tag cloud empty (and the /tags/ route boring) even though the tags exist.
cat <<SQL

insert into roller_weblogentrytagagg (id, websiteid, name, total, lastused)
  select 'agg-' || name, websiteid, name, count(*), now() from roller_weblogentrytag group by websiteid, name;
insert into roller_weblogentrytagagg (id, websiteid, name, total, lastused)
  select 'aggsite-' || name, null, name, count(*), now() from roller_weblogentrytag group by name;
SQL
} > "$D/seed/02-seed.sql"

echo "webapp/ exploded ($(find "$D/webapp/WEB-INF/classes/org" -name '*.class' | wc -l) app classes)"
echo "seed/  : $(wc -l < "$D/seed/01-schema.sql") lines schema, $(wc -l < "$D/seed/02-seed.sql") lines data"
