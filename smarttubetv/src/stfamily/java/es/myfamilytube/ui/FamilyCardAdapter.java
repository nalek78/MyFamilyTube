package es.myfamilytube.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** Cards of one row: video thumbnails, or plain buttons for the settings section. */
final class FamilyCardAdapter extends RecyclerView.Adapter<FamilyCardAdapter.CardHolder> {
    interface Listener {
        void onCardFocused(FamilyRow row, Object item, int position);
        void onCardClicked(Object item);
        void onCardLongClicked(Object item);
    }

    private static final float FOCUS_SCALE = 1.06f;
    private final FamilyRow mRow;
    private final Listener mListener;

    FamilyCardAdapter(FamilyRow row, Listener listener) {
        mRow = row;
        mListener = listener;
    }

    @NonNull
    @Override
    public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.family_card, parent, false);
        return new CardHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CardHolder holder, int position) {
        Object item = mRow.items.get(position);
        holder.bind(item);

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? FOCUS_SCALE : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            int pos = holder.getAdapterPosition();
            if (hasFocus && pos != RecyclerView.NO_POSITION && pos < mRow.items.size()) {
                mRow.selected = pos;
                mListener.onCardFocused(mRow, mRow.items.get(pos), pos);
            }
        });
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < mRow.items.size()) {
                mListener.onCardClicked(mRow.items.get(pos));
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < mRow.items.size()) {
                mListener.onCardLongClicked(mRow.items.get(pos));
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewRecycled(@NonNull CardHolder holder) {
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        Glide.with(holder.thumb).clear(holder.thumb);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return mRow.items.size();
    }

    static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView center;
        final TextView badge;
        final TextView title;
        final TextView subtitle;
        private final int mRadiusPx;

        CardHolder(View view) {
            super(view);
            thumb = view.findViewById(R.id.family_card_thumb);
            center = view.findViewById(R.id.family_card_center);
            badge = view.findViewById(R.id.family_card_badge);
            title = view.findViewById(R.id.family_card_title);
            subtitle = view.findViewById(R.id.family_card_subtitle);
            mRadiusPx = view.getResources().getDimensionPixelSize(R.dimen.family_radius);
        }

        void bind(Object item) {
            if (item instanceof Video) {
                Video video = (Video) item;
                center.setVisibility(View.GONE);
                thumb.setVisibility(View.VISIBLE);
                title.setVisibility(View.VISIBLE);
                title.setText(video.getTitle());
                String author = video.getAuthor();
                subtitle.setText(!TextUtils.isEmpty(author) ? author : video.getSecondTitle());
                subtitle.setVisibility(View.VISIBLE);

                String badgeText = video.isLive ? "EN DIRECTO" : video.badge;
                if (!TextUtils.isEmpty(badgeText)) {
                    badge.setText(badgeText);
                    badge.setVisibility(View.VISIBLE);
                } else {
                    badge.setVisibility(View.GONE);
                }

                String url = video.getCardImageUrl();
                if (!TextUtils.isEmpty(url)) {
                    Glide.with(thumb)
                            .load(url)
                            .transform(new CenterCrop(), new RoundedCorners(mRadiusPx))
                            .into(thumb);
                } else {
                    Glide.with(thumb).clear(thumb);
                    thumb.setImageDrawable(null);
                }
            } else if (item instanceof SettingsItem) {
                SettingsItem setting = (SettingsItem) item;
                Glide.with(thumb).clear(thumb);
                thumb.setImageDrawable(null);
                badge.setVisibility(View.GONE);
                center.setVisibility(View.VISIBLE);
                center.setText(setting.title);
                title.setVisibility(View.GONE);
                subtitle.setVisibility(View.GONE);
            }
        }
    }
}
