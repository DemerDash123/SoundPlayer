package com.demerdash.soundplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.PlayingBarsView;
import com.demerdash.soundplayer.ui.ThemeUtil;
import com.demerdash.soundplayer.util.Format;

import java.util.ArrayList;
import java.util.List;

/** The play queue: tap to jump, drag the handle to reorder, ✕ to remove. */
public final class QueueActivity extends BaseActivity {

    private ListView list;
    private TextView subtitle;
    private final QueueAdapter adapter = new QueueAdapter();
    private List<Song> items = new ArrayList<>();
    private int current = -1;
    private boolean scrolled;
    private int dragFrom = -1;
    private int dragAt = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_queue);
        list = findViewById(R.id.queue_list);
        subtitle = findViewById(R.id.queue_subtitle);
        ThemeUtil.padInsets(findViewById(R.id.queue_top), true, false, false);
        ThemeUtil.padInsets(list, false, true, false);
        list.setAdapter(adapter);
        findViewById(R.id.queue_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.queue_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
    }

    @Override
    public void onQueueChanged() {
        if (service == null) return;
        if (dragFrom >= 0) {
            items = service.queueItems();
            current = service.queueIndex();
            adapter.notifyDataSetChanged();
            return;
        }
        items = service.queueItems();
        current = service.queueIndex();
        long total = 0;
        for (Song s : items) total += s.duration;
        subtitle.setText(getResources().getQuantityString(R.plurals.songs_count, items.size(), items.size())
                + " • " + Format.duration(total));
        adapter.notifyDataSetChanged();
        if (!scrolled && current >= 0) {
            scrolled = true;
            list.setSelectionFromTop(Math.max(0, current), dp(120));
        }
    }

    @Override
    public void onPlaybackChanged() {
        if (service == null) return;
        int idx = service.queueIndex();
        if (idx != current) {
            onQueueChanged();
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add(Menu.NONE, 1, 1, R.string.menu_save_queue);
        p.getMenu().add(Menu.NONE, 2, 2, R.string.menu_clear_queue);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (service == null) return true;
                if (item.getItemId() == 1) {
                    List<Long> ids = new ArrayList<>();
                    for (Song s : service.queueItems()) if (!s.isExternal()) ids.add(s.id);
                    PlaylistStore.get(QueueActivity.this).create(getString(R.string.queue_title) + " " +
                            android.text.format.DateFormat.getDateFormat(QueueActivity.this).format(new java.util.Date()), ids);
                    toast(R.string.queue_saved);
                } else {
                    service.clearQueue();
                    finish();
                }
                return true;
            }
        });
        p.show();
    }

    private void handleDrag(MotionEvent e) {
        int[] loc = new int[2];
        list.getLocationOnScreen(loc);
        int y = (int) (e.getRawY() - loc[1]);
        int x = list.getWidth() / 2;
        int pos = list.pointToPosition(x, y);
        if (pos != ListView.INVALID_POSITION && pos != dragAt && service != null) {
            service.moveQueueItem(dragAt, pos);
            dragAt = pos;
            adapter.notifyDataSetChanged();
        }
        int edge = dp(64);
        if (y < edge) list.smoothScrollBy(-dp(24), 60);
        else if (y > list.getHeight() - edge) list.smoothScrollBy(dp(24), 60);
    }

    private final class QueueAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : LayoutInflater.from(QueueActivity.this).inflate(R.layout.item_queue, parent, false);
            Song s = items.get(position);
            ImageView art = v.findViewById(R.id.q_art);
            art.setClipToOutline(true);
            ArtLoader.get(QueueActivity.this).bindSong(art, s, dp(44));
            TextView title = v.findViewById(R.id.q_title);
            title.setText(s.title);
            ((TextView) v.findViewById(R.id.q_subtitle)).setText(s.artist + " • " + Format.duration(s.duration));
            boolean isCurrent = position == current;
            title.setTextColor(ThemeUtil.color(QueueActivity.this, isCurrent ? R.attr.spAccent : R.attr.spText));
            PlayingBarsView bars = v.findViewById(R.id.q_playing);
            bars.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            bars.setAnimating(isCurrent && service != null && service.isPlaying());
            v.setBackgroundColor(position == dragAt && dragFrom >= 0 ? ThemeUtil.color(QueueActivity.this, R.attr.spSurfaceHigh) : 0);
            if (position != dragAt || dragFrom < 0) v.setBackgroundResource(selectableBg());
            v.setAlpha(position < current && dragFrom < 0 ? 0.55f : 1f);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (service != null) service.jumpTo(position);
                }
            });
            v.findViewById(R.id.q_remove).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (service != null) service.removeFromQueue(position);
                }
            });
            v.findViewById(R.id.q_drag).setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            dragFrom = position;
                            dragAt = position;
                            list.requestDisallowInterceptTouchEvent(true);
                            adapter.notifyDataSetChanged();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (dragFrom >= 0) handleDrag(e);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            dragFrom = -1;
                            dragAt = -1;
                            list.requestDisallowInterceptTouchEvent(false);
                            onQueueChanged();
                            return true;
                        default:
                            return true;
                    }
                }
            });
            return v;
        }
    }

    private int selectableBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
