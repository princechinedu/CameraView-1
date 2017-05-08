package com.xlythe.fragment.camera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.v4.app.Fragment;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.PermissionChecker;
import com.xlythe.view.camera.R;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;

public abstract class CameraFragment extends Fragment implements CameraView.OnImageCapturedListener, CameraView.OnVideoCapturedListener {
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final String[] OPTIONAL_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.VIBRATE
    };
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String DESTINATION = "yyyy-MM-dd hh:mm:ss";
    private static final String PHOTO_EXT = ".jpg";
    private static final String VIDEO_EXT = ".mp4";

    private View mCameraHolder;

    private View mPermissionPrompt;

    private CameraView mCamera;

    private View mPermissionRequest;

    private View mCapture;

    private View mConfirm;

    @Nullable
    private TextView mDuration;

    @Nullable
    private ProgressBar mProgress;

    @Nullable
    private CompoundButton mToggle;

    @Nullable
    private View mCancel;

    private ProgressBarAnimator mAnimator = new ProgressBarAnimator();

    private DisplayManager.DisplayListener mDisplayListener;

    @SuppressWarnings({"MissingPermission"})
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                showCamera();
            } else {
                showPermissionPrompt();
            }
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (Build.VERSION.SDK_INT > 17) {
            mDisplayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @SuppressWarnings({"MissingPermission"})
                @Override
                public void onDisplayChanged(int displayId) {
                    if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
                        if (mCamera.isOpen()) {
                            mCamera.close();
                            mCamera.open();
                        }
                    }
                }
            };
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            displayManager.registerDisplayListener(mDisplayListener, new Handler());
        }
    }

    @Override
    public void onDetach() {
        if (Build.VERSION.SDK_INT > 17) {
            DisplayManager displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            displayManager.unregisterDisplayListener(mDisplayListener);
        }
        super.onDetach();
    }

    @SuppressWarnings({"MissingPermission"})
    @Override
    public void onStart() {
        super.onStart();
        if (PermissionChecker.hasPermissions(getContext(), REQUIRED_PERMISSIONS)) {
            showCamera();
        } else {
            showPermissionPrompt();
        }
    }

    @Override
    public void onStop() {
        mCamera.close();
        super.onStop();
    }

    public void setEnabled(boolean enabled) {
        mCapture.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return mCapture.isEnabled();
    }

    public void setQuality(CameraView.Quality quality) {
        mCamera.setQuality(quality);
    }

    public CameraView.Quality getQuality() {
        return mCamera.getQuality();
    }

    public void setMaxVideoDuration(long duration) {
        mCamera.setMaxVideoDuration(duration);
    }

    public long getMaxVideoDuration() {
        return mCamera.getMaxVideoDuration();
    }

    public void setMaxVideoSize(long size) {
        mCamera.setMaxVideoSize(size);
    }

    public long getMaxVideoSize() {
        return mCamera.getMaxVideoSize();
    }

    protected void onTakePicture() {}

    protected void onRecordStart() {}

    protected void onRecordStop() {}

    @RequiresPermission(allOf = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    })
    private void showCamera() {
        mCameraHolder.setVisibility(View.VISIBLE);
        mPermissionPrompt.setVisibility(View.GONE);
        mCamera.open();
    }

    private void showPermissionPrompt() {
        mCameraHolder.setVisibility(View.GONE);
        mPermissionPrompt.setVisibility(View.VISIBLE);
    }

    private static String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        Formatter formatter = new Formatter(Locale.getDefault());

        if (hours > 0) {
            return formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return formatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mCameraHolder = view.findViewById(R.id.layout_camera);
        mPermissionPrompt = view.findViewById(R.id.layout_permissions);
        mPermissionRequest = view.findViewById(R.id.request_permissions);
        mCamera = (CameraView) view.findViewById(R.id.camera);
        mToggle = (CompoundButton) view.findViewById(R.id.toggle);
        mCapture = mCameraHolder.findViewById(R.id.capture);
        mProgress = (ProgressBar) view.findViewById(R.id.progress);
        mDuration = (TextView) view.findViewById(R.id.duration);
        mCancel = view.findViewById(R.id.cancel);
        mConfirm = view.findViewById(R.id.confirm);

        if (mCameraHolder == null) {
            throw new IllegalStateException("No View found with id R.id.layout_camera");
        }

        if (mCamera == null) {
            throw new IllegalStateException("No CameraView found with id R.id.camera");
        }

        if (mCapture == null) {
            throw new IllegalStateException("No CameraView found with id R.id.capture");
        }

        if (mPermissionPrompt == null) {
            throw new IllegalStateException("No View found with id R.id.layout_permissions");
        }

        if (mPermissionRequest == null) {
            throw new IllegalStateException("No View found with id R.id.request_permissions");
        }

        mCamera.setOnImageCapturedListener(this);
        mCamera.setOnVideoCapturedListener(this);

        mCapture.setOnTouchListener(new OnTouchListener(getContext()));

        if (mConfirm != null) {
            mConfirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mCamera.confirmPicture();
                }
            });
            mCamera.setImageConfirmationEnabled(true);
            mCamera.setVideoConfirmationEnabled(true);
        } else {
            mCamera.setImageConfirmationEnabled(false);
            mCamera.setVideoConfirmationEnabled(false);
        }

        mPermissionRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(concat(REQUIRED_PERMISSIONS, OPTIONAL_PERMISSIONS), REQUEST_CODE_PERMISSIONS);
            }
        });

        if (mToggle != null) {
            mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
            mToggle.setChecked(mCamera.isUsingFrontFacingCamera());
            mToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mCamera.toggleCamera();
                }
            });
        }

        if (mProgress != null) {
            mProgress.setMax(10000);
        }

        if (mDuration != null) {
            mDuration.setVisibility(View.GONE);
        }

        if (mCancel != null) {
            mCancel.setVisibility(View.GONE);
        }

        mConfirm.setVisibility(View.GONE);
    }

    @Override
    public void onFailure() {}

    @Override
    public void onImageConfirmation() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (view == mCancel) {
                    mCamera.rejectPicture();
                } else {
                    mCamera.confirmPicture();
                }

                // After confirming/rejecting, show our buttons again
                mConfirm.setVisibility(View.GONE);
                mCapture.setVisibility(View.VISIBLE);
                if (mCancel!= null) {
                    mCancel.setVisibility(View.GONE);
                }
                if (mToggle != null) {
                    mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
                }
            }
        };
        if (mCancel != null) {
            mCancel.setVisibility(View.VISIBLE);
            mCancel.setOnClickListener(listener);
        }
        if (mToggle != null) {
            mToggle.setVisibility(View.GONE);
        }
        mCapture.setVisibility(View.GONE);
        mConfirm.setVisibility(View.VISIBLE);
        mConfirm.setOnClickListener(listener);
    }

    @Override
    public void onVideoConfirmation() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (view == mCancel) {
                    mCamera.rejectVideo();
                } else {
                    mCamera.confirmVideo();
                }

                // After confirming/rejecting, show our buttons again
                mConfirm.setVisibility(View.GONE);
                mCapture.setVisibility(View.VISIBLE);
                if (mCancel!= null) {
                    mCancel.setVisibility(View.GONE);
                }
                if (mToggle != null) {
                    mToggle.setVisibility(mCamera.hasFrontFacingCamera() ? View.VISIBLE : View.GONE);
                }
            }
        };
        if (mCancel != null) {
            mCancel.setVisibility(View.VISIBLE);
            mCancel.setOnClickListener(listener);
        }
        if (mToggle != null) {
            mToggle.setVisibility(View.GONE);
        }
        mCapture.setVisibility(View.GONE);
        mConfirm.setVisibility(View.VISIBLE);
        mConfirm.setOnClickListener(listener);
    }

    private class ProgressBarAnimator extends ValueAnimator {
        private ProgressBarAnimator() {
            setInterpolator(new LinearInterpolator());
            setFloatValues(0f, 1f);
            addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float percent = (float) animation.getAnimatedValue();
                    onUpdate(percent);
                }
            });
        }

        void onUpdate(float percent) {
            if (mProgress != null) {
                mProgress.setProgress((int) (percent * 10000));
            }
            if (mDuration != null) {
                mDuration.setText(stringForTime((int) (percent * 10000)));
            }
        }
    }

    private class OnTouchListener implements View.OnTouchListener {
        private final int TAP = 1;
        private final int HOLD = 2;
        private final int RELEASE = 3;

        private final long LONG_PRESS = ViewConfiguration.getLongPressTimeout();

        private final Context mContext;

        private long mDownEventTimestamp;
        private Rect mViewBoundsRect;
        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case TAP:
                        onTap();
                        break;
                    case HOLD:
                        onHold();
                        if (mCamera.getMaxVideoDuration() > 0) {
                            sendEmptyMessageDelayed(RELEASE, mCamera.getMaxVideoDuration());
                        }
                        break;
                    case RELEASE:
                        onRelease();
                        break;
                }
            }
        };

        OnTouchListener(Context context) {
            mContext = context;
        }

        Context getContext() {
            return mContext;
        }

        void onTap() {
            mCamera.takePicture(new File(getContext().getCacheDir(), DateFormat.format(DESTINATION, new Date()) + PHOTO_EXT));
            onTakePicture();
        }

        void onHold() {
            vibrate();
            mCamera.startRecording(new File(getContext().getCacheDir(), DateFormat.format(DESTINATION, new Date()) + VIDEO_EXT));
            if (mCamera.isRecording()) {
                if (mDuration != null) {
                    mDuration.setVisibility(View.VISIBLE);
                }
                if (mCamera.getMaxVideoDuration() > 0) {
                    mAnimator.setDuration(mCamera.getMaxVideoDuration()).start();
                }
                onRecordStart();
            }
        }

        void onRelease() {
            mCamera.stopRecording();
            mAnimator.cancel();
            if (mProgress != null) {
                mProgress.setProgress(0);
            }
            if (mDuration != null) {
                mDuration.setVisibility(View.GONE);
            }
            onRecordStop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDownEventTimestamp = System.currentTimeMillis();
                    mViewBoundsRect = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    mHandler.sendEmptyMessageDelayed(HOLD, LONG_PRESS);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // If the user moves their finger off the button, trigger RELEASE
                    if (mViewBoundsRect.contains(v.getLeft() + (int) event.getX(), v.getTop() + (int) event.getY())) {
                        break;
                    }
                    // Fall through
                case MotionEvent.ACTION_CANCEL:
                    clearHandler();
                    if (delta() > LONG_PRESS && (mCamera.getMaxVideoDuration() <= 0 || delta() < mCamera.getMaxVideoDuration())) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
                case MotionEvent.ACTION_UP:
                    clearHandler();
                    if (delta() < LONG_PRESS) {
                        mHandler.sendEmptyMessage(TAP);
                    } else if ((mCamera.getMaxVideoDuration() <= 0 || delta() < mCamera.getMaxVideoDuration())) {
                        mHandler.sendEmptyMessage(RELEASE);
                    }
                    v.setPressed(false);
                    break;
            }
            return true;
        }

        private long delta() {
            return System.currentTimeMillis() - mDownEventTimestamp;
        }

        private void vibrate() {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (PermissionChecker.hasPermissions(getContext(), Manifest.permission.VIBRATE) && vibrator.hasVibrator()) {
                vibrator.vibrate(25);
            }
        }

        private void clearHandler() {
            mHandler.removeMessages(TAP);
            mHandler.removeMessages(HOLD);
            mHandler.removeMessages(RELEASE);
        }
    }

    private static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
