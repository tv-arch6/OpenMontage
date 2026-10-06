package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The message list.
 *
 * It is a {@link BaseAdapter} on a ListView rather than a RecyclerView adapter
 * for one reason: this project has no `androidx.recyclerview` on its class path
 * (it uses androidx.cardview and swiperefreshlayout, but never RecyclerView), and
 * adding a library to a Sketchware project is a manual step that can break the
 * build. The recycling contract is the same one RecyclerView gives: a fixed set
 * of view types, a holder per row reused through {@code convertView}, and no
 * allocation while scrolling. See user-app/INSTALL.md if you prefer to switch.
 *
 * Row types: date separator, text, image, video, audio, file, ad-request card,
 * price-offer card, system note. A pending (queued) row renders in the same
 * bubble with a clock, a failure with a retry affordance.
 */
public final class CigramAdsChatAdapter extends BaseAdapter {

    public interface Actions {
        void onReply(JSONObject message);

        void onLongPress(JSONObject message, View anchor);

        void onOpenMedia(JSONObject message);

        void onPlayAudio(JSONObject message, int rowId);

        void onQuoteAction(JSONObject message, String action);

        void onRetry(JSONObject pending);

        void onOpenFile(JSONObject message);
    }

    private static final int TYPE_DATE = 0;
    private static final int TYPE_TEXT = 1;
    private static final int TYPE_IMAGE = 2;
    private static final int TYPE_VIDEO = 3;
    private static final int TYPE_AUDIO = 4;
    private static final int TYPE_FILE = 5;
    private static final int TYPE_ORDER = 6;
    private static final int TYPE_QUOTE = 7;
    private static final int TYPE_SYSTEM = 8;
    private static final int TYPE_COUNT = 9;

    private final Activity activity;
    private final Actions actions;
    /** Rows: either a message object, or {"__date": ms} for a separator. */
    private final List<JSONObject> rows = new ArrayList<JSONObject>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", new Locale("ar"));

    private int playingRow = -1;
    private float playedFraction = 0f;

    public CigramAdsChatAdapter(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    /** Rebuilds the row list from the conversation, inserting date separators. */
    public void submit(List<JSONObject> messages) {
        rows.clear();
        long previousDay = 0L;
        for (JSONObject message : messages) {
            if (message == null) continue;
            long at = message.optLong("at", 0L);
            long day = dayStart(at);
            if (day != previousDay && at > 0L) {
                JSONObject separator = new JSONObject();
                try {
                    separator.put("__date", day);
                } catch (Throwable ignored) { }
                rows.add(separator);
                previousDay = day;
            }
            rows.add(message);
        }
        notifyDataSetChanged();
    }

    public void setPlaying(int rowId, float fraction) {
        playingRow = rowId;
        playedFraction = fraction;
        notifyDataSetChanged();
    }

    private static long dayStart(long ms) {
        if (ms <= 0L) return 0L;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(ms);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Override public int getCount() {
        return rows.size();
    }

    @Override public Object getItem(int position) {
        return rows.get(position);
    }

    @Override public long getItemId(int position) {
        JSONObject row = rows.get(position);
        if (row.has("__date")) return -row.optLong("__date");
        long seq = row.optLong("seq", 0L);
        return seq > 0L ? seq : row.optString("client_id", String.valueOf(position)).hashCode();
    }

    @Override public boolean hasStableIds() {
        return true;
    }

    @Override public int getViewTypeCount() {
        return TYPE_COUNT;
    }

    @Override public int getItemViewType(int position) {
        JSONObject row = rows.get(position);
        if (row.has("__date")) return TYPE_DATE;
        String kind = row.optString("kind", "text");
        if ("image".equals(kind)) return TYPE_IMAGE;
        if ("video".equals(kind)) return TYPE_VIDEO;
        if ("audio".equals(kind)) return TYPE_AUDIO;
        if ("file".equals(kind)) return TYPE_FILE;
        if ("ad_request".equals(kind)) return TYPE_ORDER;
        if ("quote".equals(kind)) return TYPE_QUOTE;
        if ("system".equals(kind)) return TYPE_SYSTEM;
        return TYPE_TEXT;
    }

    // ------------------------------------------------------------------ holder

    private static final class Holder {
        int type;
        LinearLayout root;      // the full-width row
        LinearLayout bubble;    // the coloured bubble
        LinearLayout replyBox;
        TextView replyText;
        TextView text;
        TextView time;
        CigramAdsUi.Icon tick;
        ImageView image;
        FrameLayout imageWrap;
        TextView duration;
        CigramAdsUi.Icon playIcon;
        CigramAdsVoice.Waveform waveform;
        TextView fileName;
        TextView fileMeta;
        LinearLayout cardBody;
        LinearLayout buttons;
        ProgressBar progress;
        TextView progressText;
        TextView failure;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        int type = getItemViewType(position);
        Holder holder;
        View view = convertView;
        if (view == null || !(view.getTag() instanceof Holder)
                || ((Holder) view.getTag()).type != type) {
            holder = new Holder();
            holder.type = type;
            view = build(type, holder);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        bind(position, type, holder);
        return view;
    }

    // ------------------------------------------------------------------ build

    private View build(int type, Holder holder) {
        if (type == TYPE_DATE) {
            TextView label = CigramAdsUi.text(activity, "", 12f, CigramAdsUi.MUTED, true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(CigramAdsUi.dp(activity, 12), CigramAdsUi.dp(activity, 6),
                    CigramAdsUi.dp(activity, 12), CigramAdsUi.dp(activity, 6));
            LinearLayout wrap = CigramAdsUi.column(activity);
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            wrap.setPadding(0, CigramAdsUi.dp(activity, 10), 0, CigramAdsUi.dp(activity, 10));
            label.setBackground(CigramAdsUi.round(activity, 0x33000000, 10));
            wrap.addView(label, new LinearLayout.LayoutParams(-2, -2));
            holder.text = label;
            holder.root = wrap;
            return wrap;
        }

        if (type == TYPE_SYSTEM) {
            TextView label = CigramAdsUi.text(activity, "", 12.5f, CigramAdsUi.MUTED, false);
            label.setGravity(Gravity.CENTER);
            label.setBackground(CigramAdsUi.round(activity,
                    CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x18), CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x44), 12));
            int p = CigramAdsUi.dp(activity, 10);
            label.setPadding(p, p, p, p);
            LinearLayout wrap = CigramAdsUi.column(activity);
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            wrap.setPadding(CigramAdsUi.dp(activity, 28), CigramAdsUi.dp(activity, 6),
                    CigramAdsUi.dp(activity, 28), CigramAdsUi.dp(activity, 6));
            wrap.addView(label, new LinearLayout.LayoutParams(-1, -2));
            holder.text = label;
            holder.root = wrap;
            return wrap;
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(CigramAdsUi.dp(activity, 10), CigramAdsUi.dp(activity, 3),
                CigramAdsUi.dp(activity, 10), CigramAdsUi.dp(activity, 3));

        LinearLayout bubble = CigramAdsUi.column(activity);
        int p = CigramAdsUi.dp(activity, 10);
        bubble.setPadding(p, CigramAdsUi.dp(activity, 8), p, CigramAdsUi.dp(activity, 7));
        holder.bubble = bubble;
        holder.root = root;

        holder.replyBox = CigramAdsUi.column(activity);
        holder.replyBox.setBackground(CigramAdsUi.round(activity, 0x22FFFFFF, 8));
        holder.replyBox.setPadding(CigramAdsUi.dp(activity, 8), CigramAdsUi.dp(activity, 5),
                CigramAdsUi.dp(activity, 8), CigramAdsUi.dp(activity, 5));
        holder.replyText = CigramAdsUi.text(activity, "", 12f, 0xCCFFFFFF, false);
        holder.replyText.setMaxLines(2);
        holder.replyText.setEllipsize(TextUtils.TruncateAt.END);
        holder.replyBox.addView(holder.replyText, new LinearLayout.LayoutParams(-1, -2));
        holder.replyBox.setVisibility(View.GONE);
        bubble.addView(holder.replyBox, CigramAdsUi.lp(activity, -1, -2, 0, 0, 0, 6));

        if (type == TYPE_IMAGE || type == TYPE_VIDEO) {
            holder.imageWrap = new FrameLayout(activity);
            holder.image = new ImageView(activity);
            holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.image.setBackground(CigramAdsUi.round(activity, 0x22000000, 10));
            holder.image.setClipToOutline(true);
            holder.imageWrap.addView(holder.image, new FrameLayout.LayoutParams(-1, -1));
            if (type == TYPE_VIDEO) {
                holder.playIcon = new CigramAdsUi.Icon(activity, CigramAdsUi.ICON_SPARK, 0xFFFFFFFF);
                FrameLayout badge = new FrameLayout(activity);
                badge.setBackground(CigramAdsUi.round(activity, 0x99000000, 24));
                int size = CigramAdsUi.dp(activity, 26);
                badge.addView(holder.playIcon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
                int outer = CigramAdsUi.dp(activity, 48);
                holder.imageWrap.addView(badge, new FrameLayout.LayoutParams(outer, outer, Gravity.CENTER));
                holder.duration = CigramAdsUi.text(activity, "", 11f, 0xFFFFFFFF, true);
                holder.duration.setBackground(CigramAdsUi.round(activity, 0x99000000, 6));
                holder.duration.setPadding(CigramAdsUi.dp(activity, 6), 2, CigramAdsUi.dp(activity, 6), 2);
                FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.BOTTOM | Gravity.START);
                dp.setMargins(CigramAdsUi.dp(activity, 6), 0, 0, CigramAdsUi.dp(activity, 6));
                holder.imageWrap.addView(holder.duration, dp);
            }
            holder.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            holder.progress.setMax(100);
            holder.progress.setVisibility(View.GONE);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1,
                    CigramAdsUi.dp(activity, 4), Gravity.BOTTOM);
            holder.imageWrap.addView(holder.progress, pp);
            bubble.addView(holder.imageWrap,
                    new LinearLayout.LayoutParams(CigramAdsUi.dp(activity, 208), CigramAdsUi.dp(activity, 208)));
        }

        if (type == TYPE_AUDIO) {
            LinearLayout row = CigramAdsUi.row(activity);
            FrameLayout play = new FrameLayout(activity);
            play.setBackground(CigramAdsUi.round(activity, 0x33FFFFFF, 20));
            holder.playIcon = new CigramAdsUi.Icon(activity, CigramAdsUi.ICON_SPARK, 0xFFFFFFFF);
            int inner = CigramAdsUi.dp(activity, 20);
            play.addView(holder.playIcon, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
            int size = CigramAdsUi.dp(activity, 40);
            row.addView(play, new LinearLayout.LayoutParams(size, size));

            holder.waveform = new CigramAdsVoice.Waveform(activity);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, CigramAdsUi.dp(activity, 30), 1f);
            wp.setMarginStart(CigramAdsUi.dp(activity, 8));
            wp.setMarginEnd(CigramAdsUi.dp(activity, 8));
            row.addView(holder.waveform, wp);

            holder.duration = CigramAdsUi.text(activity, "0:00", 11.5f, 0xCCFFFFFF, false);
            row.addView(holder.duration, new LinearLayout.LayoutParams(-2, -2));
            bubble.addView(row, new LinearLayout.LayoutParams(CigramAdsUi.dp(activity, 236), -2));
        }

        if (type == TYPE_FILE) {
            LinearLayout row = CigramAdsUi.row(activity);
            int size = CigramAdsUi.dp(activity, 40);
            row.addView(CigramAdsUi.iconBox(activity, CigramAdsUi.ICON_DOC, 0xFFFFFFFF, 40f),
                    new LinearLayout.LayoutParams(size, size));
            LinearLayout texts = CigramAdsUi.column(activity);
            holder.fileName = CigramAdsUi.text(activity, "", 14f, 0xFFFFFFFF, true);
            holder.fileName.setMaxLines(2);
            holder.fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(holder.fileName, new LinearLayout.LayoutParams(-1, -2));
            holder.fileMeta = CigramAdsUi.text(activity, "", 11.5f, 0xBBFFFFFF, false);
            texts.addView(holder.fileMeta, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.setMarginStart(CigramAdsUi.dp(activity, 10));
            row.addView(texts, tp);
            bubble.addView(row, new LinearLayout.LayoutParams(CigramAdsUi.dp(activity, 232), -2));
        }

        if (type == TYPE_ORDER || type == TYPE_QUOTE) {
            holder.cardBody = CigramAdsUi.column(activity);
            bubble.addView(holder.cardBody, new LinearLayout.LayoutParams(CigramAdsUi.dp(activity, 250), -2));
            holder.buttons = CigramAdsUi.row(activity);
            holder.buttons.setVisibility(View.GONE);
            bubble.addView(holder.buttons, CigramAdsUi.lp(activity, -1, -2, 0, 10, 0, 0));
        }

        holder.text = CigramAdsUi.text(activity, "", 15f, 0xFFFFFFFF, false);
        holder.text.setGravity(Gravity.RIGHT | Gravity.TOP);
        bubble.addView(holder.text, new LinearLayout.LayoutParams(-2, -2));

        holder.failure = CigramAdsUi.text(activity, "", 11.5f, CigramAdsUi.BAD, true);
        holder.failure.setVisibility(View.GONE);
        bubble.addView(holder.failure, CigramAdsUi.lp(activity, -1, -2, 0, 4, 0, 0));

        LinearLayout footer = CigramAdsUi.row(activity);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        holder.time = CigramAdsUi.text(activity, "", 10.5f, 0x99FFFFFF, false);
        footer.addView(holder.time, new LinearLayout.LayoutParams(-2, -2));
        holder.tick = new CigramAdsUi.Icon(activity, CigramAdsUi.ICON_CHECK, 0x99FFFFFF);
        LinearLayout.LayoutParams tickLp = new LinearLayout.LayoutParams(
                CigramAdsUi.dp(activity, 13), CigramAdsUi.dp(activity, 13));
        tickLp.setMarginStart(CigramAdsUi.dp(activity, 5));
        footer.addView(holder.tick, tickLp);
        holder.progressText = CigramAdsUi.text(activity, "", 10.5f, 0x99FFFFFF, false);
        holder.progressText.setVisibility(View.GONE);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.setMarginStart(CigramAdsUi.dp(activity, 6));
        footer.addView(holder.progressText, gp);
        bubble.addView(footer, CigramAdsUi.lp(activity, -2, -2, 0, 4, 0, 0));

        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-2, -2);
        bubbleLp.width = -2;
        root.addView(bubble, bubbleLp);

        View spacer = new View(activity);
        root.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        return root;
    }

    // ------------------------------------------------------------------- bind

    private void bind(final int position, int type, final Holder holder) {
        final JSONObject row = rows.get(position);

        if (type == TYPE_DATE) {
            holder.text.setText(dateLabel(row.optLong("__date", 0L)));
            return;
        }
        if (type == TYPE_SYSTEM) {
            holder.text.setText(row.optString("text", ""));
            return;
        }

        final boolean mine = !"admin".equals(row.optString("from", "user"));
        int state = CigramAdsChat.stateOf(row);
        boolean pending = state == CigramAdsChat.STATE_SENDING;
        boolean failed = state == CigramAdsChat.STATE_FAILED;

        // Mine on the right (the reading side), theirs on the left.
        holder.root.setLayoutDirection(mine ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int fill = mine
                ? (failed ? CigramAdsUi.alpha(CigramAdsUi.BAD, 0x55) : 0xFF1B4B5A)
                : CigramAdsUi.CARD;
        holder.bubble.setBackground(CigramAdsUi.round(activity, fill,
                mine ? CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x55) : CigramAdsUi.STROKE, 16));
        holder.bubble.setAlpha(pending ? 0.72f : 1f);
        holder.bubble.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        long replyTo = row.optLong("reply_to", 0L);
        if (replyTo > 0L) {
            holder.replyBox.setVisibility(View.VISIBLE);
            holder.replyText.setText("رداً على: " + replyPreview(replyTo));
        } else {
            holder.replyBox.setVisibility(View.GONE);
        }

        String text = row.optString("text", "");
        if (text.length() > 0) {
            holder.text.setVisibility(View.VISIBLE);
            holder.text.setText(text);
            holder.text.setMaxWidth((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.74f));
        } else {
            holder.text.setVisibility(View.GONE);
        }

        holder.time.setText(row.optLong("at", 0L) > 0L
                ? clock.format(new Date(row.optLong("at"))) : "");

        if (mine) {
            holder.tick.setVisibility(View.VISIBLE);
            if (failed) {
                holder.tick.set(CigramAdsUi.ICON_CROSS, CigramAdsUi.BAD);
            } else if (pending) {
                holder.tick.set(CigramAdsUi.ICON_CLOCK, 0x99FFFFFF);
            } else if (state == CigramAdsChat.STATE_READ) {
                holder.tick.set(CigramAdsUi.ICON_CHECK, CigramAdsUi.ACCENT);
            } else {
                holder.tick.set(CigramAdsUi.ICON_CHECK, 0x99FFFFFF);
            }
        } else {
            holder.tick.setVisibility(View.GONE);
        }

        if (failed) {
            holder.failure.setVisibility(View.VISIBLE);
            String reason = row.optString("local_error", "");
            holder.failure.setText(row.optBoolean("local_permanent", false)
                    ? (reason.length() > 0 ? reason : "لم تُرسل")
                    : "لم تُرسل — اضغط لإعادة المحاولة");
        } else {
            holder.failure.setVisibility(View.GONE);
        }

        int percent = row.optInt("local_progress", -1);
        if (pending && percent >= 0) {
            holder.progressText.setVisibility(View.VISIBLE);
            holder.progressText.setText(percent + "%");
            if (holder.progress != null) {
                holder.progress.setVisibility(View.VISIBLE);
                holder.progress.setProgress(percent);
            }
        } else {
            holder.progressText.setVisibility(View.GONE);
            if (holder.progress != null) holder.progress.setVisibility(View.GONE);
        }

        JSONObject media = row.optJSONObject("media");

        if (type == TYPE_IMAGE && holder.image != null) {
            String url = media == null ? "" : media.optString("url", "");
            CigramAdsMedia.loadInto(activity, url, holder.image, CigramAdsUi.dp(activity, 208));
            holder.image.setContentDescription(text.length() > 0 ? text : "صورة مرسلة");
        }

        if (type == TYPE_VIDEO && holder.image != null) {
            String thumb = media == null ? "" : media.optString("thumb_url", "");
            if (thumb.length() == 0 && media != null) thumb = media.optString("url", "");
            CigramAdsMedia.loadInto(activity, thumb, holder.image, CigramAdsUi.dp(activity, 208));
            long duration = media == null ? 0L : media.optLong("duration_ms", 0L);
            if (holder.duration != null) {
                holder.duration.setText(duration > 0L ? CigramAdsMedia.clockOf(duration) : "فيديو");
            }
            holder.image.setContentDescription("فيديو مرسل");
        }

        if (type == TYPE_AUDIO && holder.waveform != null) {
            float[] levels = CigramAdsVoice.decode(row.optString("wave", ""));
            holder.waveform.setLevels(levels);
            holder.waveform.setColors(0x55FFFFFF, mine ? 0xFFFFFFFF : CigramAdsUi.ACCENT);
            int rowId = (int) getItemId(position);
            boolean isPlaying = playingRow == rowId;
            holder.waveform.setPlayed(isPlaying ? playedFraction : 0f);
            long duration = media == null ? 0L : media.optLong("duration_ms", 0L);
            holder.duration.setText(CigramAdsMedia.clockOf(duration));
            holder.playIcon.set(isPlaying ? CigramAdsUi.ICON_CLOCK : CigramAdsUi.ICON_SPARK, 0xFFFFFFFF);
            holder.bubble.setContentDescription("تسجيل صوتي، " + CigramAdsMedia.clockOf(duration));
        }

        if (type == TYPE_FILE && holder.fileName != null) {
            String name = media == null ? "" : media.optString("name", "");
            holder.fileName.setText(name.length() > 0 ? name : "ملف");
            long size = media == null ? 0L : media.optLong("size", 0L);
            holder.fileMeta.setText(size > 0L ? CigramAdsMedia.humanSize(size) : "");
        }

        if (type == TYPE_ORDER && holder.cardBody != null) {
            JSONObject order = row.optJSONObject("order");
            if (order == null) order = row.optJSONObject("order_preview");
            buildOrderCard(holder.cardBody, order);
            holder.buttons.setVisibility(View.GONE);
        }

        if (type == TYPE_QUOTE && holder.cardBody != null) {
            JSONObject quote = row.optJSONObject("quote");
            buildQuoteCard(holder.cardBody, quote);
            bindQuoteButtons(holder.buttons, row, quote);
        }

        // ---- gestures
        final JSONObject message = row;
        holder.bubble.setClickable(true);
        holder.bubble.setLongClickable(true);
        holder.bubble.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (actions != null) actions.onLongPress(message, v);
                return true;
            }
        });
        final int rowId = (int) getItemId(position);
        final int viewType = type;
        holder.bubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (actions == null) return;
                if (CigramAdsChat.stateOf(message) == CigramAdsChat.STATE_FAILED) {
                    actions.onRetry(message);
                    return;
                }
                if (viewType == TYPE_IMAGE || viewType == TYPE_VIDEO) {
                    actions.onOpenMedia(message);
                } else if (viewType == TYPE_AUDIO) {
                    actions.onPlayAudio(message, rowId);
                } else if (viewType == TYPE_FILE) {
                    actions.onOpenFile(message);
                }
            }
        });
    }

    private String replyPreview(long seq) {
        for (JSONObject row : rows) {
            if (row.optLong("seq", 0L) != seq) continue;
            String kind = row.optString("kind", "text");
            if ("image".equals(kind)) return "صورة";
            if ("video".equals(kind)) return "فيديو";
            if ("audio".equals(kind)) return "تسجيل صوتي";
            if ("file".equals(kind)) return "ملف";
            if ("ad_request".equals(kind)) return "طلب إعلان";
            if ("quote".equals(kind)) return "عرض سعر";
            String text = row.optString("text", "");
            return text.length() > 60 ? text.substring(0, 60) + "…" : text;
        }
        return "رسالة";
    }

    // --------------------------------------------------------------- cards

    private void buildOrderCard(LinearLayout body, JSONObject order) {
        body.removeAllViews();
        if (order == null) return;
        LinearLayout head = CigramAdsUi.row(activity);
        int size = CigramAdsUi.dp(activity, 28);
        head.addView(CigramAdsUi.iconBox(activity, CigramAdsUi.ICON_CALC, 0xFFFFFFFF, 28f),
                new LinearLayout.LayoutParams(size, size));
        TextView title = CigramAdsUi.text(activity, "طلب إعلان", 14.5f, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(activity, 8));
        head.addView(title, tp);
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));

        String currency = order.optString("currency_label", "");
        cardLine(body, "المساحة", order.optString("slot_name", ""));
        cardLine(body, "المدة", order.optInt("days", 0) + " يوم");
        JSONArray cities = order.optJSONArray("cities");
        if (cities != null && cities.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cities.length(); i++) {
                if (sb.length() > 0) sb.append("، ");
                sb.append(cities.optString(i, ""));
            }
            cardLine(body, "المدن", sb.toString());
        }
        JSONObject breakdown = order.optJSONObject("breakdown");
        if (breakdown != null) {
            double discount = breakdown.optDouble("discount_value", 0d);
            if (discount > 0d) {
                cardLine(body, "خصم المدة", "- " + CigramAdsUi.money(discount, currency));
            }
            JSONArray labels = breakdown.optJSONArray("addon_labels");
            if (labels != null && labels.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < labels.length(); i++) {
                    if (sb.length() > 0) sb.append("، ");
                    sb.append(labels.optString(i, ""));
                }
                cardLine(body, "إضافات", sb.toString());
            }
        }
        long views = order.optLong("est_views", 0L);
        if (views > 0L) cardLine(body, "مشاهدات متوقعة", CigramAdsUi.formatNumber(views));

        View rule = new View(activity);
        rule.setBackgroundColor(0x33FFFFFF);
        body.addView(rule, CigramAdsUi.lp(activity, -1, 1, 0, 8, 0, 8));

        LinearLayout totalRow = CigramAdsUi.row(activity);
        totalRow.addView(CigramAdsUi.text(activity, "الإجمالي", 14f, 0xFFFFFFFF, true),
                new LinearLayout.LayoutParams(-2, -2));
        TextView total = CigramAdsUi.text(activity,
                CigramAdsUi.money(order.optDouble("total", 0d), currency), 16f, 0xFFFFFFFF, true);
        total.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        totalRow.addView(total, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(totalRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildQuoteCard(LinearLayout body, JSONObject quote) {
        body.removeAllViews();
        if (quote == null) return;
        LinearLayout head = CigramAdsUi.row(activity);
        int size = CigramAdsUi.dp(activity, 28);
        head.addView(CigramAdsUi.iconBox(activity, CigramAdsUi.ICON_SPARK, 0xFFFFFFFF, 28f),
                new LinearLayout.LayoutParams(size, size));
        TextView title = CigramAdsUi.text(activity, "عرض سعر من الإدارة", 14.5f, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(activity, 8));
        head.addView(title, tp);
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));

        String currency = quote.optString("currency_label", quote.optString("currency", ""));
        TextView amount = CigramAdsUi.text(activity,
                CigramAdsUi.money(quote.optDouble("amount", 0d), currency), 24f, 0xFFFFFFFF, true);
        body.addView(amount, CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));

        if (quote.optString("slot_name", "").length() > 0) {
            cardLine(body, "المساحة", quote.optString("slot_name"));
        }
        if (quote.optInt("days", 0) > 0) cardLine(body, "المدة", quote.optInt("days") + " يوم");
        if (quote.optString("note", "").length() > 0) {
            TextView note = CigramAdsUi.text(activity, quote.optString("note"), 12.5f, 0xDDFFFFFF, false);
            body.addView(note, CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));
        }
        String status = quote.optString("status", "open");
        if (!"open".equals(status)) {
            TextView badge = CigramAdsUi.badge(activity, statusLabel(status),
                    "accepted".equals(status) ? CigramAdsUi.GOOD
                            : ("rejected".equals(status) ? CigramAdsUi.BAD : CigramAdsUi.WARN));
            body.addView(badge, CigramAdsUi.lp(activity, -2, -2, 0, 10, 0, 0));
        }
    }

    private static String statusLabel(String status) {
        if ("accepted".equals(status)) return "تم القبول";
        if ("rejected".equals(status)) return "تم الرفض";
        if ("negotiating".equals(status)) return "قيد التفاوض";
        if ("expired".equals(status)) return "منتهي";
        return "بانتظار ردك";
    }

    private void bindQuoteButtons(LinearLayout buttons, final JSONObject message, JSONObject quote) {
        buttons.removeAllViews();
        // An offer only takes an answer while it is still open.
        if (quote == null || !"open".equals(quote.optString("status", "open"))) {
            buttons.setVisibility(View.GONE);
            return;
        }
        buttons.setVisibility(View.VISIBLE);
        addQuoteButton(buttons, "قبول", CigramAdsUi.GOOD, "accepted", message);
        addQuoteButton(buttons, "تفاوض", CigramAdsUi.WARN, "negotiating", message);
        addQuoteButton(buttons, "رفض", CigramAdsUi.BAD, "rejected", message);
    }

    private void addQuoteButton(LinearLayout parent, String label, int color, final String action,
                                final JSONObject message) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextSize(13f);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(CigramAdsUi.font(activity), Typeface.BOLD);
        button.setTextColor(0xFF04161D);
        button.setMinHeight(CigramAdsUi.dp(activity, 40));
        CigramAdsUi.pressable(button, CigramAdsUi.round(activity, color, 11), 0xFFFFFFFF, 11);
        button.setPadding(CigramAdsUi.dp(activity, 6), CigramAdsUi.dp(activity, 9),
                CigramAdsUi.dp(activity, 6), CigramAdsUi.dp(activity, 9));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (actions != null) actions.onQuoteAction(message, action);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (parent.getChildCount() > 0) lp.setMarginStart(CigramAdsUi.dp(activity, 6));
        parent.addView(button, lp);
    }

    private void cardLine(LinearLayout body, String label, String value) {
        if (value == null || value.length() == 0) return;
        LinearLayout row = CigramAdsUi.row(activity);
        row.setGravity(Gravity.TOP);
        TextView name = CigramAdsUi.text(activity, label, 12f, 0xAAFFFFFF, false);
        row.addView(name, new LinearLayout.LayoutParams(-2, -2));
        TextView content = CigramAdsUi.text(activity, value, 12.5f, 0xFFFFFFFF, false);
        content.setGravity(Gravity.LEFT | Gravity.TOP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(CigramAdsUi.dp(activity, 8));
        row.addView(content, lp);
        body.addView(row, CigramAdsUi.lp(activity, -1, -2, 0, 6, 0, 0));
    }

    private String dateLabel(long day) {
        if (day <= 0L) return "";
        long today = dayStart(System.currentTimeMillis());
        if (day == today) return "اليوم";
        if (day == today - 86400000L) return "أمس";
        try {
            return new SimpleDateFormat("d MMMM yyyy", new Locale("ar")).format(new Date(day));
        } catch (Throwable ignored) {
            return "";
        }
    }
}
