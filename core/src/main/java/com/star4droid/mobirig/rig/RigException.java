package com.star4droid.mobirig.rig;

/** A rigging error with a message meant for the user. */
public class RigException extends RuntimeException {
    public RigException(String message) {
        super(message);
    }
}
