package es.myfamilytube.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * The little vibration phones give when you tap something. Works for every button, card and
 * row of a screen at once: a tap (not a scroll) that lands on something tappable vibrates.
 * Follows the phone's own "vibrate on touch" setting.
 */
final class MobileHaptics {
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private final Rect mRect = new Rect();

    void onTouch(Activity activity, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mDownTime = event.getEventTime();
                break;
            case MotionEvent.ACTION_UP:
                int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                boolean tap = Math.abs(event.getRawX() - mDownX) < slop && Math.abs(event.getRawY() - mDownY) < slop
                        && event.getEventTime() - mDownTime < ViewConfiguration.getLongPressTimeout();
                if (tap) {
                    View target = find(activity.getWindow().getDecorView(), (int) event.getRawX(), (int) event.getRawY());
                    if (target != null) {
                        tap(target);
                    }
                }
                break;
            default:
                break;
        }
    }

    static void tap(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** The front-most tappable view under the finger. */
    private View find(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || !view.isEnabled() && !(view instanceof ViewGroup)) {
            return null;
        }
        if (!view.getGlobalVisibleRect(mRect) || !mRect.contains(x, y)) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = find(group.getChildAt(i), x, y);
                if (found != null) {
                    return found;
                }
            }
        }
        return view.isClickable() && view.isEnabled() ? view : null;
    }
}
