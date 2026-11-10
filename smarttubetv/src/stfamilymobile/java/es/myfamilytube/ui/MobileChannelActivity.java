package es.myfamilytube.ui;

import android.os.Bundle;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;

/** A channel on the phone: its shelves one under the other (no Shorts). */
public class MobileChannelActivity extends MobileListActivity implements ChannelView {
    private ChannelPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTitle.setText("Canal");
        mPresenter = ChannelPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
        updateTitle();
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

    private void updateTitle() {
        Video channel = mPresenter.getChannel();
        String name = channel != null ? channel.getAuthor() : null;
        if (TextUtils.isEmpty(name) && channel != null) {
            name = channel.getTitle();
        }
        if (TextUtils.isEmpty(name)) {
            Video first = mFeed.lastVideo();
            name = first != null ? first.getAuthor() : null;
        }
        if (!TextUtils.isEmpty(name)) {
            mTitle.setText(name);
        }
    }

    @Override
    public void onVideoClicked(Video video) {
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

    // ---------------------------------------------------------------- ChannelView

    @Override
    public void update(VideoGroup videoGroup) {
        showProgress(false);
        applyGroup(videoGroup);
        updateTitle();
    }

    @Override
    public void setPosition(int index) {
        // The phone list always starts at the top
    }

    @Override
    public void showProgressBar(boolean show) {
        showProgress(show);
    }

    @Override
    public void clear() {
        clearList();
    }
}
