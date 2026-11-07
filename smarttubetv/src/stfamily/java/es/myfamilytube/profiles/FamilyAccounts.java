package es.myfamilytube.profiles;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Links each family profile to one of the Google accounts signed in on this TV (or none).
 * Choosing the profile switches the engine to that account, so Suscripciones, Biblioteca
 * and recommendations are that person's.
 */
public final class FamilyAccounts {
    private FamilyAccounts() {
    }

    private static SignInService service() {
        return YouTubeServiceManager.instance().getSignInService();
    }

    /** Stable way to recognise an account: its e-mail, else its name. */
    public static String keyOf(Account account) {
        if (account == null) {
            return "";
        }
        if (!TextUtils.isEmpty(account.getEmail())) {
            return account.getEmail();
        }
        return account.getName() != null ? account.getName() : "";
    }

    public static String labelOf(Account account) {
        if (account == null) {
            return "Sin cuenta";
        }
        String name = account.getName();
        String email = account.getEmail();
        if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(email) && !name.equals(email)) {
            return name + " (" + email + ")";
        }
        return !TextUtils.isEmpty(email) ? email : (name != null ? name : "Cuenta");
    }

    public static List<Account> list() {
        List<Account> result = new ArrayList<>();
        try {
            List<Account> accounts = service().getAccounts();
            if (accounts != null) {
                for (Account account : accounts) {
                    if (account != null && !account.isEmpty()) {
                        result.add(account);
                    }
                }
            }
        } catch (Throwable e) {
            // no accounts available
        }
        return result;
    }

    public static Set<String> keys() {
        Set<String> keys = new HashSet<>();
        for (Account account : list()) {
            keys.add(keyOf(account));
        }
        return keys;
    }

    public static Account find(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        for (Account account : list()) {
            if (key.equals(keyOf(account))) {
                return account;
            }
        }
        return null;
    }

    /** Label for a stored key, also when that account was removed from the TV. */
    public static String labelOfKey(String key) {
        if (TextUtils.isEmpty(key)) {
            return "Sin cuenta";
        }
        Account account = find(key);
        return account != null ? labelOf(account) : key + " (ya no está en la tele)";
    }

    public static String currentKey() {
        try {
            return keyOf(service().getSelectedAccount());
        } catch (Throwable e) {
            return "";
        }
    }

    /** The account the profile should use: its own, or none. */
    public static String keyFor(FamilyProfile profile) {
        if (TextUtils.isEmpty(profile.accountKey) || find(profile.accountKey) == null) {
            return "";
        }
        return profile.accountKey;
    }

    /**
     * Switches the engine to the profile's account.
     * @return true when the account actually changed (content must be reloaded)
     */
    public static boolean applyFor(Context context, FamilyProfile profile) {
        String wanted = keyFor(profile);
        if (wanted.equals(currentKey())) {
            return false;
        }
        AccountSelectionPresenter.instance(context).selectAccount(find(wanted)); // null = no account
        return true;
    }
}
