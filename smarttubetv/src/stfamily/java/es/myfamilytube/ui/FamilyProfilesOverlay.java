package es.myfamilytube.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/** "¿Quién está viendo?" screen and the profile editor, drawn over the home screen. */
final class FamilyProfilesOverlay {
    interface Callback {
        void onProfileChosen(FamilyProfile profile);
    }

    private static final int RING_COLOR = 0xFFFF3D33;

    private final Activity mActivity;
    private final ViewGroup mHost;
    private final Callback mCallback;
    private final FamilyProfiles mProfiles;
    private View mRoot;
    private View mPicker;
    private LinearLayout mItems;
    private TextView mPickerTitle;
    private TextView mManageButton;
    private View mEditor;
    private boolean mManageMode;

    // Editor state
    private FamilyProfile mEditing; // null = new profile
    private boolean mEditKid;
    private int mEditAge = FamilyProfile.AGE_5_TO_8;

    FamilyProfilesOverlay(Activity activity, ViewGroup host, Callback callback) {
        mActivity = activity;
        mHost = host;
        mCallback = callback;
        mProfiles = FamilyProfiles.instance(activity);
    }

    boolean isShowing() {
        return mRoot != null && mRoot.getVisibility() == View.VISIBLE;
    }

    void show() {
        ensureViews();
        mManageMode = false;
        mRoot.setVisibility(View.VISIBLE);
        showPicker();
    }

    void hide() {
        if (mRoot != null) {
            mRoot.setVisibility(View.GONE);
        }
    }

    /** @return true when Back was handled here */
    boolean onBack() {
        if (!isShowing()) {
            return false;
        }
        if (mEditor.getVisibility() == View.VISIBLE) {
            showPicker();
        } else if (mManageMode) {
            mManageMode = false;
            showPicker();
        } else {
            hide();
        }
        return true;
    }

    private void ensureViews() {
        if (mRoot != null) {
            return;
        }
        mRoot = LayoutInflater.from(mActivity).inflate(R.layout.family_profiles, mHost, false);
        mHost.addView(mRoot);
        mPicker = mRoot.findViewById(R.id.family_picker);
        mItems = mRoot.findViewById(R.id.family_picker_items);
        mPickerTitle = mRoot.findViewById(R.id.family_picker_title);
        mManageButton = mRoot.findViewById(R.id.family_picker_manage);
        mEditor = mRoot.findViewById(R.id.family_editor);

        mManageButton.setOnClickListener(v -> askPinThen(() -> {
            mManageMode = !mManageMode;
            showPicker();
        }));

        TextView adult = mRoot.findViewById(R.id.family_editor_adult);
        TextView kid = mRoot.findViewById(R.id.family_editor_kid);
        adult.setOnClickListener(v -> { mEditKid = false; refreshEditor(); });
        kid.setOnClickListener(v -> { mEditKid = true; refreshEditor(); });

        LinearLayout ages = mRoot.findViewById(R.id.family_editor_ages);
        for (int i = 0; i < ages.getChildCount(); i++) {
            final int age = i;
            ages.getChildAt(i).setOnClickListener(v -> { mEditAge = age; refreshEditor(); });
        }

        mRoot.findViewById(R.id.family_editor_save).setOnClickListener(v -> saveEditor());
        mRoot.findViewById(R.id.family_editor_cancel).setOnClickListener(v -> showPicker());
        mRoot.findViewById(R.id.family_editor_delete).setOnClickListener(v -> deleteEditing());
    }

    // ---------------------------------------------------------------- picker

    private void showPicker() {
        mEditor.setVisibility(View.GONE);
        mPicker.setVisibility(View.VISIBLE);
        mPickerTitle.setText(mManageMode ? "Elige el perfil que quieres cambiar" : "¿Quién está viendo?");
        mManageButton.setText(mManageMode ? "Listo" : "Gestionar perfiles");
        mManageButton.setActivated(mManageMode);

        mItems.removeAllViews();
        FamilyProfile current = mProfiles.getCurrent();
        View focusTarget = null;
        for (FamilyProfile profile : mProfiles.getAll()) {
            View item = createItem(profile.getInitial(), profile.name, profile.getDescription(), profile.color);
            item.setOnClickListener(v -> onProfileClicked(profile));
            mItems.addView(item);
            if (profile.id.equals(current.id)) {
                focusTarget = item;
            }
        }
        View add = createItem("+", "Añadir perfil", "Adulto o infantil", 0);
        add.setOnClickListener(v -> askPinThen(() -> openEditor(null)));
        mItems.addView(add);

        View target = focusTarget != null ? focusTarget : add;
        target.post(target::requestFocus);
    }

    private View createItem(String initial, String name, String description, int color) {
        View item = LayoutInflater.from(mActivity).inflate(R.layout.family_profile_item, mItems, false);
        TextView avatar = item.findViewById(R.id.family_profile_avatar);
        TextView nameView = item.findViewById(R.id.family_profile_name);
        TextView descView = item.findViewById(R.id.family_profile_desc);
        avatar.setText(initial);
        nameView.setText(name);
        descView.setText(description);
        avatar.setBackground(avatarDrawable(color, false));
        item.setOnFocusChangeListener((v, hasFocus) -> {
            avatar.setBackground(avatarDrawable(color, hasFocus));
            float scale = hasFocus ? 1.08f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
        return item;
    }

    private GradientDrawable avatarDrawable(int color, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        float density = mActivity.getResources().getDisplayMetrics().density;
        if (color == 0) {
            // "Añadir perfil": empty circle
            drawable.setColor(0x00000000);
            drawable.setStroke((int) (2 * density), focused ? RING_COLOR : 0xFF5A5E66, 8 * density, 6 * density);
        } else {
            drawable.setColor(color);
            if (focused) {
                drawable.setStroke((int) (4 * density), RING_COLOR);
            }
        }
        return drawable;
    }

    private void onProfileClicked(FamilyProfile profile) {
        if (mManageMode) {
            openEditor(profile);
            return;
        }
        FamilyProfile current = mProfiles.getCurrent();
        boolean leavingKid = current.isKid && !profile.isKid;
        if (leavingKid) {
            askPinThen(() -> choose(profile));
        } else {
            choose(profile);
        }
    }

    private void choose(FamilyProfile profile) {
        mProfiles.setCurrent(profile);
        hide();
        mCallback.onProfileChosen(profile);
    }

    /** Runs the action straight away, or after the parental PIN when one is set and a kid is watching. */
    private void askPinThen(Runnable action) {
        if (!mProfiles.hasPin() || !mProfiles.getCurrent().isKid) {
            action.run();
            return;
        }
        EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(mActivity)
                .setTitle("PIN de control parental")
                .setView(input)
                .setPositiveButton("Aceptar", (dialog, which) -> {
                    if (mProfiles.checkPin(input.getText().toString())) {
                        action.run();
                    } else {
                        Toast.makeText(mActivity, "PIN incorrecto", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- editor

    private void openEditor(FamilyProfile profile) {
        mEditing = profile;
        mEditKid = profile != null && profile.isKid;
        mEditAge = profile != null ? profile.ageGroup : FamilyProfile.AGE_5_TO_8;

        TextView title = mRoot.findViewById(R.id.family_editor_title);
        EditText name = mRoot.findViewById(R.id.family_editor_name);
        EditText pin = mRoot.findViewById(R.id.family_editor_pin);
        title.setText(profile == null ? "Nuevo perfil" : "Editar perfil");
        name.setText(profile != null ? profile.name : "");
        pin.setText("");
        mRoot.findViewById(R.id.family_editor_delete)
                .setVisibility(profile != null && mProfiles.canRemove(profile) ? View.VISIBLE : View.GONE);

        mPicker.setVisibility(View.GONE);
        mEditor.setVisibility(View.VISIBLE);
        refreshEditor();
        name.post(name::requestFocus);
    }

    private void refreshEditor() {
        mRoot.findViewById(R.id.family_editor_adult).setActivated(!mEditKid);
        mRoot.findViewById(R.id.family_editor_kid).setActivated(mEditKid);
        mRoot.findViewById(R.id.family_editor_kid_options).setVisibility(mEditKid ? View.VISIBLE : View.GONE);
        mRoot.findViewById(R.id.family_editor_pin_box).setVisibility(mEditKid && !mProfiles.hasPin() ? View.VISIBLE : View.GONE);
        LinearLayout ages = mRoot.findViewById(R.id.family_editor_ages);
        for (int i = 0; i < ages.getChildCount(); i++) {
            ages.getChildAt(i).setActivated(i == mEditAge);
        }
    }

    private void saveEditor() {
        EditText nameView = mRoot.findViewById(R.id.family_editor_name);
        String name = nameView.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(mActivity, "Escribe un nombre", Toast.LENGTH_SHORT).show();
            nameView.requestFocus();
            return;
        }
        if (mEditKid && !mProfiles.hasPin()) {
            EditText pinView = mRoot.findViewById(R.id.family_editor_pin);
            String pin = pinView.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(mActivity, "El PIN tiene que tener 4 cifras", Toast.LENGTH_SHORT).show();
                pinView.requestFocus();
                return;
            }
            mProfiles.setPin(pin);
        }
        if (mEditing == null) {
            mProfiles.add(name, mEditKid, mEditAge);
        } else {
            if (mEditKid && !mEditing.isKid && !mProfiles.canRemove(mEditing)) {
                // Would leave no adult to manage the others
                Toast.makeText(mActivity, "Tiene que quedar al menos un perfil de adulto", Toast.LENGTH_LONG).show();
                return;
            }
            mEditing.name = name;
            mEditing.isKid = mEditKid;
            mEditing.ageGroup = mEditAge;
            mProfiles.update(mEditing);
        }
        showPicker();
    }

    private void deleteEditing() {
        if (mEditing == null || !mProfiles.canRemove(mEditing)) {
            return;
        }
        FamilyProfile profile = mEditing;
        new AlertDialog.Builder(mActivity)
                .setTitle("¿Borrar el perfil de " + profile.name + "?")
                .setMessage("Se borrarán sus ajustes y sus bibliotecas en esta tele.")
                .setPositiveButton("Borrar", (dialog, which) -> {
                    mProfiles.remove(profile);
                    showPicker();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
