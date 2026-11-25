package com.example.subtitles.view.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.subtitles.R;
/**
 * Service responsible for displaying live subtitles as a floating overlay.
 * <p>
 * Supports RTL and LTR text, smooth updates with minimum display time,
 * and draggable positioning within screen bounds.
 * <p>
 * Runs as a foreground service with notification to remain active in background.
 */
public class SubtitleOverlayService extends Service {
    // Minimum time a subtitle must stay visible (ms)
    public static final int MINTIMESTAY = 700;

    private static final String TAG = "SubtitleOverlayService";

    // Unicode characters for enforcing text direction
    private static final char RLE = '\u202B'; // Right-to-Left Embedding
    private static final char LRE = '\u202A'; // Left-to-Right Embedding
    private static final char PDF = '\u202C'; // Pop Directional Formatting
    // Foreground notification constants
    private static final int NOTIFICATION_ID = 2002;
    private static final String CHANNEL_ID = "subtitle_overlay_channel";
    // Singleton instance
    private static SubtitleOverlayService instance;
    // Handler for posting tasks to main thread
    private final Handler handler = new Handler(Looper.getMainLooper());
    // WindowManager and overlay views
    private WindowManager windowManager;
    private View overlayView;
    private TextView overlayText;
    private View overlayRoot;
    private View headerRow;
    private Button btnToggle;
    private Button btnClear;
    private Button btnOff;
    private static final int HISTORY_COLOR = 0xFFE0E0E0;
    private static final int LIVE_COLOR = 0xFF64B5F6;
    private ScrollView historyScroll;
    private TextView historyText;
    private final SubtitleHistory history = new SubtitleHistory();
    private boolean expanded = false;
    private String liveText = "";
    private int compactY;
    private int basePaddingPx;
    private static final int EXPANDED_BOTTOM_RESERVE_DP = 150;
    private static final int NEAR_BOTTOM_DP = 48;
    private WindowManager.LayoutParams params;
    // Subtitle update state
    private boolean waitingTochange = false;
    private long lastUpdateTime = 0L;
    private String pendingText;
    private final Runnable applyRunnable = this::applyPendingSubtitle;
    private boolean overlayVisible = false;
    // Screen and overlay positioning
    private int screenHeight;
    private int marginPx;
    private int initialOffsetPx;
    private int screenWidth;
    /**
     * Determines if a given text should be displayed RTL.
     *
     * @param text input text
     * @return true if text contains Hebrew/Arabic characters
     */
    private static boolean isRTL(String text) {
        if (text == null || text.isEmpty()) return false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                return block == Character.UnicodeBlock.HEBREW
                        || block == Character.UnicodeBlock.ARABIC;
            }
        }
        return false;
    }


    /**
     * Public method to update subtitle text.
     * Always posts update to main thread.
     *
     * @param text subtitle string
     */
    public static void updateText(String text) {
        if (instance != null) {
            instance.handler.post(() -> instance.handleTextUpdate(text));
        }
    }
    /**
     * Appends a finalized sentence to the on-screen history (no-op when service is not running).
     */
    public static void appendHistory(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) return;
        final SubtitleOverlayService svc = instance;
        if (svc != null) {
            svc.handler.post(() -> svc.addHistory(sentence));
        }
    }

    private void addHistory(String sentence) {
        history.append(sentence);
        refreshHistory();
        renderLive();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private boolean isNearBottom() {
        View child = historyScroll.getChildAt(0);
        if (child == null) return true;
        int diff = child.getBottom() - (historyScroll.getScrollY() + historyScroll.getHeight());
        return diff <= dp(NEAR_BOTTOM_DP);
    }

    private void refreshHistory() {
        boolean stick = isNearBottom();
        renderHistory(stick);
    }

    /** Finalized lines in grey, then the in-progress remainder (blue, bold) as the last paragraph. */
    private void renderHistory(boolean stick) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(history.joined().replace("\n", "\n\n"));
        sb.setSpan(new ForegroundColorSpan(HISTORY_COLOR), 0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String rest = liveText == null ? "" : liveText;
        rest = SubtitleHistory.liveRemainderAgainst(history.lines(), rest).trim();
        if (!rest.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            int start = sb.length();
            sb.append((isRTL(rest) ? RLE : LRE) + rest + PDF);
            sb.setSpan(new ForegroundColorSpan(LIVE_COLOR), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        historyText.setText(sb);
        if (stick) {
            historyScroll.post(() -> historyScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void renderLive() {
        if (expanded) {
            overlayText.setVisibility(View.GONE);
            refreshHistory(); // the live remainder is the blue last paragraph of the history
            return;
        }
        String shown = liveText == null ? "" : liveText;
        overlayText.setVisibility(View.VISIBLE);
        String wrapped = (isRTL(shown) ? RLE : LRE) + shown + PDF;
        try {
            overlayText.setText(wrapped);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Could not set overlay text", e);
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void pushLayout() {
        if (overlayView.getParent() != null) {
            try {
                windowManager.updateViewLayout(overlayView, params);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Subtitle view not attached, skipping updateLayout()", e);
            }
        }
    }

    private void applyExpandedGeometry() {
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = Math.max(dp(200), screenHeight - dp(EXPANDED_BOTTOM_RESERVE_DP));
        params.y = 0;
    }

    private void setExpanded(boolean value) {
        if (value == expanded) return;
        if (value) {
            compactY = params.y;
            expanded = true;
            applyExpandedGeometry();
            overlayRoot.setPadding(basePaddingPx, basePaddingPx + statusBarHeight(),
                    basePaddingPx, basePaddingPx);
            historyScroll.setVisibility(View.VISIBLE);
            btnClear.setVisibility(View.VISIBLE);
            btnToggle.setText(R.string.overlay_collapse);
            overlayText.setTypeface(Typeface.DEFAULT_BOLD);
            overlayText.setMaxLines(Integer.MAX_VALUE);
            refreshHistory();
            historyScroll.post(() -> historyScroll.fullScroll(View.FOCUS_DOWN));
        } else {
            expanded = false;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            boolean landscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            params.width = landscape ? screenWidth / 2 : WindowManager.LayoutParams.MATCH_PARENT;
            params.y = compactY;
            overlayRoot.setPadding(basePaddingPx, basePaddingPx, basePaddingPx, basePaddingPx);
            historyScroll.setVisibility(View.GONE);
            btnClear.setVisibility(View.GONE);
            btnToggle.setText(R.string.overlay_expand);
            overlayText.setTypeface(Typeface.DEFAULT);
            overlayText.setMaxLines(4);
        }
        renderLive();
        pushLayout();
    }

    /**
     * Shows the overlay programmatically.
     */
    public static void showOverlay() {
        if (instance != null) {
            instance.resetState();
            instance.runOnUi(() -> instance.setOverlayVisible(true));
        }
    }
    /**
     * Hides the overlay programmatically.
     */
    public static void hideOverlay() {
        if (instance != null) {
            instance.runOnUi(() -> instance.setOverlayVisible(false));
        }
    }
    /**
     * Handles incoming subtitle text with minimum display duration enforcement.
     *
     * @param text new subtitle text
     */
    private void handleTextUpdate(String text) {
        if (!overlayVisible) return;

        long delay;
        synchronized (this) {
            pendingText = text;
            if (waitingTochange) return; // already scheduled
            waitingTochange = true;
            delay = Math.max(0, MINTIMESTAY + lastUpdateTime - System.currentTimeMillis());
        }
        handler.postDelayed(applyRunnable, delay);
    }
    /**
     * Applies the pending subtitle text to the overlay.
     * Adds directionality markers based on text content.
     */
    private void applyPendingSubtitle() {
        String text;
        synchronized (this) {
            waitingTochange = false;
            if (pendingText == null) return;
            lastUpdateTime = System.currentTimeMillis();
            text = pendingText;
        }

        liveText = text;
        // renderLive wraps the text with directional markers (RTL/LTR)
        renderLive();
    }

    /**
     * Resets pending text state.
     */
    private void resetState() {
        synchronized (this) {
            instance.pendingText = null;
            instance.waitingTochange = false;
        }
    }

    /**
     * Posts a runnable to the main thread.
     *
     * @param r Runnable task
     */
    private void runOnUi(Runnable r) {
        handler.post(r);
    }

    /**
     * Shows or hides the overlay view.
     *
     * @param visible true to show, false to hide
     */
    private void setOverlayVisible(boolean visible) {
        if (visible == overlayVisible) return;
        overlayVisible = visible;
        try {
            if (visible) {
                if (overlayView.getParent() == null) {
                    windowManager.addView(overlayView, params);
                }
                setExpanded(true); // default: full-screen history view
            } else {
                setExpanded(false); // turning off minimises the dialog
                if (overlayView.getParent() != null) {
                    windowManager.removeViewImmediate(overlayView);
                }
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Overlay view state changed unexpectedly", e);
        }
    }

    /**
     * Service lifecycle: called when service is created.
     * Initializes overlay, notification, screen metrics, and drag behavior.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Create notification channel and start foreground service
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        // Initialize WindowManager and inflate overlay layout
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = LayoutInflater.from(this)
                .inflate(R.layout.overlay_layout, null);
        overlayText = overlayView.findViewById(R.id.overlay_text);
        overlayRoot = overlayView.findViewById(R.id.overlay_root);
        headerRow = overlayView.findViewById(R.id.overlay_header);
        btnToggle = overlayView.findViewById(R.id.overlay_btn_toggle);
        btnClear = overlayView.findViewById(R.id.overlay_btn_clear);
        btnOff = overlayView.findViewById(R.id.overlay_btn_off);
        historyScroll = overlayView.findViewById(R.id.overlay_history_scroll);
        historyText = overlayView.findViewById(R.id.overlay_history);
        basePaddingPx = overlayRoot.getPaddingLeft();
        btnOff.setOnClickListener(v -> FloatingToggleButtonService.turnOff());
        btnToggle.setOnClickListener(v -> setExpanded(!expanded));
        btnClear.setOnClickListener(v -> {
            history.clear();
            refreshHistory();
            renderLive();
        });
        String defaultText = getString(R.string.subtitle);
        overlayText.setText(defaultText);

        // Compute screen dimensions and margins
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;

        marginPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, dm);
        int marginHorizontal = marginPx;
        int fixedW = screenW - 2 * marginHorizontal;


        // Prepare overlay layout params
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int offsetDp = 320; // below the floating on/off toggle so it stays reachable
        initialOffsetPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm);
        params.y = initialOffsetPx;
        compactY = initialOffsetPx;
        // Attach vertical drag behavior to the header row only
        makeDraggableVertical(headerRow, params, dm);
    }

    /**
     * Allows vertical dragging of the overlay within screen bounds.
     *
     * @param view overlay view
     * @param p layout parameters to update
     * @param dm display metrics
     */
    private void makeDraggableVertical(View view,
                                       WindowManager.LayoutParams p,
                                       DisplayMetrics dm) {
        view.setOnTouchListener((v, event) -> {
            if (expanded) return false;
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int h = overlayView.getHeight();
                int newY = (int) (event.getRawY() - h / 2);
                p.y = Math.max(marginPx,
                        Math.min(newY,
                                screenHeight - h - marginPx));
                compactY = p.y;
                pushLayout();
            }
            return true;
        });
    }

    /**
     * Service start command callback.
     * Ensures overlay is visible.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setOverlayVisible(true);
        return START_STICKY;
    }
    /**
     * Cleans up overlay and handler callbacks when service is destroyed.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (overlayVisible && overlayView.getParent() != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Tried to updateViewLayout but view not attached", e);
            }
        }
        if (instance != null) {
            instance.resetState();
        }
        instance = null;
    }
    /**
     * Handles configuration changes, e.g., screen rotation.
     * Adjusts overlay width and vertical position.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;

        if (expanded) {
            applyExpandedGeometry();
            overlayRoot.setPadding(basePaddingPx, basePaddingPx + statusBarHeight(),
                    basePaddingPx, basePaddingPx);
            pushLayout();
            return;
        }

        if (newConfig.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: width = half of screen, vertically clamped at bottom
            params.width = screenWidth / 2;
            overlayView.post(() -> {
                params.y = screenHeight - overlayView.getHeight() - marginPx;
                params.y = Math.max(marginPx,
                        Math.min(params.y,
                                screenHeight - overlayView.getHeight() - marginPx));
                if (overlayView.getParent() != null) {
                    try {
                        windowManager.updateViewLayout(overlayView, params);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Subtitle view not attached, skipping updateLayout()", e);
                    }
                }
            });
        } else {
            // Portrait: full width and restore initial vertical offset
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.y = initialOffsetPx;
            if (overlayView.getParent() != null) {
                try {
                    windowManager.updateViewLayout(overlayView, params);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Subtitle view not attached, skipping updateLayout()", e);
                }
            }
        }
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
    /**
     * Creates the foreground service notification.
     *
     * @return Notification instance
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("subtitles")
                .setContentText("Overlay service running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    /**
     * Creates a notification channel for Android O+ to support foreground service.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Subtitle Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}