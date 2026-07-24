package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/")
public class Probe {
    @GET @Path("ok")       public String ok()       { return "ok"; }
    @GET @Path("boom")     public String boom()     { throw new RuntimeException("deliberate"); }
    @GET @Path("redirect") public Response redirect() {
        return Response.status(302).header("Location", "/ok").build();
    }
    @GET @Path("slow")     public String slow() throws Exception { Thread.sleep(5000); return "slow"; }
    @GET @Path("alloc")    public String alloc() {
        byte[][] keep = new byte[64][];
        for (int i = 0; i < 64; i++) keep[i] = new byte[64 * 1024];
        return "alloc " + keep.length;
    }

    /**
     * S1b's replacement for signature (ii)'s never-called probe. S1 found NeverCalled is
     * eliminated from the native image entirely by reachability analysis (it is a bare class
     * with no route referencing it, so native-image's closed-world analysis proves it dead and
     * drops it — see s1-coverage/findings.md). A JAX-RS-registered route is reachable by
     * construction (Quarkus's own routing feature references it to build the route table), so
     * it survives into the image, but this method is intentionally never requested by any
     * dump step. Its probes must read zero at every dump point; non-zero would mean build-time
     * class initialization pre-flipped probes before any request ever happened.
     */
    @GET @Path("unused")   public String unused() { return "unused"; }
}
