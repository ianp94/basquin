package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.io.ByteArrayOutputStream;

@Path("/coverage")
public class CoverageProbe {
    @GET
    @Produces("application/octet-stream")
    public byte[] dump() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object agent = Class.forName("org.jacoco.agent.rt.RT")
            .getMethod("getAgent").invoke(null);
        byte[] data = (byte[]) agent.getClass()
            .getMethod("getExecutionData", boolean.class).invoke(agent, false);
        out.write(data);
        return out.toByteArray();
    }
}
