# Locust comparison workload for JPetStore-6 — the same GET-safe catalog route mix as the k6 script
# (deploy/bench/k6/jpetstore.js), used for the v0.3.0 load-tool comparison (docs/BENCHMARKS.md).
# Closed-loop (wait_time = 0) to match k6/Basquin. Client throughput + percentiles only.
#
# Run (in-cluster, headless), multi-process so it isn't GIL-bound — a single Locust process caps
# out ~120 rps; use --processes to distribute, matching the benchmark's 8-process run:
#   locust -f deploy/bench/locust/locustfile.py \
#     --host http://jpetstore-app.basquin-system.svc.cluster.local:8080 \
#     --users 50 --spawn-rate 50 --run-time 2m --headless --processes -1
import random
from locust import HttpUser, task, constant

ROUTES = [
    '/', '/actions/Catalog.action',
    '/actions/Catalog.action?viewCategory=&categoryId=FISH',
    '/actions/Catalog.action?viewCategory=&categoryId=DOGS',
    '/actions/Catalog.action?viewCategory=&categoryId=CATS',
    '/actions/Catalog.action?viewCategory=&categoryId=BIRDS',
    '/actions/Catalog.action?viewCategory=&categoryId=REPTILES',
    '/actions/Catalog.action?viewProduct=&productId=FI-SW-01',
    '/actions/Catalog.action?viewProduct=&productId=K9-BD-01',
    '/actions/Catalog.action?viewProduct=&productId=RP-SN-01',
    '/actions/Catalog.action?viewItem=&itemId=EST-1',
    '/actions/Catalog.action?viewItem=&itemId=EST-10',
    '/actions/Catalog.action?viewItem=&itemId=EST-18',
    '/actions/Catalog.action?searchProducts=&keyword=fish',
    '/actions/Catalog.action?searchProducts=&keyword=dog',
    '/actions/Catalog.action?searchProducts=&keyword=reptile',
]


class Jp(HttpUser):
    wait_time = constant(0)  # closed-loop, no think time (matches k6 / Basquin)

    @task
    def hit(self):
        self.client.get(random.choice(ROUTES), name="catalog")
