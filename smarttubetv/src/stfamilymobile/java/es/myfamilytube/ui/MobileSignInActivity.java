package es.myfamilytube.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.SignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Adds a Google account the phone way: a button opens Google's page in the browser with the
 * code already filled in, you pick your account and allow it, and the app notices by itself.
 * (Same YouTube sign-in as the TV app, without the QR or typing a code.)
 */
public class MobileSignInActivity extends MotherActivity implements SignInView {
    private SignInPresenter mPresenter;
    private TextView mButton;
    private TextView mStatus;
    private TextView mCode;
    private View mProgress;
    private String mUserCode;
    private String mUrl;
    private boolean mOpened;

    @Override
    protected void initTheme() {
        setTheme(R.style.MobileTheme);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mobile_sign_in);
        mButton = findViewById(R.id.mobile_sign_in_button);
        mStatus = findViewById(R.id.mobile_sign_in_status);
        mCode = findViewById(R.id.mobile_sign_in_code);
        mProgress = findViewById(R.id.mobile_sign_in_progress);
        findViewById(R.id.mobile_sign_in_back).setOnClickListener(v -> finish());
        mButton.setEnabled(false);
        mButton.setAlpha(0.5f);
        mButton.setOnClickListener(v -> openGoogle());
        mCode.setOnClickListener(v -> copyCode(true));

        mPresenter = SignInPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private final MobileHaptics mHaptics = new MobileHaptics();

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        mHaptics.onTouch(this, event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mOpened) {
            mStatus.setText("Comprobando… Si ya has pulsado «Permitir» en Google, la cuenta se añadirá en unos segundos.");
        }
    }

    @Override
    protected void onDestroy() {
        if (mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    private void openGoogle() {
        if (TextUtils.isEmpty(mUrl)) {
            return;
        }
        copyCode(false);
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(mUrl)).addCategory(Intent.CATEGORY_BROWSABLE);
        // In the browser (where the phone's Google account usually is), not in the YouTube app
        Intent inBrowser = new Intent(view);
        inBrowser.setSelector(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER));
        try {
            startActivity(inBrowser);
            mOpened = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(view);
                mOpened = true;
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No hay ningún navegador instalado", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void copyCode(boolean tell) {
        if (TextUtils.isEmpty(mUserCode)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Código", mUserCode));
            if (tell) {
                Toast.makeText(this, "Código copiado", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------------------------------------------------------- SignInView

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, null);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            if (TextUtils.isEmpty(signInUrl)) {
                // The engine sends the error text instead of a code
                mProgress.setVisibility(View.GONE);
                mStatus.setText("No se pudo preparar el inicio de sesión: " + userCode + "\nVuelve atrás y prueba otra vez.");
                return;
            }
            mUserCode = userCode;
            // The QR's address already carries the code: Google's page opens with it filled in
            mUrl = !TextUtils.isEmpty(fullSignInUrl) ? fullSignInUrl : signInUrl;
            mCode.setText(userCode);
            mButton.setEnabled(true);
            mButton.setAlpha(1f);
            mStatus.setText(mOpened ? "Esperando a que confirmes en Google…" : "Listo. Pulsa el botón para continuar.");
        });
    }

    @Override
    public void close() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                Toast.makeText(this, "Cuenta de Google añadida", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
}
