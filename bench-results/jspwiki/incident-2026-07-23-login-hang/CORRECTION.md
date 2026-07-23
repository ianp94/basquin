# Correction to the evidence commit's description

The commit that added `threaddump.txt` (`a82756c`) described the incident as
"no crash, no OOM and no restart". **The "no OOM" part is wrong**, and the correction matters
because it changes the mechanism.

I inferred the absence of an OOM from the fact that the pod never restarted, and never checked the
log for one. The root-cause investigation found:

```
Exception in thread "http-nio-8080-Poller" java.lang.OutOfMemoryError: Java heap space
```

at roughly 04:31. That is the **proximate cause of total unavailability**. With the NIO Poller dead,
accepted connections are never dispatched to a worker at all — confirmed live on the wedged pod,
where two `curl`s hung for more than 15 seconds while eight worker threads sat idle-parked and never
picked them up.

So the correct causal chain is:

1. Concurrent access corrupts an unsynchronized `WeakHashMap` in `DefaultUserManager`.
2. Two workers enter an infinite `get()` loop and pin two cores indefinitely.
3. The JVM eventually exhausts heap; the `http-nio-8080-Poller` thread dies of `OutOfMemoryError`.
4. Connections are accepted but never dispatched — the app is dark while the process still runs,
   which is why Kubernetes never restarted it.

**"The thread pool bled out" is NOT the correct reading.** Only 2 of roughly 10 workers were ever
stuck; the remaining eight were idle and available. The spinning workers are the antecedent, not the
mechanism of the outage.

Recording this here rather than silently amending, because the original claim was published in a PR
description and a commit message before it was checked.
