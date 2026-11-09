package es.myfamilytube.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/** Vertical list of rows. Each row owns its card adapter so focus and scroll survive updates. */
final class FamilyRowsAdapter extends RecyclerView.Adapter<FamilyRowsAdapter.RowHolder> {
    private final List<FamilyRow> mRows;
    private final RecyclerView.RecycledViewPool mCardPool = new RecyclerView.RecycledViewPool();

    FamilyRowsAdapter(List<FamilyRow> rows, int cardSizePercent) {
        mRows = rows;
        mCardSizePercent = cardSizePercent;
        mCardPool.setMaxRecycledViews(cardSizePercent, 40);
    }

    /** Rows are built for one card size; a new size makes new rows and cards. */
    @Override
    public int getItemViewType(int position) {
        return mCardSizePercent;
    }

    private int mCardSizePercent;

    /** Applies a new thumbnail size: cards of the old size are dropped, not reused. */
    void setCardSizePercent(int percent) {
        if (percent == mCardSizePercent) {
            return;
        }
        mCardPool.clear();
        mCardSizePercent = percent;
        mCardPool.setMaxRecycledViews(percent, 40);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.family_row, parent, false);
        RowHolder holder = new RowHolder(view);
        holder.grid.getLayoutParams().height = FamilyCardSize.rowHeight(parent.getContext());
        // Keep the focused card away from the left edge: zoomed on focus it must stay on screen
        int side = view.getResources().getDimensionPixelSize(R.dimen.family_side);
        holder.grid.setRecycledViewPool(mCardPool);
        holder.grid.setHasFixedSize(true); // rows never change height: skip relayouts
        holder.grid.setHorizontalSpacing(view.getResources().getDimensionPixelSize(R.dimen.family_card_gap));
        holder.grid.setWindowAlignment(HorizontalGridView.WINDOW_ALIGN_LOW_EDGE);
        holder.grid.setWindowAlignmentOffset(side);
        holder.grid.setWindowAlignmentOffsetPercent(HorizontalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        holder.grid.setItemAlignmentOffsetPercent(HorizontalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        FamilyRow row = mRows.get(position);
        if (TextUtils.isEmpty(row.title)) {
            holder.title.setVisibility(View.GONE);
        } else {
            holder.title.setVisibility(View.VISIBLE);
            holder.title.setText(row.title);
        }
        if (holder.grid.getAdapter() != row.adapter) {
            row.adapter.setCardSizePercent(mCardSizePercent);
            holder.grid.setAdapter(row.adapter);
        }
        if (row.selected > 0 && row.selected < row.items.size()) {
            holder.grid.setSelectedPosition(row.selected);
        }
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final HorizontalGridView grid;

        RowHolder(View view) {
            super(view);
            title = view.findViewById(R.id.family_row_title);
            grid = view.findViewById(R.id.family_row_grid);
        }
    }
}
