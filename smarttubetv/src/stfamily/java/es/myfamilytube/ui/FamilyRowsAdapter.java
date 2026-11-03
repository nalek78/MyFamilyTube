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

    FamilyRowsAdapter(List<FamilyRow> rows) {
        mRows = rows;
        mCardPool.setMaxRecycledViews(0, 40);
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.family_row, parent, false);
        RowHolder holder = new RowHolder(view);
        holder.grid.setRecycledViewPool(mCardPool);
        holder.grid.setHasFixedSize(true); // rows never change height: skip relayouts
        holder.grid.setHorizontalSpacing(view.getResources().getDimensionPixelSize(R.dimen.family_card_gap));
        holder.grid.setWindowAlignment(HorizontalGridView.WINDOW_ALIGN_LOW_EDGE);
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
