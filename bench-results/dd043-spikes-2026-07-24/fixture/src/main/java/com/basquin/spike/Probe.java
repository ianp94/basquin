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
}
