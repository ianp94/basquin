package com.basquin.quarkus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * DD-043 PR-2 decision spike (.superpowers/sdd/pr2-spike-report.md): the only build step in this
 * module. It exists solely to prove that a Gradle-built {@code io.quarkus.extension} artifact is
 * honoured by a Maven-built Quarkus application's augmentation — signal is
 * {@code Installed features:} listing {@code basquin} in the consuming app's startup banner.
 *
 * <p>Deliberately trivial: no boundary filter, no routes, no {@code basquin-core} dependency.
 * Those belong to PR-2 proper, once this spike settles the toolchain question.
 */
public class BasquinProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("basquin");
    }
}
