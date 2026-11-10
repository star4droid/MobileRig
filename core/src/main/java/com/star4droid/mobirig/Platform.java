package com.star4droid.mobirig;

/** Platform-specific services (file picking etc.). Implemented by each launcher. */
public interface Platform {

    /** Receives the picked file. Always called on the render thread. */
    interface FileCallback {
        void onPicked(String name, byte[] data);

        void onError(String message);
    }

    /** Opens the system file picker for a .glb model. */
    void pickModel(FileCallback callback);

    /** Optional model to open at start-up (desktop: first command-line argument). */
    default void initialModel(FileCallback callback) {
    }

    /** Default used when no launcher provides one (tests, headless). */
    Platform NONE = new Platform() {
        @Override
        public void pickModel(FileCallback callback) {
            callback.onError("File picking is not available on this platform");
        }
    };
}
