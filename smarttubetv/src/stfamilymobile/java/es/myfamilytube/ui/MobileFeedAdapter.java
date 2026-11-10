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
    private static final int NEAR_END = 6;

    private final Listener mListener;
    private final boolean mCompact;
    private final List<Object> mItems = new ArrayList<>();

    /** @param compact small rows (picture left) instead of big cards */
    MobileFeedAdapter(Listener listener, boolean compact) {
        mListener = listener;
        mCompact = compact;
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
            holder.itemView.setOnClickListener(v -> mListener.onVideoClicked(video));
            holder.itemView.setOnLongClickListener(v -> {
                mListener.onVideoLongClicked(video);
                return true;
            });
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

    static final class VideoHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView badge;
        final TextView title;
        final TextView subtitle;
        private final int mThumbW;
        private final int mThumbH;

        VideoHolder(View view, ViewGroup parent, boolean compact) {
            super(view);
            thumb = view.findViewById(R.id.mobile_card_thumb);
            badge = view.findViewById(R.id.mobile_card_badge);
            title = view.findViewById(R.id.mobile_card_title);
            subtitle = view.findViewById(R.id.mobile_card_subtitle);
            View frame = view.findViewById(R.id.family_card_thumb_frame);
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
