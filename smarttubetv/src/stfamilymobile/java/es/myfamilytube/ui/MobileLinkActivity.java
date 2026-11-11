package es.myfamilytube.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens YouTube links (from the browser, WhatsApp, "Compartir"…) in MyFamilyTube.
 * Shorts links only open for profiles that show Shorts. The link waits on the home screen until the profile is chosen
 * (with its PIN if it's a grown-up one), so the parental control can't be skipped this way.
 */
public class MobileLinkActivity extends Activity {
    private static final Pattern URL = Pattern.compile("(https?://|vnd\\.youtube:)\\S+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri uri = linkOf(intent);

        if (uri == null) {
            Toast.makeText(this, "No es un enlace de YouTube", Toast.LENGTH_SHORT).show();
        } else {
            // A clean copy with just the link: the engine reads the video, list, channel or search from it
            MobileHomeActivity.setPendingLink(new Intent(Intent.ACTION_VIEW, uri));
            Intent open;
            if (MobileHomeActivity.isAlive()) {
                open = new Intent(this, MobileHomeActivity.class);
            } else {
                // App not running: start it the normal way (it prepares the engine), the home screen takes the link
                open = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (open == null) {
                    open = new Intent(this, MobileHomeActivity.class);
                }
            }
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        }
        finish();
    }

    private static Uri linkOf(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (intent.getData() != null) {
            return intent.getData();
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        Matcher matcher = URL.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        Uri uri = Uri.parse(matcher.group());
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        boolean youtube = "vnd.youtube".equals(uri.getScheme()) || host.endsWith("youtube.com") || host.equals("youtu.be");
        return youtube ? uri : null;
    }

    static boolean isShorts(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments != null && !segments.isEmpty() && "shorts".equalsIgnoreCase(segments.get(0));
    }
}
