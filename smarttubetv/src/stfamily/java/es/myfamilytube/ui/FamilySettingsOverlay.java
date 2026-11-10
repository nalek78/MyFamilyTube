package es.myfamilytube.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import es.myfamilytube.profiles.FamilyProfiles;

/**
 * Simple settings: the options people actually change, each on one line.
 * Pressing OK moves to the next value, so there are no pop-up lists.
 * Everything else stays reachable in "Ajustes avanzados".
 */
final class FamilySettingsOverlay {
    interface Host {
        void openProfiles();

        void openDiagnostics();

        void openAdvanced();

        /** Ajustes › Pantalla › Tamaño de las miniaturas changed. */
        void onCardSizeChanged();
    }

    /** A setting with a few values that OK cycles through. */
    private interface Choice {
        String[] labels();

        int current();

        void set(int index);
    }

    private static final String[] YES_NO = {"Sí", "No"};

    private final Activity mActivity;
    private final ViewGroup mHost;
    private final Host mCallbacks;
    private View mRoot;
    private LinearLayout mList;

    FamilySettingsOverlay(Activity activity, ViewGroup host, Host callbacks) {
        mActivity = activity;
        mHost = host;
        mCallbacks = callbacks;
    }

    boolean isShowing() {
        return mRoot != null && mRoot.getVisibility() == View.VISIBLE;
    }

    void show() {
        if (mRoot == null) {
            mRoot = LayoutInflater.from(mActivity).inflate(FamilyLayouts.settings, mHost, false);
            mHost.addView(mRoot);
            mList = mRoot.findViewById(R.id.family_settings_list);
        }
        build();
        mRoot.setVisibility(View.VISIBLE);
        View first = mList.getChildAt(1); // 0 is the first header
        if (first != null) {
            first.post(first::requestFocus);
        }
    }

    void hide() {
        if (mRoot != null) {
            mRoot.setVisibility(View.GONE);
        }
    }

    boolean onBack() {
        if (!isShowing()) {
            return false;
        }
        hide();
        return true;
    }

    private void build() {
        mList.removeAllViews();
        PlayerData player = PlayerData.instance(mActivity);
        SponsorBlockData sponsor = SponsorBlockData.instance(mActivity);
        FamilyProfiles profiles = FamilyProfiles.instance(mActivity);

        header("Reproducción");
        choice("Calidad de vídeo", "Más baja = carga antes y va más fluida en teles justas", new Choice() {
            final FormatItem[] formats = {FormatItem.VIDEO_AUTO, FormatItem.VIDEO_SD_AVC_30, FormatItem.VIDEO_HD_AVC_30,
                    FormatItem.VIDEO_FHD_AVC_30, FormatItem.VIDEO_FHD_AVC_60};

            public String[] labels() {
                return new String[]{"Automática", "360p", "720p", "1080p", "1080p 60"};
            }

            public int current() {
                FormatItem format = player.getFormat(FormatItem.TYPE_VIDEO);
                if (format == null || format.getHeight() <= 0) {
                    return 0;
                }
                if (format.getHeight() <= 360) {
                    return 1;
                }
                if (format.getHeight() <= 720) {
                    return 2;
                }
                return format.getFrameRate() >= 50 ? 4 : 3;
            }

            public void set(int index) {
                player.setFormat(formats[index]);
            }
        });
        choice("Al terminar un vídeo", null, new Choice() {
            final int[] modes = {PlayerConstants.PLAYBACK_MODE_ALL, PlayerConstants.PLAYBACK_MODE_PAUSE,
                    PlayerConstants.PLAYBACK_MODE_CLOSE, PlayerConstants.PLAYBACK_MODE_ONE};

            public String[] labels() {
                return new String[]{"Poner el siguiente", "Pausar", "Cerrar el vídeo", "Repetirlo"};
            }

            public int current() {
                return indexOf(modes, player.getPlaybackMode());
            }

            public void set(int index) {
                player.setPlaybackMode(modes[index]);
            }
        });
        choice("Velocidad", null, new Choice() {
            final float[] speeds = {0.75f, 1f, 1.25f, 1.5f, 2f};

            public String[] labels() {
                return new String[]{"0,75x", "Normal", "1,25x", "1,5x", "2x"};
            }

            public int current() {
                float speed = player.getSpeed();
                for (int i = 0; i < speeds.length; i++) {
                    if (Math.abs(speeds[i] - speed) < 0.01f) {
                        return i;
                    }
                }
                return 1;
            }

            public void set(int index) {
                player.setSpeed(speeds[index]);
            }
        });
        choice("Saltar patrocinios", "Se salta los anuncios que dice el youtuber (SponsorBlock)", new Choice() {
            public String[] labels() {
                return YES_NO;
            }

            public int current() {
                return sponsor.isSponsorBlockEnabled() ? 0 : 1;
            }

            public void set(int index) {
                sponsor.setSponsorBlockEnabled(index == 0);
            }
        });
        choice("Vista previa en la miniatura", FamilyLayouts.mobile
                ? "Al tocar la miniatura, el vídeo se reproduce ahí con sonido; tócala otra vez para abrirlo"
                : "Al quedarte en un vídeo, se reproduce su primer minuto con sonido", new Choice() {
            public String[] labels() {
                return YES_NO;
            }

            public int current() {
                return FamilyPreview.isEnabled(mActivity) ? 0 : 1;
            }

            public void set(int index) {
                FamilyPreview.setEnabled(mActivity, index == 0);
            }
        });
        if (FamilyLayouts.mobile) // phone only
        choice("Al salir de un vídeo", "Ventana flotante: sigue en pequeño sobre otras apps, y con la pantalla apagada sigue el sonido", new Choice() {
            public String[] labels() {
                return FamilyBackground.LABELS;
            }

            public int current() {
                return FamilyBackground.get(mActivity);
            }

            public void set(int index) {
                FamilyBackground.set(mActivity, index);
            }
        });
        choice("Memoria para el vídeo","«Baja» si la tele va justa o la app se cierra sola", new Choice() {
            final int[] buffers = {PlayerConstants.BUFFER_LOW, PlayerConstants.BUFFER_MEDIUM, PlayerConstants.BUFFER_HIGH};

            public String[] labels() {
                return new String[]{"Baja", "Normal", "Alta"};
            }

            public int current() {
                return indexOf(buffers, player.getVideoBufferType());
            }

            public void set(int index) {
                player.setVideoBufferType(buffers[index]);
            }
        });

        if (!FamilyLayouts.mobile) {
        header("Pantalla");
        choice("Tamaño de las miniaturas", "Normal, un 15 % más grande o un 30 % más grande", new Choice() {
            public String[] labels() {
                return FamilyCardSize.LABELS;
            }

            public int current() {
                return FamilyCardSize.index(mActivity);
            }

            public void set(int index) {
                FamilyCardSize.setPercent(mActivity, FamilyCardSize.PERCENTS[index]);
                mCallbacks.onCardSizeChanged();
            }
        });
        }

        if (FamilyLayouts.mobile) { // phone only
            header("Enlaces");
            link("Abrir los enlaces de YouTube con esta app", "Los vídeos que te envíen se abrirán aquí, sin Shorts y con el control parental", () -> {
                hide();
                FamilyLinks.showSetup(mActivity);
            });
        }

        header("Familia");
        link("Perfiles y control parental", "Crear o cambiar perfiles, edad, canales aprobados, tiempo", () -> {
            hide();
            mCallbacks.openProfiles();
        });
        choice("Pedir PIN para los perfiles de adulto", profiles.hasPin()
                ? "Así un niño no puede entrar en un perfil de adulto"
                : "Se activa al crear el primer perfil infantil", new Choice() {
            public String[] labels() {
                return YES_NO;
            }

            public int current() {
                return profiles.isAdultPinEnabled() ? 0 : 1;
            }

            public void set(int index) {
                profiles.setAdultPinEnabled(index == 0);
            }
        });

        link(profiles.hasPin() ? "Cambiar PIN" : "Crear PIN",
                profiles.hasPin() ? "Primero el PIN actual, luego el nuevo dos veces"
                        : "Se pedirá para entrar en perfiles de adulto y cambiar el control parental",
                () -> FamilyPinPad.verify(mActivity, () -> FamilyPinPad.create(mActivity, pin -> {
                    profiles.setPin(pin);
                    android.widget.Toast.makeText(mActivity, "PIN cambiado", android.widget.Toast.LENGTH_SHORT).show();
                    build(); // "Crear PIN" becomes "Cambiar PIN"
                })));

        header("Ayuda");
        link("Diagnóstico", "Registro de errores para enviar si la app falla", mCallbacks::openDiagnostics);
        link("Ajustes avanzados", "Todas las opciones de SmartTube", () -> {
            hide();
            mCallbacks.openAdvanced();
        });
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private void header(String text) {
        TextView header = new TextView(mActivity);
        header.setText(text.toUpperCase());
        header.setTextColor(0xFFFF3D33);
        header.setTextSize(13);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            header.setLetterSpacing(0.1f);
        }
        float density = mActivity.getResources().getDisplayMetrics().density;
        header.setPadding((int) (4 * density), (int) (22 * density), 0, (int) (2 * density));
        mList.addView(header);
    }

    private View row(String title, String hint) {
        View row = LayoutInflater.from(mActivity).inflate(FamilyLayouts.settingRow, mList, false);
        ((TextView) row.findViewById(R.id.family_setting_title)).setText(title);
        TextView hintView = row.findViewById(R.id.family_setting_hint);
        if (hint == null) {
            hintView.setVisibility(View.GONE);
        } else {
            hintView.setText(hint);
        }
        mList.addView(row);
        return row;
    }

    private void choice(String title, String hint, Choice choice) {
        View row = row(title, hint);
        TextView value = row.findViewById(R.id.family_setting_value);
        String[] labels = choice.labels();
        value.setText(labels[Math.max(0, Math.min(labels.length - 1, choice.current()))] + "  ›");
        row.setOnClickListener(v -> {
            int next = (choice.current() + 1) % labels.length;
            choice.set(next);
            value.setText(labels[choice.current()] + "  ›");
        });
    }

    private void link(String title, String hint, Runnable action) {
        View row = row(title, hint);
        ((TextView) row.findViewById(R.id.family_setting_value)).setText("›");
        row.setOnClickListener(v -> action.run());
    }
}
