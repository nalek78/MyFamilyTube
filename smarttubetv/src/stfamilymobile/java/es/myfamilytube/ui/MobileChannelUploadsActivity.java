package es.myfamilytube.ui;

import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;

/** All the videos of a channel (or a playlist) on the phone. */
public class MobileChannelUploadsActivity extends MobileListActivity implements ChannelUploadsView {
    private ChannelUploadsPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTitle.setText("Vídeos");
        mFeed.setShowTitles(false);
        mPresenter = ChannelUploadsPresenter.instance(this);
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

    // ---------------------------------------------------------------- ChannelUploadsView

    @Override
    public void update(VideoGroup videoGroup) {
        showProgress(false);
        if (videoGroup != null && videoGroup.getTitle() != null) {
            mTitle.setText(videoGroup.getTitle());
        }
        applyGroup(videoGroup);
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
