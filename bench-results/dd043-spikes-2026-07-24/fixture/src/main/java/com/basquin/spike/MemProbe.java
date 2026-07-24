package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/mem")
public class MemProbe {
    @GET @Path("used")
    public String used() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) + "," + r.totalMemory() + "," + r.maxMemory();
    }

    @GET @Path("gc")
    public String gc() {
        Runtime r = Runtime.getRuntime();
        long before = r.totalMemory() - r.freeMemory();
        System.gc();
        long after = r.totalMemory() - r.freeMemory();
        return before + "," + after;
    }
}
