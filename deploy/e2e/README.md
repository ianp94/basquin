# Operator in-cluster e2e

`e2e.sh` runs the full operator pipeline against a local **kind** cluster and asserts it works — the
test envtest structurally can't do (it exercises the built operator image, the namespaced
ServiceAccount/Role/RoleBinding, the injected initContainer pulling the real agents image, and the
agents actually loading in a live JVM).

## Run

```bash
deploy/e2e/e2e.sh              # build+load images, deploy, inject, assert
deploy/e2e/e2e.sh --teardown   # remove the target, operator, CRD, namespace
```

It will:
1. ensure the kind cluster, then **build + load** the agents image, the operator image, and a **raw**
   (un-instrumented) JPetStore image;
2. install the CRD and deploy the operator as a pod with its **namespaced RBAC** (not admin creds);
3. deploy the raw app (with its own `CATALINA_OPTS`, to prove append-not-replace) and apply a
   `ClosureJVMTarget`;
4. assert: target `Injected`, the agents initContainer was added, `CATALINA_OPTS` was **appended**
   (original kept), the operator ran with **no RBAC `forbidden` errors**, the agents are on the live
   app JVM's command line, and the app serves HTTP 200 with them loaded.

## Config (env)

`CLUSTER` (default `closurejvm`), `NS` (`closurejvm-system`), `TAG` (`0.2.0`), `RAW_APP_IMAGE`
(`closurejvm/jpetstore-raw:0.2.0`). If the raw image isn't present it's built from `JPETSTORE_WAR`,
or by extracting `ROOT.war` from `closurejvm/jpetstore-demo:latest` if that image exists.
