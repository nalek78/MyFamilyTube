package es.myfamilytube.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone list: section titles, videos (big cards or compact rows), a strip of channels
 * and plain lines for the advanced settings.
 */
final class MobileFeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Listener {
        void onVideoClicked(Video video);

        void onVideoLongClicked(Video video);

        /** Tap on the picture (preview on the phone lists). */
        void onThumbnailClicked(View card, Video video);

        void onChannelClicked(FamilyChannelItem channel);

        /** The user is getting near the end of the list. */
        void onNearEnd();
    }

    /** A row of channels shown above the videos. */
    static final class ChannelStrip {
        final List<FamilyChannelItem> channels;

        ChannelStrip(List<FamilyChannelItem> channels) {
            this.channels = channels;
        }
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VIDEO = 1;
    private static final int TYPE_SETTING = 2;
    private static final int TYPE_CHANNELS = 3;
    private static final int TYPE_SHORTS = 4;
    private static final int NEAR_END = 6;

    private final Listener mListener;
    private final boolean mCompact;
    private final List<Object> mItems = new ArrayList<>();
    private MobilePreview mPreview;

    /** @param compact small rows (picture left) instead of big cards */
    MobileFeedAdapter(Listener listener, boolean compact) {
        mListener = listener;
        mCompact = compact;
    }

    void setPreview(MobilePreview preview) {
        mPreview = preview;
    }

    void setItems(List<Object> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    boolean isEmpty() {
        return mItems.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = mItems.get(position);
        if (item instanceof MobileFeed.Header) {
            return TYPE_HEADER;
        }
        if (item instanceof SettingsItem) {
            return TYPE_SETTING;
        }
        if (item instanceof ChannelStrip) {
            return TYPE_CHANNELS;
        }
        if (item instanceof MobileFeed.ShortsPair) {
            return TYPE_SHORTS;
        }
        return TYPE_VIDEO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_HEADER:
                return new SimpleHolder(inflater.inflate(R.layout.mobile_header, parent, false));
            case TYPE_SETTING:
                return new SimpleHolder(inflater.inflate(R.layout.mobile_text_item, parent, false));
            case TYPE_CHANNELS:
                return new SimpleHolder(inflater.inflate(R.layout.mobile_channels_strip, parent, false));
            case TYPE_SHORTS:
                return new ShortsHolder(inflater.inflate(R.layout.mobile_shorts_pair, parent, false), parent);
            default:
                View view = inflater.inflate(mCompact ? R.layout.mobile_video_small : R.layout.mobile_video_card, parent, false);
                return new VideoHolder(view, parent, mCompact);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = mItems.get(position);
        if (holder instanceof VideoHolder) {
            Video video = (Video) item;
            ((VideoHolder) holder).bind(video);
            // Big cards can preview themselves (the preview finds them through this tag)
            holder.itemView.setTag(R.id.family_card_thumb_frame, mCompact ? null : video);
            if (mPreview != null) {
                mPreview.onBound(holder.itemView, video);
            }
            holder.itemView.setOnClickListener(v -> mListener.onVideoClicked(video));
            ((VideoHolder) holder).frame.setOnClickListener(v -> mListener.onThumbnailClicked(holder.itemView, video));
            ((VideoHolder) holder).frame.setOnLongClickListener(v -> {
                mListener.onVideoLongClicked(video);
                return true;
            });
            holder.itemView.setOnLongClickListener(v -> {
                mListener.onVideoLongClicked(video);
                return true;
            });
        } else if (holder instanceof ShortsHolder) {
            MobileFeed.ShortsPair pair = (MobileFeed.ShortsPair) item;
            ((ShortsHolder) holder).bind(pair, mListener);
        } else if (item instanceof MobileFeed.Header) {
            ((TextView) holder.itemView).setText(((MobileFeed.Header) item).title);
        } else if (item instanceof SettingsItem) {
            SettingsItem setting = (SettingsItem) item;
            ((TextView) holder.itemView).setText(setting.title);
            holder.itemView.setOnClickListener(v -> {
                if (setting.onClick != null) {
                    setting.onClick.run();
                }
            });
        } else if (item instanceof ChannelStrip) {
            LinearLayout container = holder.itemView.findViewById(R.id.mobile_channels_container);
            container.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
            for (FamilyChannelItem channel : ((ChannelStrip) item).channels) {
                TextView chip = (TextView) inflater.inflate(R.layout.mobile_channel_chip, container, false);
                chip.setText(channel.name);
                chip.setOnClickListener(v -> mListener.onChannelClicked(channel));
                container.addView(chip);
            }
        }

        if (position >= mItems.size() - NEAR_END) {
            holder.itemView.post(mListener::onNearEnd);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof VideoHolder) {
            Glide.with(((VideoHolder) holder).thumb).clear(((VideoHolder) holder).thumb);
        } else if (holder instanceof ShortsHolder) {
            ((ShortsHolder) holder).clear();
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private static final class SimpleHolder extends RecyclerView.ViewHolder {
        SimpleHolder(View view) {
            super(view);
        }
    }

    /** Two vertical (9:16) Shorts cards side by side. */
    static final class ShortsHolder extends RecyclerView.ViewHolder {
        private final View[] mCards = new View[2];
        private final ImageView[] mThumbs = new ImageView[2];
        private final TextView[] mTitles = new TextView[2];
        private final int mThumbW;
        private final int mThumbH;

        ShortsHolder(View view, ViewGroup parent) {
            super(view);
            int[] cards = {R.id.mobile_short_left, R.id.mobile_short_right};
            int width = parent.getWidth() > 0 ? parent.getWidth() : view.getResources().getDisplayMetrics().widthPixels;
            int side = view.getResources().getDimensionPixelSize(R.dimen.mobile_side);
            mThumbW = Math.max(1, (width - 3 * side) / 2);
            mThumbH = mThumbW * 16 / 9;
            for (int i = 0; i < 2; i++) {
                mCards[i] = view.findViewById(cards[i]);
                mThumbs[i] = mCards[i].findViewById(R.id.mobile_card_thumb);
                mTitles[i] = mCards[i].findViewById(R.id.mobile_card_title);
                View frame = mCards[i]; // the include's id replaces the card's own id
                frame.getLayoutParams().height = mThumbH;
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    frame.setClipToOutline(true);
                }
            }
        }

        void bind(MobileFeed.ShortsPair pair, Listener listener) {
            Video[] videos = {pair.left, pair.right};
            for (int i = 0; i < 2; i++) {
                Video video = videos[i];
                if (video == null) {
                    mCards[i].setVisibility(View.INVISIBLE);
                    mCards[i].setOnClickListener(null);
                    continue;
                }
                mCards[i].setVisibility(View.VISIBLE);
                mTitles[i].setText(video.getTitle());
                mCards[i].setOnClickListener(v -> listener.onVideoClicked(video));
                mCards[i].setOnLongClickListener(v -> {
                    listener.onVideoLongClicked(video);
                    return true;
                });
                String url = video.getCardImageUrl();
                if (TextUtils.isEmpty(url)) {
                    Glide.with(mThumbs[i]).clear(mThumbs[i]);
                    mThumbs[i].setImageDrawable(null);
                } else {
                    Glide.with(mThumbs[i])
                            .load(url)
                            .override(Math.min(mThumbW, 540), Math.min(mThumbH, 960))
                            .format(DecodeFormat.PREFER_RGB_565)
                            .centerCrop()
                            .dontAnimate()
                            .into(mThumbs[i]);
                }
            }
        }

        void clear() {
            for (ImageView thumb : mThumbs) {
                Glide.with(thumb).clear(thumb);
            }
        }
    }

    static final class VideoHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView badge;
        final TextView title;
        final TextView subtitle;
        final View frame;
        private final int mThumbW;
        private final int mThumbH;

        VideoHolder(View view, ViewGroup parent, boolean compact) {
            super(view);
            thumb = view.findViewById(R.id.mobile_card_thumb);
            badge = view.findViewById(R.id.mobile_card_badge);
            title = view.findViewById(R.id.mobile_card_title);
            subtitle = view.findViewById(R.id.mobile_card_subtitle);
            frame = view.findViewById(R.id.family_card_thumb_frame);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                frame.setClipToOutline(true); // rounded corners from the frame's shape
            }
            if (compact) {
                mThumbW = frame.getLayoutParams().width;
                mThumbH = frame.getLayoutParams().height;
            } else {
                // Full width (minus margins), 16:9
                int width = parent.getWidth() > 0 ? parent.getWidth() : view.getResources().getDisplayMetrics().widthPixels;
                int side = view.getResources().getDimensionPixelSize(R.dimen.mobile_side);
                mThumbW = Math.max(1, width - 2 * side);
                mThumbH = mThumbW * 9 / 16;
                frame.getLayoutParams().height = mThumbH;
            }
        }

        void bind(Video video) {
            title.setText(video.getTitle());
            CharSequence second = video.getSecondTitle();
            subtitle.setText(!TextUtils.isEmpty(second) ? second : video.getAuthor());
            String badgeText = video.isLive ? "EN DIRECTO" : video.badge;
            if (!TextUtils.isEmpty(badgeText)) {
                badge.setText(badgeText);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            String url = video.getCardImageUrl();
            if (TextUtils.isEmpty(url)) {
                Glide.with(thumb).clear(thumb);
                thumb.setImageDrawable(null);
                return;
            }
            // Picture at the size shown, 2 bytes per pixel: light on memory
            Glide.with(thumb)
                    .load(url)
                    .override(Math.min(mThumbW, 960), Math.min(mThumbH, 540))
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .dontAnimate()
                    .into(thumb);
        }
    }
}
