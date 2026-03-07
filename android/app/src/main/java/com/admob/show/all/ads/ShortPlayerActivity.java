package com.admob.show.all.ads;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;

import de.hdodenhof.circleimageview.CircleImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ShortPlayerActivity — YouTube Shorts-style full-screen vertical video player.
 *
 * Entry points:
 *   1. Direct (Shorts tab) — loads personalized shorts from Turso.
 *   2. From ViewkaroActivity — receives video data via Intent extras.
 *
 * Uses the MadXExoPlayer library (ExoPlayerFactory / ExtractorMediaSource API).
 *
 * SECURITY NOTE: The Turso tokens below grant read/write access to the database.
 * In production, store them in BuildConfig fields populated from a secure CI/CD
 * secret store (e.g. gradle.properties or a secrets manager) and do NOT commit
 * plaintext tokens to a public repository.
 */
public class ShortPlayerActivity extends AppCompatActivity {

    // ---------------------------------------------------------------------------
    // Turso database endpoints and auth tokens
    // ---------------------------------------------------------------------------

    private static final String TURSO_VIDEOS_URL =
            "https://lootly-bharatverma.aws-ap-south-1.turso.io/v2/pipeline";
    private static final String TURSO_VIDEOS_TOKEN =
            "Bearer eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9"
            + ".eyJhIjoicnciLCJnaWQiOiI3YjY4ZWYxNi02ZGE3LTQ0ZDYtYjIyNS1hNTM2NjBm"
            + "MTVjMmIiLCJpYXQiOjE3NzA4OTg1MDMsInJpZCI6ImYwNjYzODZkLTU3YzgtNGY3Mi"
            + "05ZDJhLWJmMGJhOGYwMzZkMyJ9"
            + ".XvDdQFSQyAtUuQ_O1GnonG-pucjTEvOqsHqcEBe3jnHXmTde48BzeDLmWrEua8aW2"
            + "nUFla8Z2nHo2HKiBiM_Dw";

    private static final String TURSO_USERS_URL =
            "https://lootly-vermaji.aws-ap-south-1.turso.io/v2/pipeline";
    private static final String TURSO_USERS_TOKEN =
            "Bearer eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9"
            + ".eyJhIjoicnciLCJnaWQiOiIzNDM1MzhkNy1mN2I1LTQyYzgtODMwNS1hMTk4OTJk"
            + "MzA1ZDEiLCJpYXQiOjE3NzE1MDEwODYsInJpZCI6IjhhYzVhMjE0LTdhZjYtNGRmYi"
            + "1iNmY1LTQ1ODliNmM5NGVmZSJ9"
            + ".N_jOF5MCKKrBzDi88ycVl9b9gYWVW4EfGfK-vSf6088URYrHk2JGzixjmiWY_NL-K"
            + "9qH6mNNgCLlvUDyCQ";

    // ---------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------

    private ViewPager2 viewPager;
    private ProgressBar loadingIndicator;

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private final List<ShortVideo> shortsList = new ArrayList<>();
    private ShortsPagerAdapter adapter;
    private String currentUserId = "";
    private int currentPosition = 0;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocalDB localDB;

    // ---------------------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------------------

    /** Represents a single short video and its metadata. */
    public static class ShortVideo {
        public String id;
        public String url;
        public String title;
        public String description;
        /** UID of the content creator. */
        public String uid;
        public String channelName;
        public String channelAvatar;
        public String thumbnailUrl;
        public String createdAt;
        public long duration;
        public long views;
        public long likes;
        public boolean isLiked;
        public String category;
        public String tags;
    }

    // ---------------------------------------------------------------------------
    // Activity lifecycle
    // ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen, keep screen on
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.short_player);

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        currentUserId = prefs.getString("uid", "");

        localDB = new LocalDB(this);

        viewPager = findViewById(R.id.viewPager);
        loadingIndicator = findViewById(R.id.loadingIndicator);

        viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        viewPager.setOffscreenPageLimit(1);

        adapter = new ShortsPagerAdapter(this, shortsList);
        viewPager.setAdapter(adapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                adapter.pauseAt(currentPosition);
                currentPosition = position;
                adapter.playAt(currentPosition);

                // Track interest
                if (!currentUserId.isEmpty()) {
                    executor.execute(() -> localDB.addInterest(currentUserId, "Shorts"));
                }
            }
        });

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("video_id")) {
            loadShortsFromIntent(intent);
        } else {
            loadShortsFromTurso();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        adapter.pauseAt(currentPosition);
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.playAt(currentPosition);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.releaseAll();
        executor.shutdown();
    }

    // ---------------------------------------------------------------------------
    // Data loading — from Intent (ViewkaroActivity entry)
    // ---------------------------------------------------------------------------

    private void loadShortsFromIntent(Intent intent) {
        ShortVideo video = new ShortVideo();
        video.id = intent.getStringExtra("video_id");
        video.url = intent.getStringExtra("video_url");
        video.title = intent.getStringExtra("video_title");
        video.uid = intent.getStringExtra("uid");
        video.channelName = intent.getStringExtra("channel_name");
        video.channelAvatar = intent.getStringExtra("channel_avatar");
        video.thumbnailUrl = intent.getStringExtra("thumbnail_url");
        video.createdAt = intent.getStringExtra("created_at");
        video.duration = intent.getLongExtra("duration", 0);
        video.views = intent.getLongExtra("views", 0);
        video.likes = intent.getLongExtra("likes", 0);

        shortsList.add(video);
        adapter.notifyItemInserted(0);
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

        // Auto-play first item
        viewPager.post(() -> adapter.playAt(0));

        // Save watch start in LocalDB
        if (!currentUserId.isEmpty() && video.id != null) {
            executor.execute(() -> localDB.saveWatch(currentUserId, video.id, 0, 0));
        }

        // Load more shorts in background (excluding the one already loaded)
        final String excludeId = video.id;
        executor.execute(() -> {
            try {
                String notClause = (excludeId != null && !excludeId.isEmpty())
                        ? " AND v.id != '" + escapeSql(excludeId) + "'"
                        : "";
                String sql = "SELECT v.id, v.url, v.title, v.description, v.uid, v.thumbnail,"
                        + " v.created_at, v.duration, v.views, v.likes, v.category, v.tags,"
                        + " u.name AS creator_name, u.Avater AS creator_avatar"
                        + " FROM videos v LEFT JOIN Users u ON v.uid = u.uid"
                        + " WHERE (v.category = 'Shorts' OR v.tags = 'short')"
                        + notClause
                        + " ORDER BY v.views DESC LIMIT 19";
                JSONObject response = executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
                List<ShortVideo> more = parseShortsFromResponse(response);
                mainHandler.post(() -> {
                    int startPos = shortsList.size();
                    shortsList.addAll(more);
                    adapter.notifyItemRangeInserted(startPos, more.size());
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Data loading — personalized algorithm (direct entry)
    // ---------------------------------------------------------------------------

    /**
     * Loads shorts using the personalized algorithm:
     *   1. Personalized (exclude already-watched videos)
     *   2. Trending (high views)
     *   3. Latest (fallback)
     */
    private void loadShortsFromTurso() {
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            List<ShortVideo> videos = new ArrayList<>();

            // ---- Step 1: personalized (skip already-watched) ----
            if (!currentUserId.isEmpty()) {
                try {
                    List<String> watchedIds = localDB.getHighlyWatchedVideoIds(currentUserId);
                    if (watchedIds != null && !watchedIds.isEmpty()) {
                        StringBuilder notIn = buildIdList(watchedIds);
                        String sql = "SELECT v.id, v.url, v.title, v.description, v.uid,"
                                + " v.thumbnail, v.created_at, v.duration, v.views, v.likes,"
                                + " v.category, v.tags,"
                                + " u.name AS creator_name, u.Avater AS creator_avatar"
                                + " FROM videos v LEFT JOIN Users u ON v.uid = u.uid"
                                + " WHERE (v.category = 'Shorts' OR v.tags = 'short')"
                                + " AND v.id NOT IN (" + notIn + ")"
                                + " ORDER BY v.views DESC LIMIT 10";
                        JSONObject response =
                                executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
                        videos.addAll(parseShortsFromResponse(response));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // ---- Step 2: trending ----
            if (videos.size() < 10) {
                try {
                    String sql = "SELECT v.id, v.url, v.title, v.description, v.uid,"
                            + " v.thumbnail, v.created_at, v.duration, v.views, v.likes,"
                            + " v.category, v.tags,"
                            + " u.name AS creator_name, u.Avater AS creator_avatar"
                            + " FROM videos v LEFT JOIN Users u ON v.uid = u.uid"
                            + " WHERE (v.category = 'Shorts' OR v.tags = 'short')"
                            + " ORDER BY v.views DESC LIMIT 20";
                    JSONObject response =
                            executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
                    for (ShortVideo v : parseShortsFromResponse(response)) {
                        if (videos.size() >= 20) break;
                        if (!containsId(videos, v.id)) videos.add(v);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // ---- Step 3: latest (fallback) ----
            if (videos.size() < 5) {
                try {
                    String sql = "SELECT v.id, v.url, v.title, v.description, v.uid,"
                            + " v.thumbnail, v.created_at, v.duration, v.views, v.likes,"
                            + " v.category, v.tags,"
                            + " u.name AS creator_name, u.Avater AS creator_avatar"
                            + " FROM videos v LEFT JOIN Users u ON v.uid = u.uid"
                            + " WHERE (v.category = 'Shorts' OR v.tags = 'short')"
                            + " ORDER BY v.created_at DESC LIMIT 20";
                    JSONObject response =
                            executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
                    for (ShortVideo v : parseShortsFromResponse(response)) {
                        if (videos.size() >= 20) break;
                        if (!containsId(videos, v.id)) videos.add(v);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            final List<ShortVideo> finalVideos = videos;
            mainHandler.post(() -> {
                if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
                if (finalVideos.isEmpty()) {
                    Toast.makeText(ShortPlayerActivity.this,
                            "No shorts available", Toast.LENGTH_SHORT).show();
                    return;
                }
                shortsList.clear();
                shortsList.addAll(finalVideos);
                adapter.notifyDataSetChanged();
                viewPager.post(() -> adapter.playAt(0));

                if (!currentUserId.isEmpty()) {
                    executor.execute(() -> localDB.addInterest(currentUserId, "Shorts"));
                }
            });
        });
    }

    // ---------------------------------------------------------------------------
    // Turso HTTP helpers
    // ---------------------------------------------------------------------------

    /**
     * Executes a Turso pipeline SQL statement and returns the JSON response.
     * Must be called on a background thread.
     */
    JSONObject executeTursoRequest(String apiUrl, String token, String sql) throws Exception {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);

            String body = "{\"requests\":["
                    + "{\"type\":\"execute\",\"stmt\":{\"sql\":\""
                    + sql.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"}},"
                    + "{\"type\":\"close\"}"
                    + "]}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Parses the Turso pipeline response into a list of ShortVideo objects.
     * Handles the {type, value} column value format returned by Turso.
     */
    private List<ShortVideo> parseShortsFromResponse(JSONObject response) {
        List<ShortVideo> list = new ArrayList<>();
        try {
            JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) return list;

            JSONObject firstResult = results.getJSONObject(0);
            if (!"ok".equals(firstResult.optString("type"))) return list;

            JSONObject result = firstResult
                    .getJSONObject("response")
                    .getJSONObject("result");

            JSONArray cols = result.getJSONArray("cols");
            JSONArray rows = result.getJSONArray("rows");

            // Build column-name → index map
            Map<String, Integer> colMap = new HashMap<>();
            for (int i = 0; i < cols.length(); i++) {
                colMap.put(cols.getJSONObject(i).getString("name"), i);
            }

            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i);
                ShortVideo video = new ShortVideo();
                video.id = rowStr(row, colMap, "id");
                video.url = firstNonNull(rowStr(row, colMap, "url"),
                        rowStr(row, colMap, "video_url"));
                video.title = rowStr(row, colMap, "title");
                video.description = rowStr(row, colMap, "description");
                video.uid = rowStr(row, colMap, "uid");
                video.channelName = firstNonNull(rowStr(row, colMap, "creator_name"),
                        rowStr(row, colMap, "channel_name"),
                        rowStr(row, colMap, "name"));
                video.channelAvatar = firstNonNull(rowStr(row, colMap, "creator_avatar"),
                        rowStr(row, colMap, "Avater"),
                        rowStr(row, colMap, "channel_avatar"));
                video.thumbnailUrl = firstNonNull(rowStr(row, colMap, "thumbnail"),
                        rowStr(row, colMap, "thumbnail_url"));
                video.createdAt = rowStr(row, colMap, "created_at");
                video.category = rowStr(row, colMap, "category");
                video.tags = rowStr(row, colMap, "tags");
                video.views = parseLong(rowStr(row, colMap, "views"));
                video.likes = parseLong(rowStr(row, colMap, "likes"));
                video.duration = parseLong(rowStr(row, colMap, "duration"));

                if (video.id != null && video.url != null && !video.url.isEmpty()) {
                    list.add(video);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /** Extracts a string value from a Turso row cell (which may be a {type,value} object). */
    private String rowStr(JSONArray row, Map<String, Integer> colMap, String col) {
        Integer idx = colMap.get(col);
        if (idx == null || idx >= row.length()) return null;
        try {
            Object val = row.get(idx);
            if (val == null || JSONObject.NULL.equals(val)) return null;
            if (val instanceof JSONObject) {
                String v = ((JSONObject) val).optString("value", null);
                return (v == null || "null".equals(v)) ? null : v;
            }
            return val.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Escapes a string for embedding inside a Turso SQL literal (doubles single quotes). */
    private String escapeSql(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    private StringBuilder buildIdList(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(escapeSql(ids.get(i))).append("'");
        }
        return sb;
    }

    private boolean containsId(List<ShortVideo> list, String id) {
        if (id == null) return false;
        for (ShortVideo v : list) {
            if (id.equals(v.id)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // Business logic called by ViewHolders
    // ---------------------------------------------------------------------------

    /** Increments the view count for a video after 2 seconds of playback. */
    void incrementViewCount(String videoId) {
        executor.execute(() -> {
            try {
                String sql = "UPDATE videos SET views = views + 1 WHERE id = '"
                        + escapeSql(videoId) + "'";
                executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Loads whether the current user has liked a given video. */
    void loadLikeStatus(String videoId, LikeStatusCallback callback) {
        if (currentUserId.isEmpty() || videoId == null) return;
        executor.execute(() -> {
            boolean isLiked = false;
            try {
                String sql = "SELECT reaction FROM video_reactions"
                        + " WHERE uid = '" + escapeSql(currentUserId) + "'"
                        + " AND video_id = '" + escapeSql(videoId) + "'";
                JSONObject resp = executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
                JSONArray results = resp.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    if ("ok".equals(first.optString("type"))) {
                        JSONArray rows = first.getJSONObject("response")
                                .getJSONObject("result").getJSONArray("rows");
                        if (rows.length() > 0) {
                            String reaction = rowStr(rows.getJSONArray(0),
                                    singleColMap("reaction"), "reaction");
                            isLiked = "like".equals(reaction);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            final boolean liked = isLiked;
            mainHandler.post(() -> callback.onStatusLoaded(liked));
        });
    }

    private Map<String, Integer> singleColMap(String colName) {
        Map<String, Integer> m = new HashMap<>();
        m.put(colName, 0);
        return m;
    }

    /** Toggles like on a video and reports the new state and count back via callback. */
    void toggleLike(String videoId, boolean currentlyLiked, LikeCallback callback) {
        if (currentUserId.isEmpty()) {
            mainHandler.post(() ->
                    Toast.makeText(this, "Please login to like", Toast.LENGTH_SHORT).show());
            return;
        }
        executor.execute(() -> {
            try {
                String sql;
                if (currentlyLiked) {
                    sql = "DELETE FROM video_reactions"
                            + " WHERE uid = '" + escapeSql(currentUserId) + "'"
                            + " AND video_id = '" + escapeSql(videoId) + "'";
                } else {
                    sql = "INSERT INTO video_reactions (uid, video_id, reaction)"
                            + " VALUES ('" + escapeSql(currentUserId) + "', '"
                            + escapeSql(videoId) + "', 'like')"
                            + " ON CONFLICT(uid, video_id) DO UPDATE SET reaction = 'like'";
                }
                executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);

                // Refresh like count from DB
                String updateSql = "UPDATE videos SET likes ="
                        + " (SELECT COUNT(*) FROM video_reactions"
                        + " WHERE video_id = '" + escapeSql(videoId) + "'"
                        + " AND reaction = 'like')"
                        + " WHERE id = '" + escapeSql(videoId) + "'";
                executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, updateSql);

                String countSql = "SELECT likes FROM videos WHERE id = '"
                        + escapeSql(videoId) + "'";
                JSONObject countResp =
                        executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, countSql);
                long newCount = extractFirstLong(countResp);

                mainHandler.post(() -> callback.onLikeUpdated(!currentlyLiked, newCount));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Records a dislike reaction (upserts 'dislike' in video_reactions). */
    void dislikeVideo(String videoId) {
        if (currentUserId.isEmpty()) {
            mainHandler.post(() ->
                    Toast.makeText(this, "Please login to react", Toast.LENGTH_SHORT).show());
            return;
        }
        executor.execute(() -> {
            try {
                String sql = "INSERT INTO video_reactions (uid, video_id, reaction)"
                        + " VALUES ('" + escapeSql(currentUserId) + "', '"
                        + escapeSql(videoId) + "', 'dislike')"
                        + " ON CONFLICT(uid, video_id) DO UPDATE SET reaction = 'dislike'";
                executeTursoRequest(TURSO_VIDEOS_URL, TURSO_VIDEOS_TOKEN, sql);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private long extractFirstLong(JSONObject response) {
        try {
            JSONArray results = response.optJSONArray("results");
            if (results != null && results.length() > 0) {
                JSONObject first = results.getJSONObject(0);
                if ("ok".equals(first.optString("type"))) {
                    JSONArray rows = first.getJSONObject("response")
                            .getJSONObject("result").getJSONArray("rows");
                    if (rows.length() > 0) {
                        return parseLong(rowStr(rows.getJSONArray(0),
                                singleColMap("likes"), "likes"));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /** Persists updated watch time to LocalDB. */
    void updateWatchTime(String videoId, long watchTimeSec, int watchPercent) {
        if (!currentUserId.isEmpty() && videoId != null) {
            executor.execute(() ->
                    localDB.updateWatch(currentUserId, videoId, watchTimeSec, watchPercent));
        }
    }

    /** Opens CommentActivity (if present) for the given video. */
    void openComments(String videoId, String videoTitle) {
        try {
            Intent intent = new Intent(this,
                    Class.forName("com.admob.show.all.ads.CommentActivity"));
            intent.putExtra("video_id", videoId);
            intent.putExtra("video_title", videoTitle);
            startActivity(intent);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Comments coming soon", Toast.LENGTH_SHORT).show();
        }
    }

    /** Shares a video URL via the system share sheet. */
    void shareVideo(ShortVideo video) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        String text = (video.title != null ? video.title : "Check out this short")
                + "\n" + (video.url != null ? video.url : "");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    // ---------------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------------

    interface LikeCallback {
        void onLikeUpdated(boolean isLiked, long newCount);
    }

    interface LikeStatusCallback {
        void onStatusLoaded(boolean isLiked);
    }

    // ===========================================================================
    // ViewPager2 Adapter
    // ===========================================================================

    static class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.ShortViewHolder> {

        private final ShortPlayerActivity activity;
        private final List<ShortVideo> videos;
        /** Tracks active ViewHolder instances by adapter position. */
        private final Map<Integer, ShortViewHolder> holderMap = new HashMap<>();

        ShortsPagerAdapter(ShortPlayerActivity activity, List<ShortVideo> videos) {
            this.activity = activity;
            this.videos = videos;
        }

        @NonNull
        @Override
        public ShortViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_short, parent, false);
            return new ShortViewHolder(view, activity);
        }

        @Override
        public void onBindViewHolder(@NonNull ShortViewHolder holder, int position) {
            holderMap.put(position, holder);
            holder.bind(videos.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull ShortViewHolder holder) {
            super.onViewRecycled(holder);
            holder.releasePlayer();
            // Remove from map by reference
            Iterator<Map.Entry<Integer, ShortViewHolder>> it = holderMap.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() == holder) {
                    it.remove();
                    break;
                }
            }
        }

        @Override
        public int getItemCount() {
            return videos.size();
        }

        void playAt(int position) {
            ShortViewHolder holder = holderMap.get(position);
            if (holder != null) holder.startPlayback();
        }

        void pauseAt(int position) {
            ShortViewHolder holder = holderMap.get(position);
            if (holder != null) holder.pausePlayback();
        }

        void releaseAll() {
            for (ShortViewHolder holder : holderMap.values()) {
                holder.releasePlayer();
            }
            holderMap.clear();
        }

        // =======================================================================
        // ViewHolder
        // =======================================================================

        static class ShortViewHolder extends RecyclerView.ViewHolder {

            private final ShortPlayerActivity activity;

            // Views
            private final PlayerView playerView;
            private final ImageView thumbnail;
            private final CircleImageView channelAvatar;
            private final CircleImageView channelAvatarRight;
            private final TextView channelName;
            private final TextView videoTitle;
            private final TextView videoDescription;
            private final ImageView btnLike;
            private final TextView tvLikeCount;
            private final ImageView btnDislike;
            private final ImageView btnComment;
            private final TextView tvCommentCount;
            private final ImageView btnShare;
            private final ProgressBar videoProgress;
            private final TextView speedOverlay;
            private final ImageView pauseIcon;
            private final android.widget.Button btnSubscribe;

            // Player
            private SimpleExoPlayer player;
            private ShortVideo currentVideo;
            private boolean isLiked = false;

            // Progress + view-count timers
            private final Handler progressHandler = new Handler(Looper.getMainLooper());
            private final Handler viewCountHandler = new Handler(Looper.getMainLooper());
            private Runnable progressRunnable;
            private Runnable viewCountRunnable;
            private long watchStartTime = 0;

            ShortViewHolder(@NonNull View itemView, ShortPlayerActivity activity) {
                super(itemView);
                this.activity = activity;

                playerView = itemView.findViewById(R.id.playerView);
                thumbnail = itemView.findViewById(R.id.thumbnail);
                channelAvatar = itemView.findViewById(R.id.channelAvatar);
                channelAvatarRight = itemView.findViewById(R.id.channelAvatarRight);
                channelName = itemView.findViewById(R.id.channelName);
                videoTitle = itemView.findViewById(R.id.videoTitle);
                videoDescription = itemView.findViewById(R.id.videoDescription);
                btnLike = itemView.findViewById(R.id.btnLike);
                tvLikeCount = itemView.findViewById(R.id.tvLikeCount);
                btnDislike = itemView.findViewById(R.id.btnDislike);
                btnComment = itemView.findViewById(R.id.btnComment);
                tvCommentCount = itemView.findViewById(R.id.tvCommentCount);
                btnShare = itemView.findViewById(R.id.btnShare);
                videoProgress = itemView.findViewById(R.id.videoProgress);
                speedOverlay = itemView.findViewById(R.id.speedOverlay);
                pauseIcon = itemView.findViewById(R.id.pauseIcon);
                btnSubscribe = itemView.findViewById(R.id.btnSubscribe);
            }

            /** Binds a ShortVideo to this ViewHolder and sets up the player + UI. */
            void bind(ShortVideo video) {
                this.currentVideo = video;
                this.isLiked = video.isLiked;

                // Text fields
                if (videoTitle != null) {
                    videoTitle.setText(video.title != null ? video.title : "");
                }
                if (channelName != null) {
                    channelName.setText(video.channelName != null
                            ? "@" + video.channelName : "@creator");
                }
                if (tvLikeCount != null) {
                    tvLikeCount.setText(formatCount(video.likes));
                }
                if (tvCommentCount != null) {
                    tvCommentCount.setText("0");
                }
                if (videoDescription != null) {
                    if (video.description != null && !video.description.isEmpty()) {
                        videoDescription.setText(video.description);
                        videoDescription.setVisibility(View.VISIBLE);
                    } else {
                        videoDescription.setVisibility(View.GONE);
                    }
                }

                // Avatars
                if (video.channelAvatar != null && !video.channelAvatar.isEmpty()) {
                    if (channelAvatar != null) {
                        Glide.with(activity).load(video.channelAvatar)
                                .circleCrop().into(channelAvatar);
                    }
                    if (channelAvatarRight != null) {
                        Glide.with(activity).load(video.channelAvatar)
                                .circleCrop().into(channelAvatarRight);
                    }
                }

                // Thumbnail (shown until player starts)
                if (video.thumbnailUrl != null && !video.thumbnailUrl.isEmpty()
                        && thumbnail != null) {
                    Glide.with(activity).load(video.thumbnailUrl)
                            .centerCrop().into(thumbnail);
                    thumbnail.setAlpha(1.0f);
                }

                updateLikeButtonState();
                setupClickListeners(video);
                setupPlayer(video);

                // Load like status asynchronously
                activity.loadLikeStatus(video.id, liked -> {
                    isLiked = liked;
                    video.isLiked = liked;
                    updateLikeButtonState();
                });
            }

            // -------------------------------------------------------------------
            // ExoPlayer setup (MadXExoPlayer / old ExoPlayer API)
            // -------------------------------------------------------------------

            private void setupPlayer(ShortVideo video) {
                releasePlayer();

                Context ctx = activity;
                DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
                AdaptiveTrackSelection.Factory trackSelFactory =
                        new AdaptiveTrackSelection.Factory(bandwidthMeter);
                DefaultTrackSelector trackSelector = new DefaultTrackSelector(trackSelFactory);

                // Custom buffer sizes for smooth short-video playback
                DefaultLoadControl loadControl = new DefaultLoadControl();

                // Uses ExoPlayerFactory (NOT SimpleExoPlayer.Builder)
                player = ExoPlayerFactory.newSimpleInstance(ctx, trackSelector, loadControl);

                if (playerView != null) {
                    playerView.setPlayer(player);
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    playerView.setUseController(false);
                }

                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.setVolume(1.0f);

                // Build media source
                DefaultHttpDataSourceFactory dataSourceFactory =
                        new DefaultHttpDataSourceFactory("MadXExoPlayer/1.0", bandwidthMeter);
                String url = video.url != null ? video.url : "";
                MediaSource mediaSource;
                if (url.contains(".m3u8") || url.contains("m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(Uri.parse(url));
                } else {
                    mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                            .setExtractorsFactory(new DefaultExtractorsFactory())
                            .createMediaSource(Uri.parse(url));
                }

                player.prepare(mediaSource);
                player.setPlayWhenReady(false); // wait for explicit playAt()

                player.addListener(new Player.EventListener() {
                    @Override
                    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                        if (playbackState == Player.STATE_READY && playWhenReady
                                && thumbnail != null) {
                            // Fade out thumbnail once video is ready
                            thumbnail.animate().alpha(0f).setDuration(300).start();
                        }
                    }

                    @Override
                    public void onPlayerError(ExoPlaybackException error) {
                        // No-op: errors are silent to avoid disrupting UX
                    }
                });
            }

            // -------------------------------------------------------------------
            // Playback control
            // -------------------------------------------------------------------

            void startPlayback() {
                if (player == null && currentVideo != null) {
                    setupPlayer(currentVideo);
                }
                if (player == null) return;

                player.setPlayWhenReady(true);
                watchStartTime = System.currentTimeMillis();

                // Increment view count after 2 seconds
                viewCountRunnable = () -> {
                    if (currentVideo != null && currentVideo.id != null) {
                        activity.incrementViewCount(currentVideo.id);
                    }
                };
                viewCountHandler.postDelayed(viewCountRunnable, 2_000);

                startProgressUpdates();

                // Save watch start to LocalDB
                if (currentVideo != null && !activity.currentUserId.isEmpty()) {
                    activity.executor.execute(() ->
                            activity.localDB.saveWatch(
                                    activity.currentUserId, currentVideo.id, 0, 0));
                }
            }

            void pausePlayback() {
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
                if (viewCountRunnable != null) {
                    viewCountHandler.removeCallbacks(viewCountRunnable);
                    viewCountRunnable = null;
                }
                stopProgressUpdates();
                saveWatchTime();
            }

            void releasePlayer() {
                pausePlayback();
                if (player != null) {
                    if (playerView != null) playerView.setPlayer(null);
                    player.release();
                    player = null;
                }
            }

            // -------------------------------------------------------------------
            // Progress bar
            // -------------------------------------------------------------------

            private void startProgressUpdates() {
                progressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (player != null && videoProgress != null) {
                            long duration = player.getDuration();
                            long position = player.getCurrentPosition();
                            if (duration > 0) {
                                videoProgress.setProgress((int) ((position * 100) / duration));
                            }
                        }
                        progressHandler.postDelayed(this, 500);
                    }
                };
                progressHandler.post(progressRunnable);
            }

            private void stopProgressUpdates() {
                if (progressRunnable != null) {
                    progressHandler.removeCallbacks(progressRunnable);
                    progressRunnable = null;
                }
            }

            // -------------------------------------------------------------------
            // Watch-time saving
            // -------------------------------------------------------------------

            private void saveWatchTime() {
                if (currentVideo == null || player == null || watchStartTime == 0) return;
                long watchSec = (System.currentTimeMillis() - watchStartTime) / 1000;
                long duration = player.getDuration();
                int watchPercent = (duration > 0)
                        ? (int) ((player.getCurrentPosition() * 100) / duration) : 0;
                activity.updateWatchTime(currentVideo.id, watchSec, watchPercent);
                watchStartTime = 0;
            }

            // -------------------------------------------------------------------
            // Click / gesture listeners
            // -------------------------------------------------------------------

            private void setupClickListeners(ShortVideo video) {
                if (playerView != null) {
                    GestureDetector gestureDetector = new GestureDetector(
                            activity, new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            if (player != null) {
                                boolean nowPlaying = !player.getPlayWhenReady();
                                player.setPlayWhenReady(nowPlaying);
                                if (pauseIcon != null) {
                                    pauseIcon.setVisibility(nowPlaying ? View.GONE : View.VISIBLE);
                                    if (!nowPlaying) {
                                        new Handler(Looper.getMainLooper()).postDelayed(
                                                () -> pauseIcon.setVisibility(View.GONE), 800);
                                    }
                                }
                            }
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent e) {
                            if (player != null) {
                                player.setPlaybackParameters(new PlaybackParameters(2.0f));
                                if (speedOverlay != null) {
                                    speedOverlay.setVisibility(View.VISIBLE);
                                }
                            }
                        }
                    });

                    playerView.setOnTouchListener((v, event) -> {
                        // Restore normal speed on finger lift
                        if (event.getAction() == MotionEvent.ACTION_UP
                                || event.getAction() == MotionEvent.ACTION_CANCEL) {
                            if (player != null) {
                                player.setPlaybackParameters(new PlaybackParameters(1.0f));
                            }
                            if (speedOverlay != null) {
                                speedOverlay.setVisibility(View.GONE);
                            }
                        }
                        boolean consumed = gestureDetector.onTouchEvent(event);
                        // Must call performClick for accessibility
                        if (event.getAction() == MotionEvent.ACTION_UP) v.performClick();
                        return consumed;
                    });
                }

                // Like
                if (btnLike != null) {
                    btnLike.setOnClickListener(v -> activity.toggleLike(
                            video.id, isLiked, (liked, count) -> {
                                isLiked = liked;
                                video.isLiked = liked;
                                video.likes = count;
                                updateLikeButtonState();
                                if (tvLikeCount != null) tvLikeCount.setText(formatCount(count));
                            }));
                }

                // Dislike
                if (btnDislike != null) {
                    btnDislike.setOnClickListener(v -> {
                        activity.dislikeVideo(video.id);
                        Toast.makeText(activity, "Disliked", Toast.LENGTH_SHORT).show();
                    });
                }

                // Comment
                if (btnComment != null) {
                    btnComment.setOnClickListener(v ->
                            activity.openComments(video.id, video.title));
                }

                // Share
                if (btnShare != null) {
                    btnShare.setOnClickListener(v -> activity.shareVideo(video));
                }

                // Description — tap to expand/collapse
                if (videoDescription != null) {
                    videoDescription.setOnClickListener(v -> {
                        if (videoDescription.getMaxLines() <= 1) {
                            videoDescription.setMaxLines(Integer.MAX_VALUE);
                            videoDescription.setEllipsize(null);
                        } else {
                            videoDescription.setMaxLines(1);
                            videoDescription.setEllipsize(TextUtils.TruncateAt.END);
                        }
                    });
                }

                // Title — tap to toggle description visibility
                if (videoTitle != null) {
                    videoTitle.setOnClickListener(v -> {
                        if (videoDescription != null
                                && video.description != null
                                && !video.description.isEmpty()) {
                            videoDescription.setVisibility(
                                    videoDescription.getVisibility() == View.VISIBLE
                                            ? View.GONE : View.VISIBLE);
                        }
                    });
                }
            }

            // -------------------------------------------------------------------
            // UI helpers
            // -------------------------------------------------------------------

            private void updateLikeButtonState() {
                if (btnLike != null) {
                    btnLike.setColorFilter(isLiked ? Color.RED : Color.WHITE);
                }
            }

            private String formatCount(long count) {
                if (count >= 1_000_000L) {
                    return String.format("%.1fM", count / 1_000_000.0);
                } else if (count >= 1_000L) {
                    return String.format("%.1fK", count / 1_000.0);
                }
                return String.valueOf(count);
            }
        }
    }
}
