package es.myfamilytube.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Base of the phone screens that show a list of videos coming from the engine
 * (search results, a channel, a channel's uploads).
 */
abstract class MobileListActivity extends MotherActivity implements MobileFeedAdapter.Listener {
    protected final MobileFeed mFeed = new MobileFeed();
    protected MobileFeedAdapter mAdapter;
    protected RecyclerView mList;
    protected TextView mTitle;
    protected EditText mSearchField;
    protected ImageView mMic;
    private ProgressBar mProgress;
    private TextView mMessage;
    private Video mLastScrollEnd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mobile_list);
        mTitle = findViewById(R.id.mobile_title);
        mSearchField = findViewById(R.id.mobile_search_field);
        mMic = findViewById(R.id.mobile_mic);
        mProgress = findViewById(R.id.mobile_progress);
        mMessage = findViewById(R.id.mobile_message);
        mList = findViewById(R.id.mobile_list);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MobileFeedAdapter(this, false);
        mList.setAdapter(mAdapter);
        findViewById(R.id.mobile_back).setOnClickListener(v -> onBackPressed());
    }

    @Override
    protected void initTheme() {
        setTheme(R.style.MobileTheme);
    }

    /** Called by the engine with new or changed videos. */
    protected void applyGroup(VideoGroup group) {
        mFeed.apply(group);
        refresh();
    }

    protected void clearList() {
        mFeed.clear();
        mLastScrollEnd = null;
        refresh();
    }

    protected void refresh() {
        mAdapter.setItems(mFeed.flatten());
        mMessage.setVisibility(View.GONE);
    }

    protected void showProgress(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    protected void showMessage(String text) {
        mMessage.setText(text);
        mMessage.setVisibility(View.VISIBLE);
    }

    /** The engine paginates: ask for more once per last video. */
    protected abstract void onScrollEnd(Video lastVideo);

    @Override
    public void onNearEnd() {
        Video last = mFeed.lastVideo();
        if (last != null && last != mLastScrollEnd) {
            mLastScrollEnd = last;
            onScrollEnd(last);
        }
    }

    @Override
    public void onChannelClicked(FamilyChannelItem channel) {
        ChannelPresenter.instance(this).openChannel(channel.channelId);
    }

    /** Long press: for grown-ups, approve the channel for kids or open the engine's menu. */
    protected void showVideoMenu(Video video, Runnable engineMenu) {
        MobileVideoMenu.show(this, video, engineMenu);
    }
}
