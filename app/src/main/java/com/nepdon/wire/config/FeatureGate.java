package com.nepdon.wire.config;

/**
 * Build-time gates for features switched off in this build. The code stays;
 * flip a flag to bring a feature back. Each flag is read where the feature
 * acts, so a value left in prefs cannot turn one on by itself.
 */
public final class FeatureGate {

    private FeatureGate() { }

    public static final boolean RADAR     = false;
    public static final boolean MOUNT_CAM = false;
    public static final boolean AIM       = false;
    public static final boolean TELE      = false;
    public static final boolean CAPTURE   = false;
}
