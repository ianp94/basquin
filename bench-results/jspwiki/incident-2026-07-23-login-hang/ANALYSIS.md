# Apache JSPWiki 2.12.4 — unsynchronized `WeakHashMap` in `DefaultUserManager` causes a permanent 100%-CPU spin

**Affected version:** 2.12.4; also present unfixed on `apache/jspwiki` `master` as of 2026-07-23.
**Component:** `org.apache.wiki.auth.DefaultUserManager`
**Status:** Root-caused from bytecode and **deliberately reproduced**.
**Severity (our assessment):** High — permanent, unrecoverable loss of a request thread at 100% of a
core, per occurrence, with no self-recovery short of a JVM restart.

## 1. Summary

`DefaultUserManager` caches user profiles in a plain, unsynchronized `java.util.WeakHashMap`. Every
request that renders the login screen both reads and writes that map with no synchronization. Under
concurrent access the map's bucket chains can be spliced into a cycle, after which
`WeakHashMap.get()` walks the chain forever: the thread never returns, never throws, never yields,
and burns 100% of a core permanently.

We observed two Tomcat workers in this state, each having accumulated ~17,900 seconds of CPU inside a
single `get()` call, and then reproduced it on demand in about 60 seconds.

## 2. Symptom

Unmodified JSPWiki 2.12.4 on Tomcat 9 / OpenJDK 17.0.19, under automated HTTP traffic:

- Two `http-nio-8080-exec-*` threads permanently `RUNNABLE`, each pinned at ~100% of a core.
- JSPWiki's own `WatchDog` logging for hours:
  ```
  WatchDog - Watchable 'http-nio-8080-exec-2' exceeded timeout in state
  'Filtering for URL /Login.jsp' by 18121 seconds
  ```
- Eventually the application stopped answering HTTP entirely.
- No crash and no non-zero exit; the JVM stayed alive throughout, still burning 213% CPU five hours
  later.

## 3. Mechanism — established from the shipped bytecode

Disassembled with `javap` from the jar copied out of the running container
(`DefaultUserManager.javap.txt`).

### 3.1 The field is a plain `WeakHashMap`

Constructor, source line 94:
```
 5: new           #7    // class java/util/WeakHashMap
 9: invokespecial #9    // Method java/util/WeakHashMap."<init>":()V
12: putfield      #10   // Field m_profiles:Ljava/util/Map;
```
Constructed directly — **not** wrapped in `Collections.synchronizedMap`.

### 3.2 Both accesses live in one unsynchronized method

The class contains exactly two references to `m_profiles`, both inside `getUserProfile(Session)`:

| Source line | Bytecode | Operation |
|---|---|---|
| 148 | `5: invokeinterface java/util/Map.get` | `m_profiles.get(session)` |
| 172 | `125: invokeinterface java/util/Map.put` | `m_profiles.put(session, profile)` |

`getUserProfile` is not `synchronized` and its bytecode contains **zero**
`monitorenter`/`monitorexit`. The class does have two `synchronized` methods
(`addWikiEventListener`, `removeWikiEventListener`), so the omission is inconsistent with the
author's own practice in the same file.

Line 148 is precisely the frame at the top of every wedged stack.

### 3.3 Every anonymous request is a write

Control flow: `get` returns `null` for an uncached session → `newProfile = true` →
`getUserDatabase().newProfile()` → unconditionally reaches the `put` at line 172. **Every request
from a client without an established profile performs a write.** Anonymous login-page hits are
therefore a stream of concurrent `put`s into an unsynchronized map.

### 3.4 `WeakHashMap.java:409` is the chain-walk step itself

Disassembling `java.util.WeakHashMap` from the container's exact JDK (OpenJDK 17.0.19), `get()`'s
line table maps line 409 to:
```
63: aload  6
65: getfield java/util/WeakHashMap$Entry.next   // e = e.next
68: astore 6
70: goto   33                                    // loop back-edge
```

That is `e = e.next;` plus the back-edge — the innermost step of walking a collision chain. Both
wedged threads are parked on exactly this instruction.

This is the decisive point: **a terminating chain walk cannot spend 17,912 seconds of CPU at
`e = e.next`.** A huge map, a slow lookup, or a pathological hash distribution would all still
terminate. Only a chain that loops back on itself produces an unbounded walk. The stack proves the
structure is cyclic, not merely slow.

### 3.5 Why a cycle forms — and why reads alone suffice

The usual explanation is concurrent `put` during resize, which applies here. But `WeakHashMap` is
worse, and it matters for how easily this triggers:

```java
private Entry<K,V>[] getTable() {
    expungeStaleEntries();          // <-- mutates the map
    return table;
}
public V get(Object key) {
    ...
    Entry<K,V>[] tab = getTable();  // every read goes through the above
```

Verified from the same disassembly: `get()` → `getTable()` → `expungeStaleEntries()`, which walks
bucket chains and unlinks dead entries with classic pointer surgery — `table[i] = next` (`aastore`)
or `prev.next = next` (`putfield Entry.next`).

So **`WeakHashMap.get()` is itself a mutating operation.** Two threads that only ever *read* can
corrupt each other's chain. Concurrent unlinks on one bucket can drop the terminating `null` or
splice a node back into a chain it already left.

JSPWiki's usage amplifies this badly: the keys are `Session` objects, and anonymous login hits create
a fresh session per request that becomes garbage almost immediately. The reference queue is therefore
never empty, so `expungeStaleEntries()` is doing real unlinking work on essentially *every* call,
from every request thread, concurrently.

### 3.6 Why it is permanent

The spin is a plain `while (e != null)` loop with no allocation, no I/O, no blocking call and no
interruption check. Nothing in JSPWiki, Tomcat or the JDK will break the thread out. It is lost for
the life of the JVM. `WatchDog` detects and logs it but has no mechanism to kill or recover it — it
only reports.

## 4. What traffic triggers it

Route into the fault:
```
Login_jsp → LoginForm_jsp → ViewTemplate_jsp → LoginContent_jsp
  → UserProfileTag.doWikiStartTag (UserProfileTag.java:106)
    → DefaultUserManager.getUserProfile (line 148)
```
**Rendering the login page** calls the unsafe map. Any concurrent traffic at `/Login.jsp` exercises
it.

In our campaigns the volume arrived indirectly. Three fuzz-corpus GET routes carried an **empty**
`page=`. Verified directly against a freshly restarted instance:

| Request | Result |
|---|---|
| `/Wiki.jsp?page=` | `302` → `/Login.jsp?redirect=` |
| `/Diff.jsp?page=` | `302` → `/Login.jsp?redirect=` |
| `/PageInfo.jsp?page=` | `302` → `/Login.jsp?redirect=` |
| `/Wiki.jsp?page=Page59` | `200` |

So three ordinary-looking corpus entries turn into a concentrated stream of concurrent login renders
— each an anonymous session, each a `put` into the unsynchronized map.

Nothing about this traffic is malformed or hostile. An empty query parameter is not an attack, and
any deployment with several users on the login screen at once does the same thing more slowly.

## 5. Impact

### 5.1 Proven

Each occurrence permanently destroys one request thread and permanently consumes 100% of one core.
This is monotonic — threads are never recovered and CPU cost accumulates. On our 8-core node two
occurrences took 25% of total CPU capacity indefinitely.

Two dumps ~350s apart show CPU climbing (`17,912,810 ms` → `18,262,037 ms` for `exec-2`) with a
byte-identical stack: genuinely spinning, not blocked, not an unlucky sample.

### 5.2 What actually took the application down

Our first reading was "the thread pool bleeds capacity until the app stops serving." **That is
wrong.** Only 2 threads were ever stuck against a Tomcat default `maxThreads` of 200, and the
application kept serving normally for ~4.6 hours after the first thread wedged. Pool exhaustion was
never approached.

The proximate cause of unavailability was heap exhaustion, and specifically **where the resulting
error landed**:

```
Exception in thread "http-nio-8080-Poller" java.lang.OutOfMemoryError: Java heap space
```

The NIO **Poller** thread died. Tomcat does not restart it. Without a Poller, accepted connections
are never registered for read-readiness and never dispatched to a worker, so *every* subsequent
request hangs forever regardless of idle worker count.

Confirmed live rather than inferred: two `curl` requests were left hanging >15s while a dump taken at
that moment showed **eight** workers idle-parked in `TaskQueue.take` with unchanged CPU counters. The
requests were accepted at TCP level and never handed to any of them.

Correct causal chain:
1. Concurrent access corrupts the unsynchronized `WeakHashMap`.
2. Two workers enter an infinite `get()` loop, pinning two cores.
3. The JVM exhausts heap; `http-nio-8080-Poller` dies of `OutOfMemoryError`.
4. Connections are accepted but never dispatched — the app is dark while the process still runs.

### 5.3 Honest limits on the causal chain

We are confident about (a) the corruption and spin and (b) the Poller death causing total
unavailability. We are **less certain that (a) caused (b)**. Two contributors we could not cleanly
separate:

1. **Corruption-driven growth.** A cyclic chain also breaks `expungeStaleEntries()` for that bucket,
   so those entries can never be unlinked; `WeakHashMap` holds *values* strongly, so each stranded
   entry pins a `UserProfile` forever.
2. **Plain session accumulation.** The login-bounce traffic creates a fresh server session per
   request. At observed rates with a 30-minute timeout, tens of thousands of live sessions
   accumulate — enough to threaten a 2 GB heap on its own, with or without corruption.

Both stem from the same traffic. The container log had rotated, retaining only the final ~30 minutes,
so we cannot see whether earlier OOMEs preceded the first spin. We therefore report the thread-safety
defect as the primary, proven finding, and heap exhaustion as an observed co-occurring failure whose
allocation driver we did not isolate.

### 5.4 No recovery, and no restart

JSPWiki does not recover: the spin is uninterruptible and the dead Poller is not replaced. Only a JVM
restart clears it (confirmed).

Kubernetes did **not** restart the pod. The Deployment defines a `readinessProbe` on `/Wiki.jsp` and
**no `livenessProbe` and no `startupProbe`**. A failing readiness probe only removes the pod from
Service endpoints; it never restarts the container. So the pod sat `0/1 Running`, serving nothing,
burning two cores, indefinitely.

That probe configuration is ours, not JSPWiki's, so it is not a JSPWiki defect. We record it because
it is why a five-hour outage went unremediated, and because a real deployment with the same very
common readiness-only configuration would sit dead identically.

## 6. Reproduction — succeeded

Deliberately reproduced on a freshly restarted, unmodified instance.

**Method.** Drive `/Login.jsp` from a separate in-cluster pod at concurrency 96, with no cookie jar
so every request is a fresh anonymous session (and therefore a `put`):
```sh
curl -s -o /dev/null --parallel --parallel-max 96 -K urls.txt   # urls.txt = 4000 × /Login.jsp
```
Poll `jcmd 1 Thread.print` every ~20s. A thread transiently inside `get()` is normal; the same thread
still there 20s later is stuck.

**Result — wedged in ~60 seconds.** `http-nio-8080-exec-10` appeared in `WeakHashMap.get` and stayed
across six consecutive samples, with the stack identical to the production incident:
```
at java.util.WeakHashMap.get(java.base@17.0.19/WeakHashMap.java:409)
at org.apache.wiki.auth.DefaultUserManager.getUserProfile(DefaultUserManager.java:148)
at org.apache.wiki.tags.UserProfileTag.doWikiStartTag(UserProfileTag.java:106)
```

**It is a true spin.** CPU rose `279,265ms → 309,525ms` across 30.26s of wall time — 30.26s of CPU in
30.26s, exactly 100% of one core. Every other worker sat at ~1,900ms; the wedged thread was ~70×
higher.

**It is permanent and load-independent.** All load was stopped; the thread kept spinning at a full
core with zero traffic arriving.

**The application still worked.** With the thread permanently lost, `/Login.jsp` still returned `200`
in 10ms — matching the incident, where the app served normally for 4.6 hours after the first thread
wedged. The damage is silent and cumulative, which is what makes it dangerous: nothing fails visibly
until enough cores are gone or the heap runs out.

## 7. Is this caused by the test instrumentation?

**No.** The instance ran a Tomcat valve (boundary logic inlined into `StandardHostValve.invoke` by a
Java agent), a JVMTI native agent, and a JaCoCo agent. We checked properly rather than relying on the
absence of our frames.

1. **No instrumentation frame is near the fault.** Wedged stacks run from `StandardHostValve.invoke`
   through stock Catalina, Jasper and JSPWiki to `WeakHashMap.get`. Our boundary runs on *entry* and
   has already returned before the application body executes. Suggestive but not conclusive alone —
   hence the rest.

2. **The serializing lock is not involved.** This was the real risk: our valve serializes explore-mode
   requests under a fair `ReentrantLock` held across the application call, and a stranded holder would
   have blocked everything. It did not happen. A dump taken while two of our own requests were hanging
   shows **zero** threads in `RequestBoundary`, `BasquinValve`, `ReentrantLock` or `FairSync` — not the
   holder, not a waiter. The incident occurred in lock-free passthrough mode.

3. **The JVMTI agent cannot produce this.** Reviewed in full (239 lines). It subscribes to
   `ThreadStart`/`ThreadEnd`, keeps two atomic counters and weak global refs to non-daemon `Thread`
   objects. It never touches `Session` or `UserProfile`, does no heap tagging, no heap walks, no
   bytecode instrumentation. Its single mutex is taken only in thread lifecycle events, never on the
   request path.

4. **The failure mode is not one instrumentation can fabricate.** This is structural, not
   circumstantial. A held lock yields a `BLOCKED` thread. A retained reference yields heap growth.
   Neither can make `e = e.next` iterate forever. An unbounded walk of a singly-linked chain requires
   the chain to be cyclic — a property of JSPWiki's own structure, reachable only through JSPWiki's own
   unsynchronized accessor. No external agent writes to `m_profiles`; the only two writers that exist
   are lines 148 and 172.

5. **The source is wrong without any instrumentation.** Upstream `master` today is a plain
   `WeakHashMap` with an unsynchronized `get`+`put` in a method reachable from a public unauthenticated
   page. Visible by inspection.

### 7.1 Caveat we will not paper over

Our driver produced concurrency at `/Login.jsp` far above what a small wiki sees organically, which is
why we hit this in minutes rather than months. That affects *how quickly* the bug manifests, not
*whether* the code is correct.

### 7.2 A note on the two test modes

Worth stating explicitly, because it cuts against us being the cause and says something sharp about
coverage-guided testing generally.

Our explore mode serializes every request under a fair lock to make per-request heap/thread deltas
meaningful. That means **explore mode structurally cannot produce this bug** — it never allows two
threads into `getUserProfile` at once. Only load mode, which is deliberately lock-free passthrough,
exposes it.

The implication is uncomfortable and general: the coverage-guided mode that reaches the most code
**cannot find concurrency defects at all**, because the very serialization that makes its measurements
clean also removes the interleaving that constitutes the bug. The "dumber" load mode, which measures
far less, is the only one that can find this class of defect. Any coverage-guided fuzzer that
serializes for measurement fidelity has the same blind spot.

And the concurrency that exposed it is not exotic. It is the concurrency any multi-user deployment
has.

## 8. Suggested fix

Minimal fix, matching what other Apache projects adopted for the identical defect:

```java
private final Map<Session, UserProfile> m_profiles =
        Collections.synchronizedMap( new WeakHashMap<>() );
```

This prevents structural corruption. Note it does **not** make `getUserProfile` atomic — the
`get` … `put` sequence remains a check-then-act race in which two concurrent requests for the same
session can each build a profile, one silently overwriting the other. If that matters, synchronize the
region:

```java
public UserProfile getUserProfile( final Session session ) {
    synchronized ( m_profiles ) {
        ...
    }
}
```

The lookup is a cheap in-memory operation, so lock contention is negligible next to the current
failure mode. A cleaner but more invasive option is to store the profile on the `Session` object
itself, removing the shared map entirely.

## 9. Prior art

Same defect class, same remedy, reported and fixed repeatedly elsewhere:

- [BEANUTILS-318](https://issues.apache.org/jira/browse/BEANUTILS-318) — "Many threads are stuck in
  infinite loops in `MethodUtils` because static `WeakHashMap` is not thread safe"
- [SANTUARIO-556](https://issues.apache.org/jira/browse/SANTUARIO-556) — "`WeakHashMap` cache cause
  infinite loop"
- [Eclipse 269867](https://bugs.eclipse.org/bugs/show_bug.cgi?id=269867) — "Non synchronized access to
  `WeakHashMap` causes infinite loop"

We found no existing JSPWiki-specific report.

## 10. Environment

| | |
|---|---|
| Application | Apache JSPWiki 2.12.4 (unmodified WAR) |
| Container | Apache Tomcat 9 |
| JVM | OpenJDK 64-Bit Server VM 17.0.19+10 |
| Heap | `-Xmx2g` |
| Platform | Kubernetes (kind), 8-core node, no CPU limit |
| Also loaded | Basquin valve + JVMTI agent, JaCoCo agent (see §7) |

## 11. Evidence files

| File | Contents |
|---|---|
| `threaddump.txt` | Original dump — `exec-2`/`exec-12` spinning at `WeakHashMap.get` |
| `threaddump-live-0500.txt` | Second dump ~350s later; CPU climbed, stack identical; no Basquin/lock frames while our own requests hung |
| `threaddump-reproduction.txt` | Deliberate reproduction; wedged thread still spinning after all load stopped |
| `DefaultUserManager.javap.txt` | Full `javap -c -p -l` disassembly of the shipped 2.12.4 class |
| `oom-poller-excerpt.log` | Container log around the Poller `OutOfMemoryError` |
| `watchdog-and-oom-timeline.log` | `WatchDog` timeouts and OOME occurrences |
| `CORRECTION.md` | Correction to the original evidence commit, which wrongly said there was no OOM |
