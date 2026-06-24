package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Handler;
import android.os.Looper;

/**
 * Utility for the common widget reattach pattern: try to attach to MapActivity,
 * and if the view isn't ready yet, retry on a fixed interval.
 */
public final class WidgetReattachHelper {
    private WidgetReattachHelper() {}
    
    public interface ReattachCallback {
        boolean tryAttach();  // return true if attached successfully, false to retry
        int getReattachIntervalMs();
        int getUpdateIntervalMs();
        void onUpdate();
    }
    
    public static void scheduleReattach(Handler handler, ReattachCallback callback) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (callback.tryAttach()) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            callback.onUpdate();
                            handler.postDelayed(this, callback.getUpdateIntervalMs());
                        }
                    }, callback.getUpdateIntervalMs());
                } else {
                    handler.postDelayed(this, callback.getReattachIntervalMs());
                }
            }
        }, callback.getReattachIntervalMs());
    }
    
    public static void cancel(Handler handler) {
        handler.removeCallbacksAndMessages(null);
    }
}