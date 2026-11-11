package es.myfamilytube.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;

import java.util.ArrayList;
import java.util.Locale;

/** Phone search: text or voice, results as big cards. Kids' results go through their filter. */
public class MobileSearchActivity extends MobileListActivity implements SearchView {
    private static final int REQUEST_VOICE = 4801;
    private SearchPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTitle.setVisibility(View.GONE);
        mSearchField.setVisibility(View.VISIBLE);
        mMic.setVisibility(View.VISIBLE);
        mFeed.setShowTitles(false);

        mSearchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(mSearchField.getText().toString());
                return true;
            }
            return false;
        });
        mMic.setOnClickListener(v -> startVoiceRecognition());

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPresenter.setView(this);
        mPresenter.onViewResumed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPresenter.onViewPaused();
    }

    @Override
    protected void onDestroy() {
        if (mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    private void search(String text) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            return;
        }
        hideKeyboard();
        clearList();
        showProgress(true);
        mPresenter.onSearch(text.trim());
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchField.getWindowToken(), 0);
        }
        mSearchField.clearFocus();
    }

    // ---------------------------------------------------------------- list

    @Override
    public void onVideoClicked(Video video) {
        beforeOpen(video);
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        showVideoMenu(video, () -> mPresenter.onVideoItemLongClicked(video));
    }

    @Override
    protected void onScrollEnd(Video lastVideo) {
        mPresenter.onScrollEnd(lastVideo);
    }

    // ---------------------------------------------------------------- SearchView

    @Override
    public void updateSearch(VideoGroup group) {
        showProgress(false);
        applyGroup(group);
    }

    @Override
    public void clearSearch() {
        clearList();
    }

    @Override
    public void clearSearchTags() {
        // No suggestions list on the phone
    }

    @Override
    public void removeSearchTag(Tag tag) {
        // No suggestions list on the phone
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        // No suggestions list on the phone
    }

    @Override
    public void showProgressBar(boolean show) {
        showProgress(show);
    }

    @Override
    public void startSearch(String searchText) {
        if (!TextUtils.isEmpty(searchText)) {
            mSearchField.setText(searchText);
            mSearchField.setSelection(searchText.length());
            search(searchText);
        } else {
            mSearchField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                mSearchField.post(() -> imm.showSoftInput(mSearchField, InputMethodManager.SHOW_IMPLICIT));
            }
        }
    }

    @Override
    public String getSearchText() {
        return mSearchField.getText().toString();
    }

    @Override
    public void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres ver?");
        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este móvil no tiene búsqueda por voz", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                startSearch(results.get(0));
            }
        }
    }
}
