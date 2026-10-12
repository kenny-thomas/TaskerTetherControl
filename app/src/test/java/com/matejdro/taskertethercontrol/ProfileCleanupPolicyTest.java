package com.matejdro.taskertethercontrol;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileCleanupPolicyTest {
    @Test public void removesInstalledCloneOnlyWhenInitiatedFromPrimary() {
        assertTrue(ProfileCleanupPolicy.canRemove(
                ProfileCleanupPolicy.CLONE_PROFILE, 10, 0, true));
    }
    @Test public void refusesPrimaryUser() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                ProfileCleanupPolicy.CLONE_PROFILE, 0, 0, true));
    }
    @Test public void refusesManagedWorkProfile() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                "android.os.usertype.profile.MANAGED", 10, 0, true));
    }
    @Test public void refusesPrivateProfile() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                "android.os.usertype.profile.PRIVATE", 12, 0, true));
    }
    @Test public void refusesOtherFullUser() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                "android.os.usertype.full.SECONDARY", 11, 0, true));
    }
    @Test public void refusesWhenAppNotInstalled() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                ProfileCleanupPolicy.CLONE_PROFILE, 10, 0, false));
    }
    @Test public void refusesCleanupWhenRunningFromSecondary() {
        assertFalse(ProfileCleanupPolicy.canRemove(
                ProfileCleanupPolicy.CLONE_PROFILE, 10, 2, true));
    }
}
