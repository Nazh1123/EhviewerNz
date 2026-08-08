/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui;

import static com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.event.GalleryActivityEvent;
import com.hippo.ehviewer.gallery.ArchiveGalleryProvider;
import com.hippo.ehviewer.gallery.DirGalleryProvider;
import com.hippo.ehviewer.gallery.EhGalleryProvider;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.widget.GalleryGuideView;
import com.hippo.ehviewer.widget.GalleryHeader;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.ehviewer.widget.TouchThroughSeekBar;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.SimpleAdapter;
import com.hippo.lib.glview.view.GLRootView;
import com.hippo.lib.glview.image.ImageTexture;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.SystemUiHelper;
import com.hippo.widget.ColorView;
import com.hippo.lib.yorozuya.AnimationUtils;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ResourcesUtils;
import com.hippo.lib.yorozuya.SimpleAnimatorListener;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class GalleryActivity extends EhActivity implements SeekBar.OnSeekBarChangeListener,
        GalleryView.Listener, ImageTexture.PlaybackListener {

    private static final String TAG = "GalleryActivity";

    public static final String ACTION_DIR = "dir";
    public static final String ACTION_EH = "eh";

    public static final String KEY_ACTION = "action";
    public static final String KEY_FILENAME = "filename";
    public static final String KEY_URI = "uri";
    public static final String KEY_GALLERY_INFO = "gallery_info";
    public static final String DATA_IN_EVENT = "data_in_event";
    public static final String KEY_PAGE = "page";
    public static final String KEY_CURRENT_INDEX = "current_index";

    private static final long SLIDER_ANIMATION_DURING = 150;
    private static final long HIDE_SLIDER_DELAY = 3000;
    private static final long LONG_PRESS_SAVE_DEBOUNCE_MS = 1000L;
    private static final long SAVE_NOTICE_DURATION_MS = 2000L;
    private static final long SAVE_NOTICE_ENTER_DURATION_MS = 300L;
    private static final long SAVE_NOTICE_REPEAT_DURATION_MS = 320L;
    private static final long SAVE_NOTICE_EXIT_DURATION_MS = 180L;

    private static final int WRITE_REQUEST_CODE = 43;

    private String mAction;
    private String mFilename;
    private Uri mUri;
    private GalleryInfo mGalleryInfo;
    private int mPage;
    private String mCacheFileName;

    @Nullable
    private GLRootView mGLRootView;
    @Nullable
    private GalleryView mGalleryView;
    @Nullable
    private GalleryProvider2 mGalleryProvider;
    @Nullable
    private GalleryAdapter mGalleryAdapter;

    @Nullable
    private SystemUiHelper mSystemUiHelper;
    private boolean mShowSystemUi;

    @Nullable
    private ColorView mMaskView;
    @Nullable
    private View mClock;
    @Nullable
    private TextView mProgress;
    @Nullable
    private View mBattery;
    @Nullable
    private View mQuickSettingsPanel;
    @Nullable
    private ImageButton mQuickScreenOrientation;
    @Nullable
    private ImageButton mQuickReadingDirection;
    @Nullable
    private ImageButton mQuickDirectSave;
    @Nullable
    private ImageButton mQuickPageTurn;
    @Nullable
    private ImageButton mQuickAnimatedWebp;
    @Nullable
    private View mSeekBarPanel;
    @Nullable
    private ImageView mAutoTransferPanel;
    @Nullable
    private TextView mLeftText;
    @Nullable
    private TextView mRightText;
    @Nullable
    private ReversibleSeekBar mSeekBar;
    @Nullable
    private View mSaveNotice;
    @Nullable
    private ImageView mSaveNoticeIcon;
    @Nullable
    private View mAnimatedWebpPanel;
    @Nullable
    private View mAnimatedWebpControls;
    @Nullable
    private TextView mAnimatedWebpTime;
    @Nullable
    private TouchThroughSeekBar mAnimatedWebpSeek;
    @Nullable
    private ImageButton mAnimatedWebpPlayPause;
    @Nullable
    private TextView mAnimatedWebpSpeed;
    @Nullable
    private ImageButton mAnimatedWebpSequential;
    @Nullable
    private ImageTexture mAnimatedWebpTexture;
    @Nullable
    private ImageTexture mAnimatedWebpLongPressTexture;
    private float mAnimatedWebpLongPressRestoreSpeed;
    private boolean mAnimatedWebpLongPressRestorePlaying;
    private boolean mAnimatedWebpSeeking;
    private boolean mAnimatedWebpSeekAwaitingFrame;
    private boolean mAnimatedWebpWasPlayingBeforeSeek;
    private int mAnimatedWebpRequestedPosition;
    private long mAnimatedWebpLastPreviewAt;
    private boolean mAnimatedWebpTouchCandidate;
    private boolean mAnimatedWebpTouchDragging;
    private float mAnimatedWebpTouchDownX;
    private float mAnimatedWebpTouchDownY;
    private int mAnimatedWebpTouchSlop;
    @Nullable
    private ImageTexture mAnimatedWebpReloadSourceTexture;
    @Nullable
    private ColorStateList mPageSliderDefaultProgressTint;
    @Nullable
    private ColorStateList mPageSliderDefaultProgressBackgroundTint;
    @Nullable
    private ColorStateList mPageSliderDefaultSecondaryProgressTint;
    @Nullable
    private ColorStateList mPageSliderDefaultThumbTint;
    @Nullable
    private Drawable.ConstantState mPageSliderDefaultProgressDrawableState;
    @Nullable
    private Drawable.ConstantState mPageSliderDefaultThumbDrawableState;
    private boolean mPageSliderAnimatedTintApplied;

    private ObjectAnimator mSeekBarPanelAnimator;
    private ObjectAnimator mAutoTransferAnimator;
    private ObjectAnimator mQuickSettingsAnimator;
    @Nullable
    private AnimatorSet mSaveNoticeAnimator;

    private int mLayoutMode;
    private int mSize;
    private int mCurrentIndex;
    private long mLastLongPressSaveAt;
    private int mLastLongPressSaveIndex = -1;
    private int mSaveNoticeGeneration;

    private boolean canFinish = false;
    private boolean autoTransferring = false;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(3);

    private ScheduledExecutorService transferService = Executors.newSingleThreadScheduledExecutor();
    private final Handler transHandle = new Handler(Looper.getMainLooper());
    private final Handler mAnimatedWebpHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAnimatedWebpUiRunnable = new Runnable() {
        @Override
        public void run() {
            updateAnimatedWebpUi();
            mAnimatedWebpHandler.postDelayed(this, 100L);
        }
    };

    private final ValueAnimator.AnimatorUpdateListener mUpdateSliderListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            if (null != mSeekBarPanel) {
                mSeekBarPanel.requestLayout();
            }
            if (null != mAutoTransferPanel) {
                mAutoTransferPanel.requestLayout();
            }
            if (null != mQuickSettingsPanel) {
                mQuickSettingsPanel.requestLayout();
            }
        }
    };

    private final SimpleAnimatorListener mShowSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            mAutoTransferAnimator = null;
            mQuickSettingsAnimator = null;
        }
    };

    private final SimpleAnimatorListener mHideSliderListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSeekBarPanelAnimator = null;
            if (mSeekBarPanel != null) {
                mSeekBarPanel.setVisibility(View.INVISIBLE);
            }
            mAutoTransferAnimator = null;
            if (mAutoTransferPanel != null) {
                mAutoTransferPanel.setVisibility(View.INVISIBLE);
            }
            mQuickSettingsAnimator = null;
            if (mQuickSettingsPanel != null) {
                mQuickSettingsPanel.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final Runnable mHideSliderRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSeekBarPanel != null) {
                hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
                hideSlider(mQuickSettingsPanel, mQuickSettingsAnimator);
            }
        }
    };

    private final Runnable mHideSaveNoticeRunnable = this::hideSaveNotice;

    @Override
    protected int getThemeResId(int theme) {
        switch (theme) {
            case Settings.THEME_LIGHT:
            default:
                return R.style.AppTheme_Gallery;
            case Settings.THEME_DARK:
                return R.style.AppTheme_Gallery_Dark;
            case Settings.THEME_BLACK:
                return R.style.AppTheme_Gallery_Black;
        }
    }

    private void buildProvider() {
        if (mGalleryProvider != null) {
            return;
        }

        if (ACTION_DIR.equals(mAction)) {
            if (mFilename != null) {
                mGalleryProvider = new DirGalleryProvider(UniFile.fromFile(new File(mFilename)));
            }
        } else if (ACTION_EH.equals(mAction)) {
            if (mGalleryInfo != null) {
                mGalleryProvider = new EhGalleryProvider(this, mGalleryInfo);
            }
        } else if (Intent.ACTION_VIEW.equals(mAction)) {
            if (mUri != null) {
                // Only support zip now
                mGalleryProvider = new ArchiveGalleryProvider(this, mUri);
            }
        }
    }

    /**
     * eventbus 通知，用于修复跳转奔溃的问题
     *
     * @param event 通知数据对象
     */
    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onGalleryActivityEvent(GalleryActivityEvent event) {
        if (mGalleryProvider != null) {
            return;
        }
        mGalleryInfo = event.galleryInfo;
        mPage = event.pagePosition;
        buildProvider();
        onCreateView(null);
    }

    private void onInit() {
        Intent intent = getIntent();
        if (intent == null) {
            canFinish = true;
            return;
        }

        mAction = intent.getAction();
        mFilename = intent.getStringExtra(KEY_FILENAME);
        mUri = intent.getData();
        mGalleryInfo = intent.getParcelableExtra(KEY_GALLERY_INFO);
        boolean onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false);
        if (!onEvent) {
            canFinish = true;
        }
        mPage = intent.getIntExtra(KEY_PAGE, -1);
        buildProvider();
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mAction = savedInstanceState.getString(KEY_ACTION);
        mFilename = savedInstanceState.getString(KEY_FILENAME);
        mUri = savedInstanceState.getParcelable(KEY_URI);
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO);
        mPage = savedInstanceState.getInt(KEY_PAGE, -1);
        mCurrentIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX);
        buildProvider();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_ACTION, mAction);
        outState.putString(KEY_FILENAME, mFilename);
        outState.putParcelable(KEY_URI, mUri);
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo);
        }
        outState.putInt(KEY_PAGE, mPage);
        outState.putInt(KEY_CURRENT_INDEX, mCurrentIndex);
    }

    @Override
    @SuppressWarnings({"WrongConstant"})
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Settings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
        super.onCreate(savedInstanceState);
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());
        builder.detectFileUriExposure();

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
        onCreateView(savedInstanceState);
        //注册事件
        EventBus.getDefault().register(this);
    }

    private void onCreateView(@Nullable Bundle savedInstanceState) {
        if (mGalleryProvider == null) {
            if (!canFinish) {
                return;
            }
            finish();
            return;
        }
        mGalleryProvider.start();

        // Get start page
        int startPage;
        if (savedInstanceState == null) {
            startPage = mPage >= 0 ? mPage : mGalleryProvider.getStartPage();
        } else {
            startPage = mCurrentIndex;
        }

        if (!isEglAvailable()) {
            mGalleryProvider.stop();
            showGlFallbackView();
            return;
        }

        setContentView(R.layout.activity_gallery);
        mGLRootView = (GLRootView) ViewUtils.$$(this, R.id.gl_root_view);
        mGalleryAdapter = new GalleryAdapter(mGLRootView, mGalleryProvider);
        Resources resources = getResources();
        mGalleryView = new GalleryView.Builder(this, mGalleryAdapter).setListener(this).setLayoutMode(Settings.getReadingDirection()).setScaleMode(Settings.getPageScaling()).setStartPosition(Settings.getStartPosition()).setStartPage(startPage).setBackgroundColor(AttrResources.getAttrColor(this, android.R.attr.colorBackground)).setEdgeColor(AttrResources.getAttrColor(this, R.attr.colorEdgeEffect) & 0xffffff | 0x33000000).setPagerInterval(Settings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0).setScrollInterval(Settings.getShowPageInterval() ? resources.getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0).setPageMinHeight(resources.getDimensionPixelOffset(R.dimen.gallery_page_min_height)).setPageInfoInterval(resources.getDimensionPixelOffset(R.dimen.gallery_page_info_interval)).setProgressColor(ResourcesUtils.getAttrColor(this, androidx.appcompat.R.attr.colorPrimary)).setProgressSize(resources.getDimensionPixelOffset(R.dimen.gallery_progress_size)).setPageTextColor(AttrResources.getAttrColor(this, android.R.attr.textColorSecondary)).setPageTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_page_text_size)).setPageTextTypeface(Typeface.DEFAULT).setErrorTextColor(resources.getColor(R.color.red_500, null)).setErrorTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_error_text_size)).setDefaultErrorString(resources.getString(R.string.error_unknown)).setEmptyString(resources.getString(R.string.error_empty)).build();
        mGalleryView.setPageAreaDoubleTapEnabled(!Settings.getQuickPageTurn());
        mGLRootView.setContentPane(mGalleryView);
        mGLRootView.setOnGenericMotionListener(this::onGenericMotion);
        mAnimatedWebpTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        mGalleryProvider.setListener(mGalleryAdapter);
        mGalleryProvider.setGLRoot(mGLRootView);

        // System UI helper
        if (Settings.getReadingFullscreen()) {
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            mSystemUiHelper = new SystemUiHelper(this, SystemUiHelper.LEVEL_IMMERSIVE, SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES | SystemUiHelper.FLAG_IMMERSIVE_STICKY);
            mSystemUiHelper.hide();
            mShowSystemUi = false;
        }

        mMaskView = (ColorView) ViewUtils.$$(this, R.id.mask);
        mClock = ViewUtils.$$(this, R.id.clock);
        mProgress = (TextView) ViewUtils.$$(this, R.id.progress);
        mBattery = ViewUtils.$$(this, R.id.battery);
        mClock.setVisibility(Settings.getShowClock() ? View.VISIBLE : View.GONE);
        mProgress.setVisibility(Settings.getShowProgress() ? View.VISIBLE : View.GONE);
        mBattery.setVisibility(Settings.getShowBattery() ? View.VISIBLE : View.GONE);

        mQuickSettingsPanel = ViewUtils.$$(this, R.id.quick_settings_panel);
        mQuickScreenOrientation = (ImageButton) ViewUtils.$$(
                mQuickSettingsPanel, R.id.quick_screen_orientation);
        mQuickReadingDirection = (ImageButton) ViewUtils.$$(
                mQuickSettingsPanel, R.id.quick_reading_direction);
        mQuickDirectSave = (ImageButton) ViewUtils.$$(
                mQuickSettingsPanel, R.id.quick_direct_save);
        mQuickPageTurn = (ImageButton) ViewUtils.$$(
                mQuickSettingsPanel, R.id.quick_page_turn);
        mQuickAnimatedWebp = (ImageButton) ViewUtils.$$(
                mQuickSettingsPanel, R.id.quick_animated_webp);
        mQuickScreenOrientation.setOnClickListener(this::toggleQuickScreenOrientation);
        mQuickReadingDirection.setOnClickListener(this::toggleQuickReadingDirection);
        mQuickDirectSave.setOnClickListener(this::toggleQuickDirectSave);
        mQuickPageTurn.setOnClickListener(this::toggleQuickPageTurn);
        mQuickAnimatedWebp.setOnClickListener(this::toggleQuickAnimatedWebp);
        ViewCompat.setOnApplyWindowInsetsListener(mQuickSettingsPanel, (view, insets) -> {
            int statusBarInset = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()).top;
            FrameLayout.LayoutParams layoutParams =
                    (FrameLayout.LayoutParams) view.getLayoutParams();
            if (layoutParams.topMargin != statusBarInset) {
                layoutParams.topMargin = statusBarInset;
                view.setLayoutParams(layoutParams);
            }
            return insets;
        });
        updateQuickSettingsButtons();

        mSeekBarPanel = ViewUtils.$$(this, R.id.seek_bar_panel);
        mAutoTransferPanel = (ImageView) ViewUtils.$$(this, R.id.auto_transfer);
        mLeftText = (TextView) ViewUtils.$$(mSeekBarPanel, R.id.left);
        mRightText = (TextView) ViewUtils.$$(mSeekBarPanel, R.id.right);
        mSeekBar = (ReversibleSeekBar) ViewUtils.$$(mSeekBarPanel, R.id.seek_bar);
        mSeekBar.setOnSeekBarChangeListener(this);
        mPageSliderDefaultProgressTint = mSeekBar.getProgressTintList();
        mPageSliderDefaultProgressBackgroundTint =
                mSeekBar.getProgressBackgroundTintList();
        mPageSliderDefaultSecondaryProgressTint =
                mSeekBar.getSecondaryProgressTintList();
        mPageSliderDefaultThumbTint = mSeekBar.getThumbTintList();
        if (mSeekBar.getProgressDrawable() != null) {
            mPageSliderDefaultProgressDrawableState =
                    mSeekBar.getProgressDrawable().getConstantState();
        }
        if (mSeekBar.getThumb() != null) {
            mPageSliderDefaultThumbDrawableState = mSeekBar.getThumb().getConstantState();
        }
        mAutoTransferPanel.setOnClickListener(this::autoRead);

        mSaveNotice = ViewUtils.$$(this, R.id.save_notice);
        mSaveNoticeIcon = (ImageView) ViewUtils.$$(this, R.id.save_notice_icon);

        mAnimatedWebpPanel = findViewById(R.id.animated_webp_panel);
        mAnimatedWebpControls = findViewById(R.id.animated_webp_controls);
        mAnimatedWebpTime = findViewById(R.id.animated_webp_time);
        mAnimatedWebpSeek = findViewById(R.id.animated_webp_seek);
        mAnimatedWebpPlayPause = findViewById(R.id.animated_webp_play_pause);
        mAnimatedWebpSpeed = findViewById(R.id.animated_webp_speed);
        mAnimatedWebpSequential = findViewById(R.id.animated_webp_sequential);
        mAnimatedWebpPlayPause.setOnClickListener(v -> {
            ImageTexture texture = mAnimatedWebpTexture;
            if (texture != null) texture.setPlaybackPlaying(!texture.isPlaybackPlaying());
        });
        mAnimatedWebpSpeed.setOnClickListener(v -> cycleAnimatedWebpSpeed());
        mAnimatedWebpSequential.setOnClickListener(v -> {
            Settings.putAnimatedWebpAutoAdvance(!Settings.getAnimatedWebpAutoAdvance());
            updateAnimatedWebpSequentialButton();
        });
        mAnimatedWebpSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) requestAnimatedWebpSeekPreview(progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                beginAnimatedWebpSeek();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                finishAnimatedWebpSeek(seekBar.getProgress());
            }
        });
        mAnimatedWebpHandler.removeCallbacks(mAnimatedWebpUiRunnable);
        mAnimatedWebpHandler.post(mAnimatedWebpUiRunnable);

        mSize = mGalleryProvider.size();
        mCurrentIndex = startPage;
        if (mGalleryView != null) {
            mLayoutMode = mGalleryView.getLayoutMode();
        }
        updateSlider();

        // Update keep screen on
        if (Settings.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Orientation
        int orientation;
        switch (Settings.getScreenRotation()) {
            default:
            case 0:
                orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
            case 1:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case 2:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case 3:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
        }
        setRequestedOrientation(orientation);

        // Screen lightness
        setScreenLightness(Settings.getCustomScreenLightness(), Settings.getScreenLightness());

        // Cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            GalleryHeader galleryHeader = findViewById(R.id.gallery_header);
            galleryHeader.setOnApplyWindowInsetsListener((v, insets) -> {
                galleryHeader.setDisplayCutout(insets.getDisplayCutout());
                return insets;
            });
        }

        if (Settings.getGuideGallery()) {
            FrameLayout mainLayout = (FrameLayout) ViewUtils.$$(this, R.id.main);
            mainLayout.addView(new GalleryGuideView(this));
        }
    }

    private boolean isEglAvailable() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == null || display == EGL10.EGL_NO_DISPLAY) {
            return false;
        }

        int[] version = new int[2];
        if (!egl.eglInitialize(display, version)) {
            return false;
        }

        try {
            int[] numConfig = new int[1];
            return egl.eglChooseConfig(display, new int[]{EGL10.EGL_NONE}, null, 0, numConfig)
                    && numConfig[0] > 0;
        } catch (Throwable e) {
            return false;
        } finally {
            egl.eglTerminate(display);
        }
    }

    private void showGlFallbackView() {
        setContentView(R.layout.activity_gallery_fallback);
        View close = ViewUtils.$$(this, R.id.gl_fallback_close);
        close.setOnClickListener(v -> finish());
        Log.w("GalleryActivity", "EGL init failed, switch to non-GL fallback page");
        Toast.makeText(this, R.string.gallery_gl_fallback_toast, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        mAnimatedWebpHandler.removeCallbacks(mAnimatedWebpUiRunnable);
        restoreAnimatedWebpLongPressPlayback();
        if (mAnimatedWebpTexture != null) {
            mAnimatedWebpTexture.setPlaybackListener(null);
            mAnimatedWebpTexture = null;
        }
        if (!transferService.isShutdown()) {
            transferService.shutdown();
            transferService = null;
        }
        mGLRootView = null;
        mGalleryView = null;
        if (mGalleryAdapter != null) {
            mGalleryAdapter.clearUploader();
            mGalleryAdapter = null;
        }
        if (mGalleryProvider != null) {
            mGalleryProvider.setListener(null);
            mGalleryProvider.stop();
            mGalleryProvider = null;
        }

        mMaskView = null;
        mClock = null;
        mProgress = null;
        mBattery = null;
        mQuickSettingsPanel = null;
        mQuickScreenOrientation = null;
        mQuickReadingDirection = null;
        mQuickDirectSave = null;
        mQuickPageTurn = null;
        mQuickAnimatedWebp = null;
        mSeekBarPanel = null;
        mAutoTransferPanel = null;
        mLeftText = null;
        mRightText = null;
        mSeekBar = null;
        if (mSaveNotice != null) {
            mSaveNotice.removeCallbacks(mHideSaveNoticeRunnable);
        }
        mSaveNoticeGeneration++;
        cancelSaveNoticeAnimation();
        mSaveNotice = null;
        mSaveNoticeIcon = null;
        mAnimatedWebpPanel = null;
        mAnimatedWebpControls = null;
        mAnimatedWebpTime = null;
        mAnimatedWebpSeek = null;
        mAnimatedWebpPlayPause = null;
        mAnimatedWebpSpeed = null;
        mAnimatedWebpSequential = null;

        if (transferService != null && !transferService.isShutdown()) {
            transferService.shutdown();
            transferService = null;
        }

        super.onDestroy();
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
        //销毁事件
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        intent.putExtra("info", mGalleryInfo);
        setResult(LOCAL_GALLERY_INFO_CHANGE, intent);
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        restoreAnimatedWebpLongPressPlayback();
        super.onPause();

        if (mGLRootView != null) {
            mGLRootView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mGLRootView != null) {
            mGLRootView.onResume();
        }
        updateQuickSettingsButtons();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        SimpleHandler.getInstance().postDelayed(() -> {
            if (hasFocus && mSystemUiHelper != null) {
                if (mShowSystemUi) {
                    mSystemUiHelper.show();
                } else {
                    mSystemUiHelper.hide();
                }
            }
        }, 300);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mGalleryView == null) {
            return super.onKeyDown(keyCode, event);
        }
        boolean unReverse = !Settings.getReverseVolumePage();
        // Check volume
        if (Settings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT && unReverse) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            }
        }

        // Check keyboard and Dpad
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageRight();
                } else {
                    mGalleryView.pageLeft();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mGalleryView.pageLeft();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mGalleryView.pageRight();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MENU:
                onTapMenuArea();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Check volume
        if (Settings.getVolumePage()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true;
            }
        }

        // Check keyboard and Dpad
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

//    private GalleryPageView findPageByIndex(int index) {
//        if (mGalleryView != null) {
//            return mGalleryView.findPageByIndex(index);
//        } else {
//            return null;
//        }
//    }

    private void autoRead(View view) {
        autoTransferring = !autoTransferring;
        if (mAutoTransferPanel == null) {
            return;
        }

        if (!autoTransferring) {
            mAutoTransferPanel.setImageResource(R.drawable.ic_start_play_24);
            transferService.shutdown();
        } else {
            mAutoTransferPanel.setImageResource(R.drawable.ic_pause_circle);
            if (transferService.isShutdown()) {
                transferService = Executors.newSingleThreadScheduledExecutor();
            }
            long initialDelay = Settings.getStartTransferTime();
            long waitTime = initialDelay * 2L;
            try {
                transferService.scheduleWithFixedDelay(() -> transHandle.post(() -> {
                    if (mGalleryView == null) {
                        return;
                    }
                    if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }), initialDelay, waitTime, TimeUnit.MILLISECONDS);
            } catch (IllegalArgumentException ignore) {

            }
        }
    }

    public boolean onGenericMotion(View view, MotionEvent motionEvent) {
        if (mGalleryView == null) {
            return false;
        }

        if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (motionEvent.getAction() == MotionEvent.ACTION_SCROLL) {
                float scrollY = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY == 0) return false;  // wrong input
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    if (scrollY > 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                } else {
                    if (scrollY < 0) {
                        mGalleryView.pageLeft();
                    } else {
                        mGalleryView.pageRight();
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleAnimatedWebpScrubGesture(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean handleAnimatedWebpScrubGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mAnimatedWebpTouchDragging = false;
                mAnimatedWebpTouchCandidate = canStartAnimatedWebpScrub(event);
                if (mAnimatedWebpTouchCandidate) {
                    mAnimatedWebpTouchDownX = event.getX();
                    mAnimatedWebpTouchDownY = event.getY();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mAnimatedWebpTouchCandidate || event.getPointerCount() != 1) {
                    return false;
                }
                if (!mAnimatedWebpTouchDragging) {
                    float dx = event.getX() - mAnimatedWebpTouchDownX;
                    float dy = event.getY() - mAnimatedWebpTouchDownY;
                    if (Math.abs(dx) <= mAnimatedWebpTouchSlop ||
                            Math.abs(dx) <= Math.abs(dy)) {
                        return false;
                    }
                    mAnimatedWebpTouchDragging = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    beginAnimatedWebpSeek();
                }
                requestAnimatedWebpSeekPreview(animatedWebpPositionForX(event.getX()), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (mAnimatedWebpTouchDragging) {
                    finishAnimatedWebpSeek(animatedWebpPositionForX(event.getX()));
                    clearAnimatedWebpTouchGesture();
                    return true;
                }
                clearAnimatedWebpTouchGesture();
                return false;
            case MotionEvent.ACTION_CANCEL:
                if (mAnimatedWebpTouchDragging) {
                    finishAnimatedWebpSeek(mAnimatedWebpRequestedPosition);
                    clearAnimatedWebpTouchGesture();
                    return true;
                }
                clearAnimatedWebpTouchGesture();
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                clearAnimatedWebpTouchGesture();
                return false;
            default:
                return false;
        }
    }

    private boolean canStartAnimatedWebpScrub(MotionEvent event) {
        if (mAnimatedWebpTexture == null || !Settings.getAnimatedWebpAllowSeek()) {
            return false;
        }
        View decor = getWindow().getDecorView();
        if (event.getY() < decor.getHeight() * 0.75f) return false;
        if (mSeekBarPanel != null && mSeekBarPanel.getVisibility() == View.VISIBLE &&
                isPointInsideView(event.getRawX(), event.getRawY(), mSeekBarPanel)) {
            return false;
        }
        // Let the real progress bar keep normal SeekBar semantics. The rest of
        // the lower quarter is the larger, invisible scrubbing target.
        return mAnimatedWebpSeek == null || !isPointInsideView(
                event.getRawX(), event.getRawY(), mAnimatedWebpSeek);
    }

    private static boolean isPointInsideView(float rawX, float rawY, View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth() &&
                rawY >= location[1] && rawY < location[1] + view.getHeight();
    }

    private int animatedWebpPositionForX(float x) {
        ImageTexture texture = mAnimatedWebpTexture;
        View decor = getWindow().getDecorView();
        if (texture == null || decor.getWidth() <= 0) return 0;
        float fraction = Math.max(0f, Math.min(1f, x / decor.getWidth()));
        return Math.round(Math.max(0, texture.getPlaybackDuration() - 1) * fraction);
    }

    private void clearAnimatedWebpTouchGesture() {
        mAnimatedWebpTouchCandidate = false;
        mAnimatedWebpTouchDragging = false;
    }

    @SuppressLint("SetTextI18n")
    private void updateProgress() {
        if (mProgress == null) {
            return;
        }
        if (mSize <= 0 || mCurrentIndex < 0) {
            mProgress.setText(null);
        } else {
            mProgress.setText((mCurrentIndex + 1) + "/" + mSize);
        }
    }

    private void updateQuickSettingsButtons() {
        if (mQuickScreenOrientation == null || mQuickReadingDirection == null
                || mQuickDirectSave == null || mQuickPageTurn == null
                || mQuickAnimatedWebp == null) {
            return;
        }

        int screenRotation = Settings.getScreenRotation();
        boolean landscape;
        if (screenRotation == 1) {
            landscape = false;
        } else if (screenRotation == 2) {
            landscape = true;
        } else {
            landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
        }
        mQuickScreenOrientation.setImageResource(landscape
                ? R.drawable.v_screen_landscape_x24
                : R.drawable.v_screen_portrait_x24);

        int readingDirection = Settings.getReadingDirection();
        switch (readingDirection) {
            case GalleryView.LAYOUT_RIGHT_TO_LEFT:
                mQuickReadingDirection.setImageResource(R.drawable.v_arrow_left_x24);
                mQuickReadingDirection.setContentDescription(
                        getString(R.string.settings_read_reading_direction_right_to_Left));
                break;
            case GalleryView.LAYOUT_TOP_TO_BOTTOM:
                mQuickReadingDirection.setImageResource(R.drawable.v_arrow_down_x24);
                mQuickReadingDirection.setContentDescription(
                        getString(R.string.settings_read_reading_direction_top_to_bottom));
                break;
            case GalleryView.LAYOUT_LEFT_TO_RIGHT:
            default:
                mQuickReadingDirection.setImageResource(R.drawable.v_arrow_right_x24);
                mQuickReadingDirection.setContentDescription(
                        getString(R.string.settings_read_reading_direction_left_to_right));
                break;
        }

        updateQuickToggleButton(mQuickDirectSave, Settings.getDirectSave());
        updateQuickToggleButton(mQuickPageTurn, Settings.getQuickPageTurn());
        updateQuickToggleButton(mQuickAnimatedWebp,
                Settings.getExperimentalAnimatedWebpEnabled());
    }

    private static void updateQuickToggleButton(ImageButton button, boolean enabled) {
        button.setSelected(enabled);
        button.setImageAlpha(enabled ? 255 : 115);
    }

    private void keepQuickSettingsVisible() {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
        SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
    }

    private void toggleQuickScreenOrientation(View view) {
        int screenRotation = Settings.getScreenRotation();
        boolean currentlyLandscape;
        if (screenRotation == 1) {
            currentlyLandscape = false;
        } else if (screenRotation == 2) {
            currentlyLandscape = true;
        } else {
            currentlyLandscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
        }

        boolean useLandscape = !currentlyLandscape;
        Settings.putScreenRotation(useLandscape ? 2 : 1);
        updateQuickSettingsButtons();
        keepQuickSettingsVisible();
        setRequestedOrientation(useLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    private void toggleQuickReadingDirection(View view) {
        if (mGalleryView == null) {
            return;
        }
        int currentDirection = Settings.getReadingDirection();
        int newDirection;
        if (currentDirection == GalleryView.LAYOUT_LEFT_TO_RIGHT) {
            newDirection = GalleryView.LAYOUT_RIGHT_TO_LEFT;
        } else {
            // The quick action intentionally toggles only the two horizontal modes. Entering it
            // from top-to-bottom (or an invalid persisted value) starts at left-to-right.
            newDirection = GalleryView.LAYOUT_LEFT_TO_RIGHT;
        }
        Settings.putReadingDirection(newDirection);
        mLayoutMode = newDirection;
        mGalleryView.setLayoutMode(newDirection);
        updateSlider();
        updateQuickSettingsButtons();
        keepQuickSettingsVisible();
    }

    private void toggleQuickDirectSave(View view) {
        Settings.putDirectSave(!Settings.getDirectSave());
        updateQuickSettingsButtons();
        keepQuickSettingsVisible();
    }

    private void toggleQuickPageTurn(View view) {
        boolean enabled = !Settings.getQuickPageTurn();
        Settings.putQuickPageTurn(enabled);
        if (mGalleryView != null) {
            mGalleryView.setPageAreaDoubleTapEnabled(!enabled);
        }
        updateQuickSettingsButtons();
        keepQuickSettingsVisible();
    }

    private void toggleQuickAnimatedWebp(View view) {
        Settings.putExperimentalAnimatedWebpEnabled(
                !Settings.getExperimentalAnimatedWebpEnabled());
        updateQuickSettingsButtons();
        if (!reloadCurrentAnimatedWebpForDecoderModeIfNeeded()) {
            updateAnimatedWebpUi();
        }
        keepQuickSettingsVisible();
    }

    @SuppressLint("SetTextI18n")
    private void updateSlider() {
        if (mSeekBar == null || mRightText == null || mLeftText == null || mSize <= 0 || mCurrentIndex < 0) {
            return;
        }

        TextView start;
        TextView end;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
            end = mLeftText;
            mSeekBar.setReverse(true);
        } else {
            start = mLeftText;
            end = mRightText;
            mSeekBar.setReverse(false);
        }
        start.setText(Integer.toString(mCurrentIndex + 1));
        end.setText(Integer.toString(mSize));
        mSeekBar.setMax(mSize - 1);
        mSeekBar.setProgress(mCurrentIndex);
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        TextView start;
        if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
            start = mRightText;
        } else {
            start = mLeftText;
        }
        if (fromUser && null != start) {
            start.setText(Integer.toString(progress + 1));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
        int progress = seekBar.getProgress();
        if (progress != mCurrentIndex && null != mGalleryView) {
            mGalleryView.setCurrentPage(progress);
        }
    }

    @Override
    public void onUpdateCurrentIndex(int index) {
        if (null != mGalleryProvider) {
            mGalleryProvider.putStartPage(index);
        }

        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_CURRENT_INDEX, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapSliderArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_SLIDER_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapMenuArea() {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_MENU_AREA, 0);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onTapErrorText(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setData(NotifyTask.KEY_TAP_ERROR_TEXT, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onLongPressPage(int index, boolean nextPageArea,
                                boolean previousPageArea) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        int key = nextPageArea
                ? NotifyTask.KEY_LONG_PRESS_NEXT_PAGE_AREA
                : previousPageArea
                        ? NotifyTask.KEY_LONG_PRESS_PREVIOUS_PAGE_AREA
                        : NotifyTask.KEY_LONG_PRESS_PAGE;
        task.setData(key, index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onAutoTransferDone() {
        if (autoTransferring) {
            autoRead(mAutoTransferPanel);
        }
    }

//    @Override
//    public boolean onGenericMotionEvent(MotionEvent event) {
//        //The input source is a pointing device associated with a display.
//        //输入源为可显示的指针设备，如：mouse pointing device(鼠标指针),stylus pointing device(尖笔设备)
//        if (0 != (event.getSource() & InputDevice.SOURCE_CLASS_POINTER)) {
//            switch (event.getAction()) {
//                // process the scroll wheel movement...处理滚轮事件
//                case MotionEvent.ACTION_SCROLL:
//                    //获得垂直坐标上的滚动方向,也就是滚轮向下滚
//                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
//                        Log.i("fortest::onGenericMotionEvent", "down");
//                    }
//                    //获得垂直坐标上的滚动方向,也就是滚轮向上滚
//                    else {
//                        Log.i("fortest::onGenericMotionEvent", "up");
//                    }
//                    return true;
//            }
//        }
//        return super.onGenericMotionEvent(event);
//    }


    private void showSlider(View sliderPanel, ObjectAnimator animator) {
        if (null != animator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel) {
            sliderPanel.setTranslationX(sliderPanel.getWidth());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", 0.0f);
        } else if (sliderPanel == mQuickSettingsPanel) {
            sliderPanel.setTranslationY(-sliderPanel.getHeight());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", 0.0f);
        } else {
            sliderPanel.setTranslationY(sliderPanel.getHeight());
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", 0.0f);
        }

        sliderPanel.setVisibility(View.VISIBLE);


        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mShowSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.show();
            mShowSystemUi = true;
        }
    }


    private void hideSlider(View sliderPanel, ObjectAnimator animator) {
        if (null != animator) {
            animator.cancel();
        }
        if (sliderPanel == mAutoTransferPanel) {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationX", sliderPanel.getWidth());
        } else if (sliderPanel == mQuickSettingsPanel) {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", -sliderPanel.getHeight());
        } else {
            animator = ObjectAnimator.ofFloat(sliderPanel, "translationY", sliderPanel.getHeight());
        }

        animator.setDuration(SLIDER_ANIMATION_DURING);
        animator.setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR);
        animator.addUpdateListener(mUpdateSliderListener);
        animator.addListener(mHideSliderListener);
        animator.start();

        if (null != mSystemUiHelper) {
            mSystemUiHelper.hide();
            mShowSystemUi = false;
        }
    }

    /**
     * @param lightness 0 - 200
     */
    private void setScreenLightness(boolean enable, int lightness) {
        if (null == mMaskView) {
            return;
        }

        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (enable) {
            lightness = MathUtils.clamp(lightness, 0, 200);
            if (lightness > 100) {
                mMaskView.setColor(0);
                // Avoid BRIGHTNESS_OVERRIDE_OFF,
                // screen may be off when lp.screenBrightness is 0.0f
                lp.screenBrightness = Math.max((lightness - 100) / 100.0f, 0.01f);
            } else {
                mMaskView.setColor(MathUtils.lerp(0xde, 0x00, lightness / 100.0f) << 24);
                lp.screenBrightness = 0.01f;
            }
        } else {
            mMaskView.setColor(0);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        w.setAttributes(lp);
    }

    private void shareImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        File dir = AppConfig.getExternalTempDir();
        if (null == dir) {
            Toast.makeText(this, R.string.error_cant_create_temp_file, Toast.LENGTH_SHORT).show();
            return;
        }
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            Toast.makeText(this, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }


        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(filename));
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "image/jpeg";
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, file.getUri());
        intent.setType(mimeType);

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_image)));
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(this, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        UniFile configuredDir = Settings.getConfiguredManualImageSaveLocation();
        UniFile defaultDir = UniFile.fromFile(AppConfig.getExternalImageDir());
        UniFile effectiveDir = configuredDir != null ? configuredDir : defaultDir;
        if (effectiveDir == null) {
            showSaveErrorNotice(getText(R.string.error_cant_save_image));
            return;
        }

        UniFile file = saveImageInDirectory(page, effectiveDir);
        // A persisted SAF grant can be revoked while the reader is open. Keep the historical
        // EhViewer/image directory as a last-resort destination for this manual save only.
        if (file == null && configuredDir != null && defaultDir != null
                && !configuredDir.getUri().equals(defaultDir.getUri())) {
            file = saveImageInDirectory(page, defaultDir);
        }
        if (file == null) {
            showSaveErrorNotice(getText(R.string.error_cant_save_image));
            return;
        }

        showSaveNotice(getString(R.string.image_saved, file.getUri()));

        // Sync media store
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, file.getUri()));
    }

    private void showSaveNotice(CharSequence message) {
        View notice = mSaveNotice;
        ImageView icon = mSaveNoticeIcon;
        if (notice == null || icon == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        boolean repeated = notice.getVisibility() == View.VISIBLE && notice.getAlpha() > 0.0f;

        mSaveNoticeGeneration++;
        notice.removeCallbacks(mHideSaveNoticeRunnable);
        cancelSaveNoticeAnimation();
        notice.setVisibility(View.VISIBLE);
        notice.setContentDescription(message);
        notice.announceForAccessibility(message);

        AnimatorSet animator = new AnimatorSet();
        if (repeated) {
            ObjectAnimator alpha = ObjectAnimator.ofFloat(
                    notice, View.ALPHA, notice.getAlpha(), 1.0f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                    notice, View.SCALE_X, notice.getScaleX(), 1.16f, 0.96f, 1.0f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                    notice, View.SCALE_Y, notice.getScaleY(), 1.16f, 0.96f, 1.0f);
            ObjectAnimator translationX = ObjectAnimator.ofFloat(
                    notice, View.TRANSLATION_X, notice.getTranslationX(), 0.0f);
            ObjectAnimator translationY = ObjectAnimator.ofFloat(
                    notice, View.TRANSLATION_Y, notice.getTranslationY(),
                    -4.0f * density, 0.0f);
            ObjectAnimator iconAlpha = ObjectAnimator.ofFloat(
                    icon, View.ALPHA, icon.getAlpha(), 0.92f, 0.5f);
            animator.playTogether(alpha, scaleX, scaleY, translationX,
                    translationY, iconAlpha);
            animator.setDuration(SAVE_NOTICE_REPEAT_DURATION_MS);
        } else {
            notice.setAlpha(0.0f);
            notice.setScaleX(0.76f);
            notice.setScaleY(0.76f);
            notice.setTranslationX(10.0f * density);
            notice.setTranslationY(10.0f * density);
            icon.setAlpha(0.5f);

            ObjectAnimator alpha = ObjectAnimator.ofFloat(notice, View.ALPHA, 0.0f, 1.0f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                    notice, View.SCALE_X, 0.76f, 1.08f, 1.0f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                    notice, View.SCALE_Y, 0.76f, 1.08f, 1.0f);
            ObjectAnimator translationX = ObjectAnimator.ofFloat(
                    notice, View.TRANSLATION_X, 10.0f * density, 0.0f);
            ObjectAnimator translationY = ObjectAnimator.ofFloat(
                    notice, View.TRANSLATION_Y, 10.0f * density, 0.0f);
            animator.playTogether(alpha, scaleX, scaleY, translationX, translationY);
            animator.setDuration(SAVE_NOTICE_ENTER_DURATION_MS);
        }
        animator.setInterpolator(AnimationUtils.SLOW_FAST_SLOW_INTERPOLATOR);
        mSaveNoticeAnimator = animator;
        animator.start();
        notice.postDelayed(mHideSaveNoticeRunnable, SAVE_NOTICE_DURATION_MS);
    }

    private void showSaveErrorNotice(CharSequence message) {
        hideSaveNotice();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideSaveNotice() {
        View notice = mSaveNotice;
        if (notice == null || notice.getVisibility() != View.VISIBLE) {
            return;
        }

        notice.removeCallbacks(mHideSaveNoticeRunnable);
        cancelSaveNoticeAnimation();
        final int generation = mSaveNoticeGeneration;
        float density = getResources().getDisplayMetrics().density;
        AnimatorSet animator = new AnimatorSet();
        animator.playTogether(
                ObjectAnimator.ofFloat(notice, View.ALPHA, notice.getAlpha(), 0.0f),
                ObjectAnimator.ofFloat(notice, View.SCALE_X, notice.getScaleX(), 0.84f),
                ObjectAnimator.ofFloat(notice, View.SCALE_Y, notice.getScaleY(), 0.84f),
                ObjectAnimator.ofFloat(notice, View.TRANSLATION_X,
                        notice.getTranslationX(), 6.0f * density),
                ObjectAnimator.ofFloat(notice, View.TRANSLATION_Y,
                        notice.getTranslationY(), 6.0f * density));
        animator.setDuration(SAVE_NOTICE_EXIT_DURATION_MS);
        animator.setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (generation == mSaveNoticeGeneration && mSaveNotice == notice) {
                    notice.setVisibility(View.GONE);
                }
            }
        });
        mSaveNoticeAnimator = animator;
        animator.start();
    }

    private void cancelSaveNoticeAnimation() {
        AnimatorSet animator = mSaveNoticeAnimator;
        mSaveNoticeAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
    }

    @Nullable
    private UniFile saveImageInDirectory(int page, @NonNull UniFile dir) {
        try {
            return mGalleryProvider != null
                    ? mGalleryProvider.save(
                            page, dir, mGalleryProvider.getImageFilename(page))
                    : null;
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Log.w(TAG, "Failed to save image to " + dir.getUri(), e);
            return null;
        }
    }

    private void saveImageTo(int page) {
        if (null == mGalleryProvider) {
            return;
        }
        File dir = getCacheDir();
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            showSaveErrorNotice(getText(R.string.error_cant_save_image));
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            showSaveErrorNotice(getText(R.string.error_cant_save_image));
            return;
        }
        mCacheFileName = filename;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(intent, WRITE_REQUEST_CODE);
//            registerForActivityResult(intent, WRITE_REQUEST_CODE);
//            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::saveImageDats)
//                    .launch(intent);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(this, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == WRITE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (resultData != null) {
                Uri uri = resultData.getData();
                String filepath = getCacheDir() + "/" + mCacheFileName;
                File cacheFile = new File(filepath);

                InputStream is = null;
                OutputStream os = null;
                ContentResolver resolver = getContentResolver();

                try {
                    is = new FileInputStream(cacheFile);
                    os = resolver.openOutputStream(uri);
                    IOUtils.copy(is, os);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    IOUtils.closeQuietly(is);
                    IOUtils.closeQuietly(os);
                }

                cacheFile.delete();

                showSaveNotice(getString(R.string.image_saved, uri.getPath()));
                // Sync media store
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
            }
        }
    }

    private void saveImageDats(ActivityResult result) {
        if (result == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }
        Intent resultData = result.getData();
        if (resultData != null) {
            Uri uri = resultData.getData();
            String filepath = getCacheDir() + "/" + mCacheFileName;
            File cacheFile = new File(filepath);

            InputStream is = null;
            OutputStream os = null;
            ContentResolver resolver = getContentResolver();

            try {
                is = new FileInputStream(cacheFile);
                os = resolver.openOutputStream(uri);
                IOUtils.copy(is, os);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }

            boolean deleted = cacheFile.delete();
            if (!deleted) {
                cacheFile.deleteOnExit();
            }

            showSaveNotice(getString(R.string.image_saved, uri.getPath()));
            // Sync media store
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
        }
    }


    private void showPageDialog(final int page) {
        Resources resources = GalleryActivity.this.getResources();
        AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
        builder.setTitle(resources.getString(R.string.page_menu_title, page + 1));

        final CharSequence[] items;
        items = new CharSequence[]{getString(R.string.page_menu_refresh), getString(R.string.page_menu_share), getString(R.string.page_menu_save), getString(R.string.page_menu_save_to)};
        pageDialogListener(builder, items, page);
        builder.show();
    }

    private void pageDialogListener(AlertDialog.Builder builder, CharSequence[] items, int page) {
        builder.setItems(items, (dialog, which) -> {
            if (mGalleryProvider == null) {
                return;
            }

            switch (which) {
                case 0: // Refresh
                    mGalleryProvider.removeCache(page);
                    mGalleryProvider.forceRequest(page);
                    break;
                case 1: // Share
                    shareImage(page);
                    break;
                case 2: // Save
                    saveImage(page);
                    break;
                case 3: // Save to
                    saveImageTo(page);
                    break;
            }
        });
    }

    private class GalleryMenuHelper implements DialogInterface.OnClickListener {

        private final View mView;
        private final Spinner mScreenRotation;
        private final Spinner mReadingDirection;
        private final Spinner mScaleMode;
        private final Spinner mStartPosition;
        private final SeekBar mStartTransferTime;
        private final EditText mStartTransferTimeInput;
        private final SwitchCompat mDirectSave;
        private final SwitchCompat mLongPressSaveTurnPage;
        private final SwitchCompat mQuickPageTurn;
        private final SwitchCompat mKeepScreenOn;
        private final SwitchCompat mShowClock;
        private final SwitchCompat mShowProgress;
        private final SwitchCompat mShowBattery;
        private final SwitchCompat mShowPageInterval;
        private final SwitchCompat mVolumePage;
        private final SwitchCompat mReverseVolumePage;
        private final SwitchCompat mReadingFullscreen;
        private final SwitchCompat mCustomScreenLightness;
        private final SeekBar mScreenLightness;
        private final SwitchCompat mAnimatedWebpEnabled;
        private final View mAnimatedWebpSettings;
        private final SwitchCompat mAnimatedWebpShowTime;
        private final SwitchCompat mAnimatedWebpAllowSeek;
        private final SwitchCompat mAnimatedWebpAutoAdvance;
        private final SwitchCompat mAnimatedWebpAutoTransferButton;
        private final EditText mAnimatedWebpLongPressSpeed;

        @SuppressLint("InflateParams")
        public GalleryMenuHelper(Context context) {
            mView = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_menu, null);
            mScreenRotation = mView.findViewById(R.id.screen_rotation);
            mReadingDirection = mView.findViewById(R.id.reading_direction);
            mScaleMode = mView.findViewById(R.id.page_scaling);
            mStartPosition = mView.findViewById(R.id.start_position);
            mStartTransferTime = mView.findViewById(R.id.start_transfer_time);
            mStartTransferTimeInput = mView.findViewById(R.id.start_transfer_time_input);
            mDirectSave = mView.findViewById(R.id.direct_save);
            mLongPressSaveTurnPage = mView.findViewById(R.id.long_press_save_turn_page);
            mQuickPageTurn = mView.findViewById(R.id.quick_page_turn);
            mKeepScreenOn = mView.findViewById(R.id.keep_screen_on);
            mShowClock = mView.findViewById(R.id.show_clock);
            mShowProgress = mView.findViewById(R.id.show_progress);
            mShowBattery = mView.findViewById(R.id.show_battery);
            mShowPageInterval = mView.findViewById(R.id.show_page_interval);
            mVolumePage = mView.findViewById(R.id.volume_page);
            mReverseVolumePage = mView.findViewById(R.id.reverse_volume_page);
            mReadingFullscreen = mView.findViewById(R.id.reading_fullscreen);
            mCustomScreenLightness = mView.findViewById(R.id.custom_screen_lightness);
            mScreenLightness = mView.findViewById(R.id.screen_lightness);
            mAnimatedWebpEnabled = mView.findViewById(R.id.animated_webp_enabled);
            mAnimatedWebpSettings = mView.findViewById(R.id.animated_webp_settings);
            mAnimatedWebpShowTime = mView.findViewById(R.id.animated_webp_show_time);
            mAnimatedWebpAllowSeek = mView.findViewById(R.id.animated_webp_allow_seek);
            mAnimatedWebpAutoAdvance = mView.findViewById(R.id.animated_webp_auto_advance);
            mAnimatedWebpAutoTransferButton =
                    mView.findViewById(R.id.animated_webp_auto_transfer_button);
            mAnimatedWebpLongPressSpeed =
                    mView.findViewById(R.id.animated_webp_long_press_speed);

            mScreenRotation.setSelection(Settings.getScreenRotation());
            mReadingDirection.setSelection(Settings.getReadingDirection());
            mScaleMode.setSelection(Settings.getPageScaling());
            mStartPosition.setSelection(Settings.getStartPosition());
            configureStartTransferTime();
            mDirectSave.setChecked(Settings.getDirectSave());
            mLongPressSaveTurnPage.setChecked(Settings.getLongPressSaveTurnPage());
            mQuickPageTurn.setChecked(Settings.getQuickPageTurn());
            mKeepScreenOn.setChecked(Settings.getKeepScreenOn());
            mShowClock.setChecked(Settings.getShowClock());
            mShowProgress.setChecked(Settings.getShowProgress());
            mShowBattery.setChecked(Settings.getShowBattery());
            mShowPageInterval.setChecked(Settings.getShowPageInterval());
            mVolumePage.setChecked(Settings.getVolumePage());
            mReverseVolumePage.setChecked(Settings.getReverseVolumePage());
            mReadingFullscreen.setChecked(Settings.getReadingFullscreen());
            mCustomScreenLightness.setChecked(Settings.getCustomScreenLightness());
            mScreenLightness.setProgress(Settings.getScreenLightness());
            mScreenLightness.setEnabled(Settings.getCustomScreenLightness());
            mAnimatedWebpEnabled.setChecked(Settings.getExperimentalAnimatedWebpEnabled());
            mAnimatedWebpShowTime.setChecked(Settings.getAnimatedWebpShowTime());
            mAnimatedWebpAllowSeek.setChecked(Settings.getAnimatedWebpAllowSeek());
            mAnimatedWebpAutoAdvance.setChecked(Settings.getAnimatedWebpAutoAdvance());
            mAnimatedWebpAutoTransferButton.setChecked(
                    Settings.getAnimatedWebpAutoTransferButton());
            setAnimatedWebpLongPressSpeedInput(
                    Settings.getAnimatedWebpLongPressSpeed());
            mAnimatedWebpLongPressSpeed.setOnFocusChangeListener((view, hasFocus) -> {
                if (!hasFocus) {
                    setAnimatedWebpLongPressSpeedInput(
                            getAnimatedWebpLongPressSpeedInput());
                }
            });
            updateAnimatedWebpSettingsEnabled(mAnimatedWebpEnabled.isChecked());

            mDirectSave.setOnCheckedChangeListener(this::onDirectSaveChange);
            mVolumePage.setOnCheckedChangeListener(this::onVolumePageChange);

            mLongPressSaveTurnPage.setVisibility(
                    Settings.getDirectSave() ? View.VISIBLE : View.GONE);

            if (Settings.getVolumePage()) {
                mReverseVolumePage.setVisibility(View.VISIBLE);

            } else {
                mReverseVolumePage.setVisibility(View.GONE);
            }

            mCustomScreenLightness.setOnCheckedChangeListener((buttonView, isChecked) -> mScreenLightness.setEnabled(isChecked));
            mAnimatedWebpEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                    updateAnimatedWebpSettingsEnabled(isChecked));
        }

        private void updateAnimatedWebpSettingsEnabled(boolean enabled) {
            mAnimatedWebpSettings.setVisibility(View.VISIBLE);
            mAnimatedWebpSettings.setAlpha(enabled ? 1.0f : 0.5f);
            mAnimatedWebpShowTime.setEnabled(enabled);
            mAnimatedWebpAllowSeek.setEnabled(enabled);
            mAnimatedWebpAutoAdvance.setEnabled(enabled);
            mAnimatedWebpAutoTransferButton.setEnabled(enabled);
            mAnimatedWebpLongPressSpeed.setEnabled(enabled);
        }

        private float getAnimatedWebpLongPressSpeedInput() {
            String normalized = Settings.normalizeAnimatedWebpLongPressSpeed(
                    mAnimatedWebpLongPressSpeed.getText().toString());
            if (normalized == null) {
                return Settings.getAnimatedWebpLongPressSpeed();
            }
            return Float.parseFloat(normalized);
        }

        private void setAnimatedWebpLongPressSpeedInput(float speed) {
            String value = Settings.normalizeAnimatedWebpLongPressSpeed(
                    String.format(Locale.US, "%.1f", speed));
            if (value == null) value = "2.0";
            if (!value.contentEquals(mAnimatedWebpLongPressSpeed.getText())) {
                mAnimatedWebpLongPressSpeed.setText(value);
                mAnimatedWebpLongPressSpeed.setSelection(value.length());
            }
        }

        private void configureStartTransferTime() {
            int transferTime = Settings.getStartTransferTime();
            mStartTransferTime.setMax(
                    Settings.startTransferTimeToProgress(
                            Settings.MAX_START_TRANSFER_SLIDER_TIME_MS));
            mStartTransferTime.setProgress(
                    Settings.startTransferTimeToProgress(transferTime));
            setTransferTimeInput(transferTime);

            mStartTransferTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        setTransferTimeInput(Settings.startTransferProgressToTime(progress));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            mStartTransferTimeInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    Integer transferTime = parseTransferTime(s.toString());
                    if (transferTime != null) {
                        mStartTransferTime.setProgress(
                                Settings.startTransferTimeToProgress(transferTime));
                    }
                }
            });
            mStartTransferTimeInput.setOnFocusChangeListener((view, hasFocus) -> {
                if (!hasFocus) {
                    setTransferTimeInput(getTransferTimeInput());
                }
            });
        }

        @Nullable
        private Integer parseTransferTime(String value) {
            if (TextUtils.isEmpty(value)) {
                return null;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private int getTransferTimeInput() {
            Integer transferTime = parseTransferTime(mStartTransferTimeInput.getText().toString());
            if (transferTime == null) {
                return Settings.getStartTransferTime();
            }
            return MathUtils.clamp(transferTime,
                    Settings.MIN_START_TRANSFER_TIME_MS, Settings.MAX_START_TRANSFER_TIME_MS);
        }

        private void setTransferTimeInput(int transferTime) {
            String value = Integer.toString(transferTime);
            if (!value.contentEquals(mStartTransferTimeInput.getText())) {
                mStartTransferTimeInput.setText(value);
                mStartTransferTimeInput.setSelection(value.length());
            }
        }

        private void onVolumePageChange(CompoundButton compoundButton, boolean b) {
            if (compoundButton.isChecked()) {
                mReverseVolumePage.setVisibility(View.VISIBLE);
            } else {
                mReverseVolumePage.setVisibility(View.GONE);
            }
        }

        private void onDirectSaveChange(CompoundButton compoundButton, boolean checked) {
            mLongPressSaveTurnPage.setVisibility(checked ? View.VISIBLE : View.GONE);
        }

        public View getView() {
            return mView;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mGalleryView == null) {
                return;
            }

            int screenRotation = mScreenRotation.getSelectedItemPosition();
            int layoutMode = GalleryView.sanitizeLayoutMode(mReadingDirection.getSelectedItemPosition());
            int scaleMode = GalleryView.sanitizeScaleMode(mScaleMode.getSelectedItemPosition());
            int startPosition = GalleryView.sanitizeStartPosition(mStartPosition.getSelectedItemPosition());
            boolean directSave = mDirectSave.isChecked();
            boolean longPressSaveTurnPage = mLongPressSaveTurnPage.isChecked();
            boolean quickPageTurn = mQuickPageTurn.isChecked();
            boolean keepScreenOn = mKeepScreenOn.isChecked();
            boolean showClock = mShowClock.isChecked();
            boolean showProgress = mShowProgress.isChecked();
            boolean showBattery = mShowBattery.isChecked();
            boolean showPageInterval = mShowPageInterval.isChecked();
            boolean volumePage = mVolumePage.isChecked();
            boolean reverseVolumePage = mReverseVolumePage.isChecked();
            boolean readingFullscreen = mReadingFullscreen.isChecked();
            boolean customScreenLightness = mCustomScreenLightness.isChecked();
            boolean animatedWebpEnabled = mAnimatedWebpEnabled.isChecked();
            boolean animatedWebpShowTime = mAnimatedWebpShowTime.isChecked();
            boolean animatedWebpAllowSeek = mAnimatedWebpAllowSeek.isChecked();
            boolean animatedWebpAutoAdvance = mAnimatedWebpAutoAdvance.isChecked();
            boolean animatedWebpAutoTransferButton =
                    mAnimatedWebpAutoTransferButton.isChecked();
            float animatedWebpLongPressSpeed =
                    getAnimatedWebpLongPressSpeedInput();

            int screenLightness = mScreenLightness.getProgress();
            int transferTime = getTransferTimeInput();

            boolean oldReadingFullscreen = Settings.getReadingFullscreen();

            Settings.putScreenRotation(screenRotation);
            Settings.putReadingDirection(layoutMode);
            Settings.putPageScaling(scaleMode);
            Settings.putStartPosition(startPosition);
            Settings.putStartTransferTime(transferTime);
            Settings.putDirectSave(directSave);
            Settings.putLongPressSaveTurnPage(longPressSaveTurnPage);
            Settings.putQuickPageTurn(quickPageTurn);
            Settings.putKeepScreenOn(keepScreenOn);
            Settings.putShowClock(showClock);
            Settings.putShowProgress(showProgress);
            Settings.putShowBattery(showBattery);
            Settings.putShowPageInterval(showPageInterval);
            Settings.putVolumePage(volumePage);
            Settings.putReadingFullscreen(readingFullscreen);
            Settings.putCustomScreenLightness(customScreenLightness);
            Settings.putScreenLightness(screenLightness);
            Settings.putReverseVolumePage(reverseVolumePage);
            Settings.putExperimentalAnimatedWebpEnabled(animatedWebpEnabled);
            Settings.putAnimatedWebpShowTime(animatedWebpShowTime);
            Settings.putAnimatedWebpAllowSeek(animatedWebpAllowSeek);
            Settings.putAnimatedWebpAutoAdvance(animatedWebpAutoAdvance);
            Settings.putAnimatedWebpAutoTransferButton(animatedWebpAutoTransferButton);
            Settings.putAnimatedWebpLongPressSpeed(animatedWebpLongPressSpeed);
            if (!volumePage) {
                mReverseVolumePage.setVisibility(View.GONE);
            } else {
                mReverseVolumePage.setVisibility(View.VISIBLE);
            }

            int orientation;
            switch (screenRotation) {
                default:
                case 0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                    break;
                case 1:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                    break;
                case 2:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                    break;
                case 3:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                    break;
            }
            setRequestedOrientation(orientation);
            mGalleryView.setLayoutMode(layoutMode);
            mGalleryView.setScaleMode(scaleMode);
            mGalleryView.setStartPosition(startPosition);
            mGalleryView.setPageAreaDoubleTapEnabled(!quickPageTurn);
            if (keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if (mClock != null) {
                mClock.setVisibility(showClock ? View.VISIBLE : View.GONE);
            }
            if (mProgress != null) {
                mProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            }
            if (mBattery != null) {
                mBattery.setVisibility(showBattery ? View.VISIBLE : View.GONE);
            }
            mGalleryView.setPagerInterval(showPageInterval ? getResources().getDimensionPixelOffset(R.dimen.gallery_pager_interval) : 0);
            mGalleryView.setScrollInterval(showPageInterval ? getResources().getDimensionPixelOffset(R.dimen.gallery_scroll_interval) : 0);
            setScreenLightness(customScreenLightness, screenLightness);

            // Update slider
            mLayoutMode = layoutMode;
            updateSlider();
            updateQuickSettingsButtons();
            if (!reloadCurrentAnimatedWebpForDecoderModeIfNeeded()) {
                updateAnimatedWebpUi();
            }

            if (oldReadingFullscreen != readingFullscreen) {
                recreate();
            }
        }
    }

    private void beginAnimatedWebpSeek() {
        ImageTexture texture = mAnimatedWebpTexture;
        if (texture == null || !Settings.getAnimatedWebpAllowSeek()) return;
        if (!mAnimatedWebpSeeking) {
            mAnimatedWebpSeeking = true;
            mAnimatedWebpSeekAwaitingFrame = false;
            mAnimatedWebpWasPlayingBeforeSeek = texture.isPlaybackPlaying();
            texture.setPlaybackPlaying(false);
        }
    }

    private void requestAnimatedWebpSeekPreview(int position, boolean force) {
        ImageTexture texture = mAnimatedWebpTexture;
        if (texture == null || !Settings.getAnimatedWebpAllowSeek()) return;
        int duration = texture.getPlaybackDuration();
        int target = Math.max(0, Math.min(Math.max(0, duration - 1), position));
        mAnimatedWebpRequestedPosition = target;
        if (mAnimatedWebpSeek != null && mAnimatedWebpSeek.getProgress() != target) {
            mAnimatedWebpSeek.setProgress(target);
        }
        updateAnimatedWebpTime(target, duration);
        long now = SystemClock.uptimeMillis();
        if (force || now - mAnimatedWebpLastPreviewAt >= 120L) {
            mAnimatedWebpLastPreviewAt = now;
            texture.seekTo(target);
        }
    }

    private void finishAnimatedWebpSeek(int position) {
        ImageTexture texture = mAnimatedWebpTexture;
        if (texture == null || !mAnimatedWebpSeeking) return;
        requestAnimatedWebpSeekPreview(position, true);
        mAnimatedWebpSeeking = false;
        mAnimatedWebpSeekAwaitingFrame = true;
        if (mAnimatedWebpWasPlayingBeforeSeek) texture.setPlaybackPlaying(true);
    }

    private void toggleAnimatedWebpPlayback() {
        ImageTexture texture = mAnimatedWebpTexture;
        if (texture != null) {
            texture.setPlaybackPlaying(!texture.isPlaybackPlaying());
        }
    }

    private void cycleAnimatedWebpSpeed() {
        ImageTexture texture = mAnimatedWebpTexture;
        if (texture == null) return;
        float speed = texture.getPlaybackSpeed();
        if (speed < 0.75f) speed = 1.0f;
        else if (speed < 1.25f) speed = 1.5f;
        else if (speed < 1.75f) speed = 2.0f;
        else speed = 0.5f;
        texture.setPlaybackSpeed(speed);
        updateAnimatedWebpSpeedButton(speed);
    }

    private void updateAnimatedWebpSpeedButton(float speed) {
        if (mAnimatedWebpSpeed == null) return;
        String text = speed == (int) speed
                ? Integer.toString((int) speed) + "x"
                : String.format(Locale.US, "%.1fx", speed);
        mAnimatedWebpSpeed.setText(text);
    }

    private void updateAnimatedWebpSequentialButton() {
        if (mAnimatedWebpSequential == null) return;
        mAnimatedWebpSequential.setImageResource(Settings.getAnimatedWebpAutoAdvance()
                ? R.drawable.v_animated_webp_repeat_next_x24
                : R.drawable.v_animated_webp_repeat_one_x24);
        mAnimatedWebpSequential.setSelected(Settings.getAnimatedWebpAutoAdvance());
    }

    private static String formatAnimatedWebpTime(int positionMs, int durationMs) {
        int positionSeconds = Math.max(0, positionMs) / 1000;
        int durationSeconds = Math.max(0, durationMs) / 1000;
        return String.format(Locale.US, "%02d:%02d/%02d:%02d",
                positionSeconds / 60, positionSeconds % 60,
                durationSeconds / 60, durationSeconds % 60);
    }

    private void updateAnimatedWebpTime(int positionMs, int durationMs) {
        if (mAnimatedWebpTime != null) {
            mAnimatedWebpTime.setText(formatAnimatedWebpTime(positionMs, durationMs));
        }
    }

    private void updatePageSliderTint(boolean animatedWebp) {
        if (mSeekBar == null || mPageSliderAnimatedTintApplied == animatedWebp) return;
        mPageSliderAnimatedTintApplied = animatedWebp;
        if (animatedWebp) {
            ColorStateList tint = ColorStateList.valueOf(0xffb8c9bb);
            ColorStateList backgroundTint = ColorStateList.valueOf(0x80b8c9bb);
            mSeekBar.setProgressTintList(tint);
            mSeekBar.setProgressBackgroundTintList(backgroundTint);
            mSeekBar.setSecondaryProgressTintList(tint);
            mSeekBar.setThumbTintList(tint);
        } else {
            if (mPageSliderDefaultProgressDrawableState != null) {
                mSeekBar.setProgressDrawable(mPageSliderDefaultProgressDrawableState
                        .newDrawable(getResources(), getTheme()).mutate());
            }
            if (mPageSliderDefaultThumbDrawableState != null) {
                mSeekBar.setThumb(mPageSliderDefaultThumbDrawableState
                        .newDrawable(getResources(), getTheme()).mutate());
            }
            mSeekBar.setProgressTintList(mPageSliderDefaultProgressTint);
            mSeekBar.setProgressBackgroundTintList(
                    mPageSliderDefaultProgressBackgroundTint);
            mSeekBar.setSecondaryProgressTintList(
                    mPageSliderDefaultSecondaryProgressTint);
            mSeekBar.setThumbTintList(mPageSliderDefaultThumbTint);
        }
        mSeekBar.invalidate();
    }

    private boolean reloadCurrentAnimatedWebpForDecoderModeIfNeeded() {
        if (mGalleryView == null || mGalleryProvider == null || mCurrentIndex < 0) {
            return false;
        }
        GalleryPageView page = mGalleryView.findPageByIndex(mCurrentIndex);
        ImageTexture texture = page != null ? page.getImageTexture() : null;
        if (texture == null || !texture.isAnimatedWebpSource()) {
            mAnimatedWebpReloadSourceTexture = null;
            return false;
        }

        boolean targetEnabled = Settings.getExperimentalAnimatedWebpEnabled() &&
                mLayoutMode != GalleryView.LAYOUT_TOP_TO_BOTTOM;
        if (texture.wasAnimatedWebpControlRequested() == targetEnabled) {
            mAnimatedWebpReloadSourceTexture = null;
            return false;
        }
        if (mAnimatedWebpReloadSourceTexture == texture) {
            return false;
        }

        mAnimatedWebpReloadSourceTexture = texture;
        if (mAnimatedWebpTexture != null) {
            mAnimatedWebpTexture.setPlaybackListener(null);
            mAnimatedWebpTexture = null;
        }
        mAnimatedWebpSeeking = false;
        mAnimatedWebpSeekAwaitingFrame = false;
        clearAnimatedWebpTouchGesture();
        if (mAnimatedWebpPanel != null) mAnimatedWebpPanel.setVisibility(View.GONE);

        // Animated pages are normally excluded from GalleryProvider's LRU cache.
        // Removing the entry is still useful if a decoder reported the image as
        // static. The provider then decodes the already downloaded/local source
        // again without recreating the complete gallery.
        mGalleryProvider.removeCache(mCurrentIndex);
        mGalleryProvider.notifyDataChanged(mCurrentIndex);
        return true;
    }

    private void updateAnimatedWebpUi() {
        if (reloadCurrentAnimatedWebpForDecoderModeIfNeeded()) return;

        ImageTexture candidate = null;
        if (mLayoutMode != GalleryView.LAYOUT_TOP_TO_BOTTOM && mGalleryView != null &&
                mCurrentIndex >= 0) {
            GalleryPageView page = mGalleryView.findPageByIndex(mCurrentIndex);
            ImageTexture texture = page != null ? page.getImageTexture() : null;
            if (texture != null && texture.isControllableAnimation()) candidate = texture;
        }

        if (!Settings.getExperimentalAnimatedWebpEnabled()) candidate = null;

        if (candidate != mAnimatedWebpTexture) {
            if (mAnimatedWebpTexture != null) mAnimatedWebpTexture.setPlaybackListener(null);
            mAnimatedWebpTexture = candidate;
            mAnimatedWebpSeeking = false;
            mAnimatedWebpSeekAwaitingFrame = false;
            if (candidate != null) candidate.setPlaybackListener(this);
        }

        boolean showTime = Settings.getAnimatedWebpShowTime();
        boolean allowSeek = Settings.getAnimatedWebpAllowSeek();
        boolean sliderVisible = mSeekBarPanel != null &&
                mSeekBarPanel.getVisibility() == View.VISIBLE;
        int sliderOffset = sliderVisible && mSeekBarPanel != null
                ? mSeekBarPanel.getHeight() : 0;
        boolean visible = candidate != null;
        updatePageSliderTint(visible);
        if (mAnimatedWebpPanel != null) {
            mAnimatedWebpPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) {
            if (mAutoTransferPanel != null && sliderVisible) {
                mAutoTransferPanel.setVisibility(View.VISIBLE);
            }
            return;
        }

        int duration = candidate.getPlaybackDuration();
        int position = candidate.getPlaybackPosition();
        if (mAnimatedWebpSeekAwaitingFrame &&
                Math.abs(position - mAnimatedWebpRequestedPosition) <=
                        Math.max(150, candidate.getPlaybackFrameDelay())) {
            mAnimatedWebpSeekAwaitingFrame = false;
        }
        if (mAnimatedWebpTime != null) {
            mAnimatedWebpTime.setVisibility(showTime ? View.VISIBLE : View.INVISIBLE);
        }
        if (!mAnimatedWebpSeeking && !mAnimatedWebpSeekAwaitingFrame) {
            updateAnimatedWebpTime(position, duration);
        }
        if (mAnimatedWebpSeek != null) {
            mAnimatedWebpSeek.setVisibility(View.VISIBLE);
            // The page slider occupies the higher-priority touch layer while visible.
            mAnimatedWebpSeek.setEnabled(allowSeek);
            mAnimatedWebpSeek.setTouchHandlingEnabled(!sliderVisible);
            mAnimatedWebpSeek.setMax(Math.max(1, duration));
            if (!mAnimatedWebpSeeking && !mAnimatedWebpSeekAwaitingFrame) {
                mAnimatedWebpSeek.setProgress(Math.min(duration, position));
            }
        }
        if (mAnimatedWebpControls != null) {
            mAnimatedWebpControls.setVisibility(showTime || sliderVisible
                    ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams raw = mAnimatedWebpControls.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                int baseMargin = Math.round(18 * getResources().getDisplayMetrics().density);
                if (params.bottomMargin != baseMargin + sliderOffset) {
                    params.bottomMargin = baseMargin + sliderOffset;
                    mAnimatedWebpControls.setLayoutParams(params);
                }
                if (mAnimatedWebpPanel != null) {
                    ViewGroup.LayoutParams panelParams = mAnimatedWebpPanel.getLayoutParams();
                    int height = Math.round(58 * getResources().getDisplayMetrics().density)
                            + sliderOffset;
                    if (panelParams.height != height) {
                        panelParams.height = height;
                        mAnimatedWebpPanel.setLayoutParams(panelParams);
                    }
                }
            }
        }
        if (mAnimatedWebpPlayPause != null) {
            mAnimatedWebpPlayPause.setVisibility(sliderVisible ? View.VISIBLE : View.INVISIBLE);
            mAnimatedWebpPlayPause.setImageResource(candidate.isPlaybackPlaying()
                    ? R.drawable.v_pause_x24 : R.drawable.v_play_x24);
        }
        if (mAnimatedWebpSpeed != null) {
            mAnimatedWebpSpeed.setVisibility(sliderVisible ? View.VISIBLE : View.INVISIBLE);
        }
        if (mAnimatedWebpSequential != null) {
            mAnimatedWebpSequential.setVisibility(
                    sliderVisible ? View.VISIBLE : View.INVISIBLE);
        }
        if (mAutoTransferPanel != null && sliderVisible) {
            mAutoTransferPanel.setVisibility(Settings.getAnimatedWebpAutoTransferButton()
                    ? View.VISIBLE : View.INVISIBLE);
        }
        updateAnimatedWebpSpeedButton(candidate.getPlaybackSpeed());
        updateAnimatedWebpSequentialButton();
    }

    @Override
    public void onPlaybackChanged(ImageTexture texture, boolean looped) {
        mAnimatedWebpHandler.post(() -> {
            if (texture != mAnimatedWebpTexture) return;
            updateAnimatedWebpUi();
            if (looped && Settings.getAnimatedWebpAutoAdvance() && mGalleryView != null &&
                    mCurrentIndex >= 0 && mCurrentIndex + 1 < mSize) {
                mGalleryView.setCurrentPage(mCurrentIndex + 1);
            }
        });
    }

    @Override
    public void onDoubleTapSliderArea() {
        mAnimatedWebpHandler.post(this::toggleAnimatedWebpPlayback);
    }

    @Override
    public synchronized boolean onLongPressSliderArea(@NonNull ImageTexture texture) {
        float speed = Settings.getAnimatedWebpLongPressSpeed();
        if (!Settings.getExperimentalAnimatedWebpEnabled()
                || mLayoutMode == GalleryView.LAYOUT_TOP_TO_BOTTOM
                || texture != mAnimatedWebpTexture
                || Math.abs(speed - 1.0f) < 0.0001f
                || mAnimatedWebpLongPressTexture != null) {
            return false;
        }
        mAnimatedWebpLongPressTexture = texture;
        mAnimatedWebpLongPressRestoreSpeed = texture.getPlaybackSpeed();
        mAnimatedWebpLongPressRestorePlaying = texture.isPlaybackPlaying();
        texture.setPlaybackSpeed(speed);
        texture.setPlaybackPlaying(true);
        return true;
    }

    @Override
    public void onLongPressSliderAreaReleased() {
        restoreAnimatedWebpLongPressPlayback();
    }

    private synchronized void restoreAnimatedWebpLongPressPlayback() {
        ImageTexture texture = mAnimatedWebpLongPressTexture;
        if (texture == null) return;
        float speed = mAnimatedWebpLongPressRestoreSpeed;
        boolean playing = mAnimatedWebpLongPressRestorePlaying;
        mAnimatedWebpLongPressTexture = null;
        texture.setPlaybackSpeed(speed);
        texture.setPlaybackPlaying(playing);
    }

    private class NotifyTask implements Runnable {

        public static final int KEY_LAYOUT_MODE = 0;
        public static final int KEY_SIZE = 1;
        public static final int KEY_CURRENT_INDEX = 2;
        public static final int KEY_TAP_SLIDER_AREA = 3;
        public static final int KEY_TAP_MENU_AREA = 4;
        public static final int KEY_TAP_ERROR_TEXT = 5;
        public static final int KEY_LONG_PRESS_PAGE = 6;
        public static final int KEY_LONG_PRESS_NEXT_PAGE_AREA = 7;
        public static final int KEY_LONG_PRESS_PREVIOUS_PAGE_AREA = 8;

        private int mKey;
        private int mValue;

        public void setData(int key, int value) {
            mKey = key;
            mValue = value;
        }

        private void onTapMenuArea() {
            AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
            GalleryMenuHelper helper = new GalleryMenuHelper(builder.getContext());
            builder.setTitle(R.string.gallery_menu_title).setView(helper.getView()).setPositiveButton(android.R.string.ok, helper).show();
        }

        private void onTapSliderArea() {
            if (mSeekBarPanel == null || mSize <= 0 || mCurrentIndex < 0
                    || mAutoTransferPanel == null || mQuickSettingsPanel == null) {
                return;
            }

            SimpleHandler.getInstance().removeCallbacks(mHideSliderRunnable);

            if (mSeekBarPanel.getVisibility() == View.VISIBLE) {
                hideSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                hideSlider(mAutoTransferPanel, mAutoTransferAnimator);
                hideSlider(mQuickSettingsPanel, mQuickSettingsAnimator);
            } else {
                updateQuickSettingsButtons();
                showSlider(mSeekBarPanel, mSeekBarPanelAnimator);
                showSlider(mAutoTransferPanel, mAutoTransferAnimator);
                showSlider(mQuickSettingsPanel, mQuickSettingsAnimator);
                updateAnimatedWebpUi();
                SimpleHandler.getInstance().postDelayed(mHideSliderRunnable, HIDE_SLIDER_DELAY);
            }
        }

        private void onTapErrorText(int index) {
            if (mGalleryProvider != null) {
                mGalleryProvider.forceRequest(index);
            }
        }

        private void performLongPressSave(int index, boolean turnPageAfterSave) {
            if (mGalleryProvider == null || index < 0 || index >= mSize) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (index == mLastLongPressSaveIndex
                    && mLastLongPressSaveAt != 0L
                    && now - mLastLongPressSaveAt < LONG_PRESS_SAVE_DEBOUNCE_MS) {
                return;
            }

            saveImage(index);
            mLastLongPressSaveIndex = index;
            mLastLongPressSaveAt = SystemClock.elapsedRealtime();

            if (turnPageAfterSave && Settings.getLongPressSaveTurnPage()
                    && mGalleryView != null) {
                if (mLayoutMode == GalleryView.LAYOUT_RIGHT_TO_LEFT) {
                    mGalleryView.pageLeft();
                } else {
                    mGalleryView.pageRight();
                }
            }
        }

        private void onLongPressPage(final int index) {
            showPageDialog(index);
        }

        private void onLongPressNextPageArea(final int index) {
            if (Settings.getDirectSave()) {
                performLongPressSave(index, true);
            } else {
                showPageDialog(index);
            }
        }

        private void onLongPressPreviousPageArea(final int index) {
            int previousIndex = index - 1;
            if (Settings.getDirectSave()
                    && Settings.getExperimentalAnimatedWebpEnabled()
                    && Settings.getAnimatedWebpAutoAdvance()
                    && mLayoutMode != GalleryView.LAYOUT_TOP_TO_BOTTOM
                    && previousIndex >= 0) {
                performLongPressSave(previousIndex, false);
            } else {
                showPageDialog(index);
            }
        }

        @Override
        public void run() {
            switch (mKey) {
                case KEY_LAYOUT_MODE:
                    GalleryActivity.this.mLayoutMode = mValue;
                    updateSlider();
                    break;
                case KEY_SIZE:
                    GalleryActivity.this.mSize = mValue;
                    updateSlider();
                    updateProgress();
                    break;
                case KEY_CURRENT_INDEX:
                    GalleryActivity.this.mCurrentIndex = mValue;
                    updateSlider();
                    updateProgress();
                    if (!reloadCurrentAnimatedWebpForDecoderModeIfNeeded()) {
                        updateAnimatedWebpUi();
                    }
                    break;
                case KEY_TAP_MENU_AREA:
                    onTapMenuArea();
                    break;
                case KEY_TAP_SLIDER_AREA:
                    onTapSliderArea();
                    break;
                case KEY_TAP_ERROR_TEXT:
                    onTapErrorText(mValue);
                    break;
                case KEY_LONG_PRESS_PAGE:
                    onLongPressPage(mValue);
                    break;
                case KEY_LONG_PRESS_NEXT_PAGE_AREA:
                    onLongPressNextPageArea(mValue);
                    break;
                case KEY_LONG_PRESS_PREVIOUS_PAGE_AREA:
                    onLongPressPreviousPageArea(mValue);
                    break;
            }
            mNotifyTaskPool.push(this);
        }
    }

    private class GalleryAdapter extends SimpleAdapter {

        public GalleryAdapter(@NonNull GLRootView glRootView, @NonNull GalleryProvider provider) {
            super(glRootView, provider);
        }

        @Override
        public void onDataChanged() {
            super.onDataChanged();

            if (mGalleryProvider != null) {
                int size = mGalleryProvider.size();
                NotifyTask task = mNotifyTaskPool.pop();
                if (task == null) {
                    task = new NotifyTask();
                }
                task.setData(NotifyTask.KEY_SIZE, size);
                SimpleHandler.getInstance().post(task);
            }
        }
    }

}
