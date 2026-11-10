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

import java.util.LinkedHashMap;
import java.util.Map;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SignInPresenter;

import java.util.List;

import es.myfamilytube.profiles.FamilyAccounts;
import es.myfamilytube.profiles.FamilyProfile;
import es.myfamilytube.profiles.FamilyProfiles;

/** "¿Quién está viendo?" screen and the profile editor (with parental control), drawn over the home screen. */
final class FamilyProfilesOverlay {
    interface Callback {
        void onProfileChosen(FamilyProfile profile);
    }

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
    private boolean mMandatory;

    // Editor state
    private FamilyProfile mEditing; // null = new profile
    private boolean mEditKid;
    private int mEditAge = FamilyProfile.AGE_5_TO_8;
    private String mEditAvatar = "";
    private String mEditAccount = "";
    /** Accounts there were before "Añadir otra cuenta", to spot the new one when sign-in returns. */
    private java.util.Set<String> mAccountsBeforeSignIn;
    private boolean mEditApprovedOnly;
    private int mEditLimitMin;
    private boolean mEditHideComments = true;
    private final Map<String, String> mEditChannels = new LinkedHashMap<>();

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
        mMandatory = false;
        mManageMode = false;
        mRoot.setVisibility(View.VISIBLE);
        showPicker();
    }

    /** At app start: a profile must be chosen; Back closes the app instead of skipping. */
    void showMandatory() {
        show();
        mMandatory = true;
    }

    /** Straight to "Gestionar perfiles" (from the settings), after the PIN when needed. */
    void showManage() {
        askPinToManage(() -> {
            ensureViews();
            mMandatory = false;
            mManageMode = true;
            mRoot.setVisibility(View.VISIBLE);
            showPicker();
        });
    }

    /**
     * Managing profiles needs the PIN unless a grown-up already typed it to get in
     * (that's the case when grown-up profiles are PIN protected).
     */
    private void askPinToManage(Runnable action) {
        FamilyProfile current = mProfiles.getCurrent();
        boolean alreadyUnlocked = !current.isKid && mProfiles.isAdultPinEnabled();
        if (alreadyUnlocked) {
            action.run();
        } else {
            askPin(action);
        }
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
        } else if (mManageMode && mMandatory) {
            mManageMode = false;
            showPicker();
        } else if (mMandatory) {
            mActivity.finish(); // leave the app; next time it asks again
        } else {
            hide();
        }
        return true;
    }

    private float dp(float value) {
        return value * mActivity.getResources().getDisplayMetrics().density;
    }

    private void ensureViews() {
        if (mRoot != null) {
            return;
        }
        mRoot = LayoutInflater.from(mActivity).inflate(FamilyLayouts.profiles, mHost, false);
        mHost.addView(mRoot);
        mPicker = mRoot.findViewById(R.id.family_picker);
        mItems = mRoot.findViewById(R.id.family_picker_items);
        mPickerTitle = mRoot.findViewById(R.id.family_picker_title);
        mManageButton = mRoot.findViewById(R.id.family_picker_manage);
        mEditor = mRoot.findViewById(R.id.family_editor);

        mManageButton.setOnClickListener(v -> {
            if (mManageMode) {
                mManageMode = false;
                showPicker();
            } else {
                // From the picker nobody has typed the PIN yet
                askPin(() -> {
                    mManageMode = true;
                    showPicker();
                });
            }
        });

        mRoot.findViewById(R.id.family_editor_adult).setOnClickListener(v -> { mEditKid = false; refreshEditor(); });
        mRoot.findViewById(R.id.family_editor_kid).setOnClickListener(v -> {
            if (!mEditKid) {
                mEditKid = true;
                applyAgeDefaults();
            }
            refreshEditor();
        });

        LinearLayout ages = mRoot.findViewById(R.id.family_editor_ages);
        for (int i = 0; i < ages.getChildCount(); i++) {
            final int age = i;
            ages.getChildAt(i).setOnClickListener(v -> {
                if (mEditAge != age) {
                    mEditAge = age;
                    applyAgeDefaults();
                }
                refreshEditor();
            });
        }
        LinearLayout content = mRoot.findViewById(R.id.family_editor_content);
        content.getChildAt(0).setOnClickListener(v -> { mEditApprovedOnly = false; refreshEditor(); });
        content.getChildAt(1).setOnClickListener(v -> { mEditApprovedOnly = true; refreshEditor(); });

        LinearLayout limits = mRoot.findViewById(R.id.family_editor_limits);
        for (int i = 0; i < limits.getChildCount() && i < FamilyProfile.LIMITS_MIN.length; i++) {
            final int minutes = FamilyProfile.LIMITS_MIN[i];
            limits.getChildAt(i).setOnClickListener(v -> { mEditLimitMin = minutes; refreshEditor(); });
        }
        mRoot.findViewById(R.id.family_editor_comments_hide).setOnClickListener(v -> { mEditHideComments = true; refreshEditor(); });
        mRoot.findViewById(R.id.family_editor_comments_show).setOnClickListener(v -> { mEditHideComments = false; refreshEditor(); });

        buildAvatarChoices();
        mRoot.findViewById(R.id.family_editor_account).setOnClickListener(v -> chooseAccount());

        mRoot.findViewById(R.id.family_editor_save).setOnClickListener(v -> saveEditor());
        mRoot.findViewById(R.id.family_editor_cancel).setOnClickListener(v -> showPicker());
        mRoot.findViewById(R.id.family_editor_delete).setOnClickListener(v -> deleteEditing());
    }

    /** Starting values for a kid of the chosen age (time limit, comments). */
    private void applyAgeDefaults() {
        FamilyProfile temp = new FamilyProfile("", "", 0, true, mEditAge);
        temp.applyAgeDefaults();
        mEditLimitMin = temp.dailyLimitMin;
        mEditHideComments = temp.hideComments;
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
            View item = createItem(profile.avatar, profile.color, profile.getInitial(), profile.name, profile.getDescription());
            item.setOnClickListener(v -> onProfileClicked(profile));
            mItems.addView(item);
            if (profile.id.equals(current.id)) {
                focusTarget = item;
            }
        }
        View add = createAddItem();
        add.setOnClickListener(v -> {
            if (mManageMode) {
                openEditor(null); // PIN already typed to enter "Gestionar"
            } else {
                askPin(() -> openEditor(null));
            }
        });
        mItems.addView(add);

        View target = focusTarget != null ? focusTarget : add;
        target.post(target::requestFocus);
    }

    private View createItem(String avatar, int color, String initial, String name, String description) {
        View item = LayoutInflater.from(mActivity).inflate(FamilyLayouts.profileItem, mItems, false);
        TextView avatarView = item.findViewById(R.id.family_profile_avatar);
        ((TextView) item.findViewById(R.id.family_profile_name)).setText(name);
        ((TextView) item.findViewById(R.id.family_profile_desc)).setText(description);
        FamilyAvatars.apply(avatarView, avatar, color, initial, false, 4);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            FamilyAvatars.apply(avatarView, avatar, color, initial, hasFocus, 4);
            float scale = hasFocus ? 1.06f : 1f;
            avatarView.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
        return item;
    }

    private View createAddItem() {
        View item = LayoutInflater.from(mActivity).inflate(FamilyLayouts.profileItem, mItems, false);
        TextView avatar = item.findViewById(R.id.family_profile_avatar);
        ((TextView) item.findViewById(R.id.family_profile_name)).setText("Añadir perfil");
        ((TextView) item.findViewById(R.id.family_profile_desc)).setText("Adulto o infantil");
        avatar.setText("+");
        avatar.setTextColor(0xFFC9CCD2);
        avatar.setBackground(dashedCircle(false));
        item.setOnFocusChangeListener((v, hasFocus) -> avatar.setBackground(dashedCircle(hasFocus)));
        return item;
    }

    private GradientDrawable dashedCircle(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0x00000000);
        drawable.setStroke((int) dp(focused ? 3 : 2), focused ? FamilyAvatars.RING_COLOR : 0xFF5A5E66, dp(8), dp(6));
        return drawable;
    }

    private void onProfileClicked(FamilyProfile profile) {
        if (mManageMode) {
            openEditor(profile);
            return;
        }
        // Grown-up profiles are PIN protected once there are kids, even if it was the last one used
        if (mProfiles.needsPinFor(profile)) {
            askPin(() -> choose(profile));
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
    void askPinThen(Runnable action) {
        if (!mProfiles.hasPin() || !mProfiles.getCurrent().isKid) {
            action.run();
            return;
        }
        askPin(action);
    }

    /** Always asks for the PIN (when there is one). */
    void askPin(Runnable action) {
        // Own number pad: the system keyboard used to cover the whole screen
        FamilyPinPad.verify(mActivity, action);
    }

    // ---------------------------------------------------------------- editor

    private void buildAvatarChoices() {
        LinearLayout row = mRoot.findViewById(R.id.family_editor_avatars);
        row.removeAllViews();
        int size = (int) dp(FamilyLayouts.mobile ? 66 : 54);
        int margin = (int) dp(6);
        // First choice: the coloured initial, then the pictures
        String[] choices = new String[FamilyProfile.AVATARS.length + 1];
        choices[0] = "";
        System.arraycopy(FamilyProfile.AVATARS, 0, choices, 1, FamilyProfile.AVATARS.length);
        for (String choice : choices) {
            TextView view = new TextView(mActivity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, margin, margin, margin);
            view.setLayoutParams(params);
            view.setGravity(android.view.Gravity.CENTER);
            view.setTextColor(0xFFFFFFFF);
            view.setTextSize(20);
            view.setFocusable(true);
            view.setClickable(true);
            view.setTag(choice);
            view.setContentDescription(choice.isEmpty() ? "Inicial" : choice);
            view.setOnClickListener(v -> { mEditAvatar = choice; refreshEditor(); });
            view.setOnFocusChangeListener((v, hasFocus) -> refreshAvatarChoice((TextView) v));
            row.addView(view);
        }
    }

    private void refreshAvatarChoice(TextView view) {
        String choice = (String) view.getTag();
        boolean selected = choice.equals(mEditAvatar);
        int color = mEditing != null ? mEditing.color : FamilyProfile.COLORS[mProfiles.getAll().size() % FamilyProfile.COLORS.length];
        EditText name = mRoot.findViewById(R.id.family_editor_name);
        String text = name.getText().toString().trim();
        String initial = text.isEmpty() ? "?" : text.substring(0, 1).toUpperCase();
        // Ring = focus; a thinner white ring marks the chosen one
        FamilyAvatars.apply(view, choice, color, initial, view.hasFocus() || selected, view.hasFocus() ? 3 : 2);
        if (selected && !view.hasFocus()) {
            view.setAlpha(1f);
        } else {
            view.setAlpha(view.hasFocus() ? 1f : 0.55f);
        }
    }

    private void openEditor(FamilyProfile profile) {
        mEditing = profile;
        mEditKid = profile != null && profile.isKid;
        mEditAge = profile != null ? profile.ageGroup : FamilyProfile.AGE_5_TO_8;
        mEditAvatar = profile != null ? profile.avatar : "";
        mEditAccount = profile != null ? profile.accountKey : "";
        mEditApprovedOnly = profile != null && profile.approvedOnly;
        mEditLimitMin = profile != null ? profile.dailyLimitMin : 0;
        mEditHideComments = profile == null || profile.hideComments;
        mEditChannels.clear();
        if (profile != null) {
            mEditChannels.putAll(profile.approvedChannels);
        }

        TextView title = mRoot.findViewById(R.id.family_editor_title);
        EditText name = mRoot.findViewById(R.id.family_editor_name);
        title.setText(profile == null ? "Nuevo perfil" : "Editar perfil");
        name.setText(profile != null ? profile.name : "");
        mRoot.findViewById(R.id.family_editor_delete)
                .setVisibility(profile != null && mProfiles.canRemove(profile) ? View.VISIBLE : View.GONE);

        mPicker.setVisibility(View.GONE);
        mEditor.setVisibility(View.VISIBLE);
        mEditor.scrollTo(0, 0);
        refreshEditor();
        name.post(name::requestFocus);
    }

    private void refreshEditor() {
        ((TextView) mRoot.findViewById(R.id.family_editor_account)).setText(FamilyAccounts.labelOfKey(mEditAccount) + "  ›");
        mRoot.findViewById(R.id.family_editor_adult).setActivated(!mEditKid);
        mRoot.findViewById(R.id.family_editor_kid).setActivated(mEditKid);
        mRoot.findViewById(R.id.family_editor_kid_options).setVisibility(mEditKid ? View.VISIBLE : View.GONE);
        mRoot.findViewById(R.id.family_editor_pin_box).setVisibility(mEditKid && !mProfiles.hasPin() ? View.VISIBLE : View.GONE);

        LinearLayout ages = mRoot.findViewById(R.id.family_editor_ages);
        for (int i = 0; i < ages.getChildCount(); i++) {
            ages.getChildAt(i).setActivated(i == mEditAge);
        }
        LinearLayout content = mRoot.findViewById(R.id.family_editor_content);
        content.getChildAt(0).setActivated(!mEditApprovedOnly);
        content.getChildAt(1).setActivated(mEditApprovedOnly);
        TextView hint = mRoot.findViewById(R.id.family_editor_content_hint);
        hint.setText(mEditApprovedOnly
                ? "Solo verá vídeos de los canales de la lista de abajo, también en la búsqueda."
                : "Puede buscar. Se quitan Shorts y vídeos con palabras de contenido adulto; hasta 8 años, también de miedo y violencia.");

        LinearLayout limits = mRoot.findViewById(R.id.family_editor_limits);
        for (int i = 0; i < limits.getChildCount() && i < FamilyProfile.LIMITS_MIN.length; i++) {
            limits.getChildAt(i).setActivated(FamilyProfile.LIMITS_MIN[i] == mEditLimitMin);
        }
        mRoot.findViewById(R.id.family_editor_comments_hide).setActivated(mEditHideComments);
        mRoot.findViewById(R.id.family_editor_comments_show).setActivated(!mEditHideComments);

        LinearLayout avatars = mRoot.findViewById(R.id.family_editor_avatars);
        for (int i = 0; i < avatars.getChildCount(); i++) {
            refreshAvatarChoice((TextView) avatars.getChildAt(i));
        }
        refreshChannels();
    }

    private void refreshChannels() {
        LinearLayout list = mRoot.findViewById(R.id.family_editor_channels);
        list.removeAllViews();
        if (mEditChannels.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText("Todavía no hay ninguno.");
            empty.setTextColor(0xFFA7ABB3);
            empty.setTextSize(14);
            empty.setGravity(android.view.Gravity.CENTER);
            list.addView(empty);
            return;
        }
        for (Map.Entry<String, String> entry : mEditChannels.entrySet()) {
            TextView row = (TextView) LayoutInflater.from(mActivity).inflate(FamilyLayouts.channelRow, list, false);
            row.setText(entry.getValue() + "   ✕ quitar");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(40));
            params.setMargins(0, (int) dp(3), 0, (int) dp(3));
            row.setLayoutParams(params);
            row.setOnClickListener(v -> {
                mEditChannels.remove(entry.getKey());
                refreshChannels();
                list.requestFocus();
            });
            list.addView(row);
        }
    }

    /** No account, one of the accounts signed in on this TV, or sign in a new one. */
    private void chooseAccount() {
        List<Account> accounts = FamilyAccounts.list();
        String[] options = new String[accounts.size() + 2];
        options[0] = "Sin cuenta";
        for (int i = 0; i < accounts.size(); i++) {
            options[i + 1] = FamilyAccounts.labelOf(accounts.get(i));
        }
        options[options.length - 1] = "Añadir otra cuenta de Google…";
        new AlertDialog.Builder(mActivity)
                .setTitle("Cuenta de Google del perfil")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        mEditAccount = "";
                    } else if (which <= accounts.size()) {
                        mEditAccount = FamilyAccounts.keyOf(accounts.get(which - 1));
                    } else {
                        startSignIn();
                        return;
                    }
                    refreshEditor();
                })
                .show();
    }

    /** Opens SmartTube's sign-in screen (code to type at youtube.com/activate on a phone). */
    private void startSignIn() {
        mAccountsBeforeSignIn = FamilyAccounts.keys();
        FamilyAccounts.signInPending = true;
        SignInPresenter.instance(mActivity).start();
    }

    /** Back from the sign-in screen: give the new account to the profile being edited. */
    void onResumed() {
        if (mAccountsBeforeSignIn == null) {
            return;
        }
        java.util.Set<String> before = mAccountsBeforeSignIn;
        mAccountsBeforeSignIn = null;
        FamilyAccounts.signInPending = false;
        String added = null;
        for (String key : FamilyAccounts.keys()) {
            if (!before.contains(key)) {
                added = key;
            }
        }
        if (added != null) {
            mEditAccount = added;
            Toast.makeText(mActivity, "Cuenta añadida. Pulsa Guardar.", Toast.LENGTH_LONG).show();
        }
        // Signing in switches the whole app to the new account: go back to the one in use
        FamilyAccounts.applyFor(mActivity, mProfiles.getCurrent());
        if (mRoot != null && mEditor.getVisibility() == View.VISIBLE) {
            refreshEditor();
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
        if (mEditing != null && mEditKid && !mEditing.isKid && !mProfiles.canRemove(mEditing)) {
            Toast.makeText(mActivity, "Tiene que quedar al menos un perfil de adulto", Toast.LENGTH_LONG).show();
            return;
        }
        if (mEditKid && !mProfiles.hasPin()) {
            // First kids profile: choose the parental PIN on our number pad, then save
            FamilyPinPad.create(mActivity, pin -> {
                mProfiles.setPin(pin);
                saveEditor();
            });
            return;
        }
        FamilyProfile profile = mEditing != null ? mEditing : mProfiles.add(name, mEditKid, mEditAge);
        profile.name = name;
        profile.isKid = mEditKid;
        profile.ageGroup = mEditAge;
        profile.avatar = mEditAvatar;
        profile.accountKey = mEditAccount;
        profile.approvedOnly = mEditApprovedOnly;
        profile.dailyLimitMin = mEditLimitMin;
        profile.hideComments = mEditHideComments;
        profile.approvedChannels.clear();
        profile.approvedChannels.putAll(mEditChannels);
        mProfiles.update(profile);
        showPicker();
    }

    private void deleteEditing() {
        if (mEditing == null || !mProfiles.canRemove(mEditing)) {
            return;
        }
        FamilyProfile profile = mEditing;
        new AlertDialog.Builder(mActivity)
                .setTitle("¿Borrar el perfil de " + profile.name + "?")
                .setMessage("Se borrarán sus ajustes y sus canales aprobados en esta tele.")
                .setPositiveButton("Borrar", (dialog, which) -> {
                    mProfiles.remove(profile);
                    showPicker();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
