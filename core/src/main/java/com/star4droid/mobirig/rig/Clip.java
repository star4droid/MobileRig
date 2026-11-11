package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;

/** An animation that can be sampled on a fitted skeleton (built-in or retargeted from a file). */
public interface Clip {
    String name();

    /** Loop length in seconds. */
    float length();

    /** Local rotations of every joint at time t; hipsOffset receives the hips translation (world units). */
    Quaternion[] sample(Skeleton sk, float t, Vector3 hipsOffset);
}
