package es.myfamilytube.ui;

import java.util.ArrayList;
import java.util.List;

/** One horizontal row of cards on the home screen. Items are Video or SettingsItem. */
final class FamilyRow {
    final int id;
    final String title;
    final List<Object> items = new ArrayList<>();
    final FamilyCardAdapter adapter;
    int selected;

    FamilyRow(int id, String title, FamilyCardAdapter.Listener listener) {
        this.id = id;
        this.title = title;
        this.adapter = new FamilyCardAdapter(this, listener);
    }
}
