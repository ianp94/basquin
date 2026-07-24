package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;

/**
 * S1b (approach A): reads {@code RuntimeData} via a direct, compile-time-typed call
 * against JaCoCo's public {@code IAgent} API — no {@code Class.forName}/{@code getMethod}
 * reflection at all. {@code org.jacoco.agent:runtime} is a compile-time dependency of this
 * fixture (see pom.xml), so {@code RT} and {@code IAgent} are ordinary compiled types, and
 * native-image's closed-world analysis sees this call the same way it sees any other virtual
 * dispatch. S1 found the reflective two-hop version ({@code Class.forName("...RT")
 * .getMethod("getAgent").invoke(null)} then {@code agent.getClass().getMethod(...)}) throws
 * {@code NoSuchMethodException} under native-image's default reflection policy — this version
 * exists to test whether removing the reflection removes the failure.
 */
@Path("/coverage")
public class CoverageProbe {
    @GET
    @Produces("application/octet-stream")
    public byte[] dump() throws Exception {
        IAgent agent = RT.getAgent();
        return agent.getExecutionData(false);
    }
}
