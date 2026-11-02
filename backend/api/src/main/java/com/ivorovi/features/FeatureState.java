package com.ivorovi.features;

/** Effective value of a flag and the configuration layer that decided it. */
public record FeatureState(FeatureFlag flag, boolean enabled, Source source) {

    public enum Source { DEPLOYMENT, DEFAULT, TENANT, INSTITUTION }
}
