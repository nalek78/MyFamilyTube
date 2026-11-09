package es.myfamilytube.ui;

import android.os.Build;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** Cards of one row: video thumbnails, or plain buttons for the settings section. */
final class FamilyCardAdapter extends RecyclerView.Adapter<FamilyCardAdapter.CardHolder> {
    interface Listener {
        void onCardFocused(FamilyRow row, Object item, int position);
        void onCardClicked(Object item);
        void onCardLongClicked(Object item);

        /** Focus moved onto (true) or off (false) a card: used for the thumbnail preview. */
        void onCardFocusChanged(View card, Object item, boolean hasFocus);

        /** The card is about to be reused for another item. */
        void onCardRecycled(View card);
    }

    private static final float FOCUS_SCALE = 1.06f;
    private final FamilyRow mRow;
    private final Listener mListener;

    private int mViewType = 100;

    FamilyCardAdapter(FamilyRow row, Listener listener) {
        mRow = row;
        mListener = listener;
    }

    /** Called before (re)attaching to a row so new cards use the current size. */
    void setCardSizePercent(int percent) {
        mViewType = percent;
    }

    @NonNull
    @Override
    public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.family_card, parent, false);
        // Size chosen in Ajustes › Pantalla › Tamaño de las miniaturas
        view.getLayoutParams().width = FamilyCardSize.cardWidth(parent.getContext());
        view.findViewById(R.id.family_card_thumb_frame).getLayoutParams().height = FamilyCardSize.thumbHeight(parent.getContext());
        return new CardHolder(view);
    }

    /** The size is the view type, so cards of an old size are never reused after changing it. */
    @Override
    public int getItemViewType(int position) {
        return mViewType;
    }

    @Override
    public void onBindViewHolder(@NonNull CardHolder holder, int position) {
        Object item = mRow.items.get(position);
        holder.bind(item);

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? FOCUS_SCALE : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < mRow.items.size()) {
                mListener.onCardFocusChanged(v, mRow.items.get(pos), hasFocus);
            }
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
        mListener.onCardRecycled(holder.itemView);
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
        private final int mThumbW;
        private final int mThumbH;

        CardHolder(View view) {
            super(view);
            thumb = view.findViewById(R.id.family_card_thumb);
            center = view.findViewById(R.id.family_card_center);
            badge = view.findViewById(R.id.family_card_badge);
            title = view.findViewById(R.id.family_card_title);
            subtitle = view.findViewById(R.id.family_card_subtitle);
            mThumbW = FamilyCardSize.cardWidth(view.getContext());
            mThumbH = FamilyCardSize.thumbHeight(view.getContext());
            View frame = view.findViewById(R.id.family_card_thumb_frame);
            if (Build.VERSION.SDK_INT >= 21) {
                frame.setClipToOutline(true); // rounded corners from the frame's background shape
            }
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
                    // Decode at the card's size with 2 bytes per pixel: far less memory on small TVs.
                    // Rounded corners come from clipping the frame, not from an extra bitmap copy.
                    Glide.with(thumb)
                            .load(url)
                            .override(mThumbW, mThumbH)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .centerCrop()
                            .dontAnimate()
                            .into(thumb);
                } else {
                    Glide.with(thumb).clear(thumb);
                    thumb.setImageDrawable(null);
                }
            } else if (item instanceof SettingsItem || item instanceof FamilyChannelItem) {
                String text = item instanceof SettingsItem ? ((SettingsItem) item).title : ((FamilyChannelItem) item).name;
                Glide.with(thumb).clear(thumb);
                thumb.setImageDrawable(null);
                badge.setVisibility(View.GONE);
                center.setVisibility(View.VISIBLE);
                center.setText(text);
                title.setVisibility(View.GONE);
                subtitle.setVisibility(View.GONE);
            }
        }
    }
}
