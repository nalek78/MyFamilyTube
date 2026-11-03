package es.myfamilytube.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import es.myfamilytube.profiles.FamilyProfiles;

/**
 * 4-digit PIN entry with its own number pad, so the system keyboard never covers the screen.
 * The remote's number keys also work.
 */
final class FamilyPinPad {
    interface OnPin {
        void onPin(String pin);
    }

    private static final int PIN_LENGTH = 4;
    private static final int RED = 0xFFFF3D33;

    private final Activity mActivity;
    private final Dialog mDialog;
    private final TextView mTitle;
    private final TextView mDots;
    private final TextView mError;
    private final StringBuilder mDigits = new StringBuilder();
    private OnPin mOnPin;

    private FamilyPinPad(Activity activity, String title) {
        mActivity = activity;
        mDialog = new Dialog(activity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1D22);
        bg.setCornerRadius(dp(18));
        root.setBackground(bg);

        mTitle = text(title, 20, 0xFFF2F2F2);
        root.addView(mTitle);
        mDots = text("", 30, 0xFFF2F2F2);
        mDots.setPadding(0, dp(10), 0, dp(4));
        mDots.setLetterSpacing(0.3f);
        root.addView(mDots);
        mError = text("", 14, RED);
        mError.setPadding(0, 0, 0, dp(10));
        root.addView(mError);

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✕"}};
        View firstKey = null;
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : rowKeys) {
                TextView button = keyButton(key);
                row.addView(button);
                if (firstKey == null) {
                    firstKey = button;
                }
            }
            root.addView(row);
        }

        mDialog.setContentView(root);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                type(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                delete();
                return true;
            }
            return false;
        });
        View focus = firstKey;
        mDialog.setOnShowListener(d -> focus.requestFocus());
        refresh();
    }

    /** Asks for the PIN and runs the action if it is right (straight away if there is no PIN yet). */
    static void verify(Activity activity, Runnable action) {
        FamilyProfiles profiles = FamilyProfiles.instance(activity);
        if (!profiles.hasPin()) {
            action.run();
            return;
        }
        FamilyPinPad pad = new FamilyPinPad(activity, "PIN de control parental");
        pad.mOnPin = pin -> {
            if (profiles.checkPin(pin)) {
                pad.mDialog.dismiss();
                action.run();
            } else {
                pad.fail("PIN incorrecto");
            }
        };
        pad.mDialog.show();
    }

    /** Asks for a new PIN twice and returns it when both match. */
    static void create(Activity activity, OnPin onCreated) {
        FamilyPinPad pad = new FamilyPinPad(activity, "Elige un PIN de 4 cifras");
        String[] first = {null};
        pad.mOnPin = pin -> {
            if (first[0] == null) {
                first[0] = pin;
                pad.mTitle.setText("Repite el PIN");
                pad.clear();
            } else if (first[0].equals(pin)) {
                pad.mDialog.dismiss();
                onCreated.onPin(pin);
            } else {
                first[0] = null;
                pad.mTitle.setText("Elige un PIN de 4 cifras");
                pad.fail("No coinciden. Vuelve a empezar.");
            }
        };
        pad.mDialog.show();
    }

    private void type(String digit) {
        if (mDigits.length() >= PIN_LENGTH) {
            return;
        }
        mDigits.append(digit);
        mError.setText("");
        refresh();
        if (mDigits.length() == PIN_LENGTH && mOnPin != null) {
            String pin = mDigits.toString();
            // Let the last dot show before checking
            mDots.postDelayed(() -> mOnPin.onPin(pin), 150);
        }
    }

    private void delete() {
        if (mDigits.length() > 0) {
            mDigits.setLength(mDigits.length() - 1);
            refresh();
        }
    }

    private void clear() {
        mDigits.setLength(0);
        refresh();
    }

    private void fail(String message) {
        clear();
        mError.setText(message);
    }

    private void refresh() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots.append(i < mDigits.length() ? "●" : "○");
        }
        mDots.setText(dots);
    }

    private TextView keyButton(String key) {
        TextView button = text(key, 22, 0xFFF2F2F2);
        int size = dp(58);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = dp(5);
        params.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(params);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(keyBackground(false));
        button.setContentDescription("⌫".equals(key) ? "Borrar" : "✕".equals(key) ? "Cancelar" : key);
        button.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(keyBackground(hasFocus)));
        button.setOnClickListener(v -> {
            if ("⌫".equals(key)) {
                delete();
            } else if ("✕".equals(key)) {
                mDialog.dismiss();
            } else {
                type(key);
            }
        });
        return button;
    }

    private GradientDrawable keyBackground(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(focused ? 0xFF2A2D33 : 0xFF101114);
        if (focused) {
            drawable.setStroke(dp(3), RED);
        }
        return drawable;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(float value) {
        return (int) (value * mActivity.getResources().getDisplayMetrics().density);
    }
}
