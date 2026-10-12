package com.matejdro.taskertethercontrol;

/** Fail-closed policy for removing ONLY our duplicate from a verified clone user. */
final class ProfileCleanupPolicy {
    static final String CLONE_PROFILE = "android.os.usertype.profile.CLONE";
    private ProfileCleanupPolicy() {}

    static boolean canRemove(String profileType, int profileUserId,
                             int currentUserId, boolean installed) {
        return installed && CLONE_PROFILE.equals(profileType) &&
                currentUserId == 0 && profileUserId > 0 &&
                profileUserId != currentUserId;
    }
}
