/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.camera2raw;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import androidx.core.app.ActivityCompat;
import androidx.legacy.app.FragmentCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Camera2RawFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {

    // 创建一个nanoDetNcnn
    private static NanoDetNcnn nanodetncnn = new NanoDetNcnn();

    static Bitmap bitmap = Bitmap.createBitmap(1024,1024, Bitmap.Config.ARGB_8888);

    // 转换屏幕方向为JPEG方向
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    // 相机权限请求代码和拍照需要的权限
    private static final int REQUEST_CAMERA_PERMISSIONS = 1;
    // 拍照需要的权限
    private static final String[] CAMERA_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    private static final long PRECAPTURE_TIMEOUT_MS = 1000; // 预捕获的超时
    private static final double ASPECT_RATIO_TOLERANCE = 0.005; // 比较纵横比时候的误差
    private static final int MAX_PREVIEW_WIDTH = 1920; // Camera2API提供的最大预览宽度
    private static final int MAX_PREVIEW_HEIGHT = 1080; // Camera2API提供的最大预览高度
    private static final String TAG = "Camera2RawFragment"; // Log的TAG
    private static final int STATE_CLOSED = 0; // 相机状态：设备关闭
    private static final int STATE_OPENED = 1; // 相机状态：设备开启但不是捕获中
    private static final int STATE_PREVIEW = 2; // 相机状态：展示相机预览中
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3; // 相机状态：捕获图像前等待3A完成
    private OrientationEventListener mOrientationListener; // 判断什么时候发生设备旋转

    // 处理TextureView的多个生命周期事件
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            synchronized (mCameraStateLock) {
                mPreviewSize = null;
            }
            return true;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    private TextureView mTextureView; // 用于相机预览的自动缩放的TextureView
    private static ImageView tv;
    private HandlerThread mBackgroundThread; // 用于CameraDevice和CameraCaptureSession回调事件的后台线程，避免阻塞前台UI
    private final AtomicInteger mRequestCounter = new AtomicInteger(); // 用于捕获回调中跟踪CaptureRequest和CaptureResult的计数器
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1); // 避免关闭相机前退出app的信号量
    private final Object mCameraStateLock = new Object(); // 保护相机状态的锁

    private String mCameraId; // 当前相机设备的ID
    private CameraCaptureSession mCaptureSession; // 用于相机预览的CameraCaptureSession
    private CameraDevice mCameraDevice; // 指向打开的相机设备的相机设备类
    private Size mPreviewSize; // 相机预览的尺寸
    private CameraCharacteristics mCharacteristics; // 当前配置好的相机设备的CameraCharacteristics
    private Handler mBackgroundHandler; // 在后台运行任务的句柄
    private RefCountedAutoCloseable<ImageReader> mJpegImageReader; // 处理JPEG图像捕获的计数器
    private RefCountedAutoCloseable<ImageReader> mRawImageReader; // 处理RAW图像捕获的计数器
    private boolean mNoAFRun = false; // 当前配置的相机是否是定焦
    private int mPendingUserCaptures = 0; // 待处理的用户拍摄照片的请求数量
    private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mJpegResultQueue = new TreeMap<>(); // 映射到JPEG捕获的请求ID
    private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mRawResultQueue = new TreeMap<>(); // 映射到RAW捕获的请求ID
    private CaptureRequest.Builder mPreviewRequestBuilder; // 图像预览的CaptureRequest.Builder()
    private int mState = STATE_CLOSED; // 相机设备状态
    private long mCaptureTimer; // 跟预捕获序列一起用的定时器，确保在3A过长的时候及时捕获

    // 当前活动的相机设备状态改变时候的回调函数
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        // 相机被开发的时候调用这个方法，如果textureview显示设置好了的话可以开启相机预览
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_OPENED;
                mCameraOpenCloseLock.release();
                mCameraDevice = cameraDevice;
                if (mPreviewSize != null && mTextureView.isAvailable()) {
                    createCameraPreviewSessionLocked();
                }
            }
        }
        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
            }
        }
        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Received camera device error: " + error);
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
            }
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    // JPEG图像准备好被保存的时候会回调onImageAvailable
    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader);
        }
    };

    // RAW图像准备好被保存的时候会回调onImageAvailable
    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
        }
    };

    // 处理预览和预捕获序列事件的CaptureCallback
    private CameraCaptureSession.CaptureCallback mPreCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
            synchronized (mCameraStateLock) {
                switch (mState) {
                    case STATE_PREVIEW: {
                        break; // 相机正常预览的时候啥都不用干
                    }
                    case STATE_WAITING_FOR_3A_CONVERGENCE: {
                        boolean readyToCapture = true;
                        if (!mNoAFRun) {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                break;
                            }
                            // 如果自动对焦是锁定的状态，说明可以捕获了
                            readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                        }
                        // 在非传统设备上运行时，要等到自动曝光和自动白平衡收敛后再拍照
                        if (!isLegacyLocked()) {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                            if (aeState == null || awbState == null) {
                                break;
                            }
                            readyToCapture = readyToCapture && aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED && awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
                        }
                        // 如果预捕获序列还没完成但是以及超时了，就要强行开始捕获了
                        if (!readyToCapture && hitTimeoutLocked()) {
                            Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                            readyToCapture = true;
                        }
                        if (readyToCapture && mPendingUserCaptures > 0) {
                            // 用户每点一次Picture就捕获一次
                            while (mPendingUserCaptures > 0) {
                                captureStillPictureLocked();
                                mPendingUserCaptures--;
                            }
                            // 做完之后相机就可以返回正常的预览状态了
                            mState = STATE_PREVIEW;
                        }
                    }
                }
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }

    };

    // 处理JPEG和RAW捕获请求的CaptureCallback
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            String currentDateTime = generateTimestamp();
            File rawFile = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_DCIM), "RAW_" + currentDateTime + ".dng");
            File jpegFile = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_DCIM), "JPEG_" + currentDateTime + ".jpg");
            // 查找这个请求的ImageSaverBuilder并按照捕获开始时间用文件名更新一下
            ImageSaver.ImageSaverBuilder jpegBuilder;
            ImageSaver.ImageSaverBuilder rawBuilder;
            int requestId = (int) request.getTag();
            synchronized (mCameraStateLock) {
                jpegBuilder = mJpegResultQueue.get(requestId);
                rawBuilder = mRawResultQueue.get(requestId);
            }
            if (jpegBuilder != null) jpegBuilder.setFile(jpegFile);
            if (rawBuilder != null) rawBuilder.setFile(rawFile);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            int requestId = (int) request.getTag();
            ImageSaver.ImageSaverBuilder jpegBuilder;
            ImageSaver.ImageSaverBuilder rawBuilder;
            StringBuilder sb = new StringBuilder();
            // 查找这个请求的ImageSaverBuilder并用CaptureRequest更新一下
            synchronized (mCameraStateLock) {
                jpegBuilder = mJpegResultQueue.get(requestId);
                rawBuilder = mRawResultQueue.get(requestId);
                if (rawBuilder != null) {
                    rawBuilder.setResult(result);
                }

                sb.append("capture raw over");
                // 如果获得了所有的必要的结果，就可以在后台将图像保存到文件了
                handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue);
                handleCompletionLocked(requestId, rawBuilder, mRawResultQueue);
                finishedCaptureLocked();
            }
            showToast(sb.toString());
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            int requestId = (int) request.getTag();
            synchronized (mCameraStateLock) {
                mJpegResultQueue.remove(requestId);
                mRawResultQueue.remove(requestId);
                finishedCaptureLocked();
            }
            showToast("Capture failed!");
        }
    };

    // 在UI线程显示Toast的句柄
    private final Handler mMessageHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Activity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, (String) msg.obj, Toast.LENGTH_SHORT).show();
            }
        }
    };

    public static Camera2RawFragment newInstance() {
        return new Camera2RawFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
//        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mTextureView = view.findViewById(R.id.texture);
        tv = view.findViewById(R.id.imageView);
        // 设置一个旋转事件监听来处理旋转事件
        mOrientationListener = new OrientationEventListener(getActivity(), SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (mTextureView != null && mTextureView.isAvailable()) {
                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                }
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        openCamera();
        // 当屏幕关闭并重新打开、SurfaceTexture可以且不会调用onSurfaceTextureView时，要配置预览边界
        if (mTextureView.isAvailable()) {
            configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        }
        else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }
    }

    @Override
    public void onPause() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
        }
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showMissingPermissionError();
                    return;
                }
            }
        }
        else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                takePicture(); // 拍照
                break;
            }
        }
    }

    // 打开相机设备前要先设置相关相机的状态
    private boolean setUpCameraOutputs() {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            ErrorDialog.buildErrorDialog("This device doesn't support Camera2 API.").show(getFragmentManager(), "dialog");
            return false;
        }
        try {
            // 找到一个支持捕获RAW的相机设备并配置状态
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                // 这里我们只用支持RAW的相机
                if (!contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES), CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    continue;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                // 对于固定图像捕获，用最大的可用尺寸
                Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                Size largestRaw = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)), new CompareSizesByArea());
                // 为JPEG和RAW输出设置ImageReader并放到计数wrapper里面去，确保在所有用到他们的后台任务都完成的时候才关闭
                synchronized (mCameraStateLock) {
                    if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
                        mJpegImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(largestJpeg.getWidth(), largestJpeg.getHeight(), ImageFormat.JPEG, 5));
                    }
                    mJpegImageReader.get().setOnImageAvailableListener(mOnJpegImageAvailableListener, mBackgroundHandler);
                    if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
                        mRawImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(largestRaw.getWidth(), largestRaw.getHeight(), ImageFormat.RAW_SENSOR, 5));
                    }
                    mRawImageReader.get().setOnImageAvailableListener(mOnRawImageAvailableListener, mBackgroundHandler);
                    mCharacteristics = characteristics;
                    mCameraId = cameraId;
                }
                return true;
            }
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        // 没找到合适拍RAW的相机就警告一下用户
        ErrorDialog.buildErrorDialog("This device doesn't support capturing RAW photos").show(getFragmentManager(), "dialog");
        return false;
    }

    // 打开CameraId指定的相机
    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        if (!setUpCameraOutputs()) {
            return;
        }
        if (!hasAllPermissionsGranted()) {
            requestCameraPermissions();
            return;
        }
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            // 等待前面的session跑完
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId;
            Handler backgroundHandler;
            synchronized (mCameraStateLock) {
                cameraId = mCameraId;
                backgroundHandler = mBackgroundHandler;
            }
            // 尝试打开相机，不管成功还是失败都会在后台线程调用mStateCallback
            manager.openCamera(cameraId, mStateCallback, backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }

        // 打开相机的时候顺带加载模型
        boolean ret_init = nanodetncnn.loadModel(getContext().getAssets(),0,0);
        if (!ret_init) {
            Log.e("MainActivity", "nanodetncnn loadModel failed");
        }
    }

    // 请求使用相机和保存图片的必要权限
    private void requestCameraPermissions() {
        if (shouldShowRationale()) {
            PermissionConfirmationDialog.newInstance().show(getChildFragmentManager(), "dialog");
        }
        else {
            FragmentCompat.requestPermissions(this, CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS);
        }
    }

    // 告知是否已向该app授予所有必要的权限
    private boolean hasAllPermissionsGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 获取是否要显示带权限请求的UI
    private boolean shouldShowRationale() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    // 显示该app需要权限才能干活
    private void showMissingPermissionError() {
        Activity activity = getActivity();
        if (activity != null) {
            Toast.makeText(activity, R.string.request_permission, Toast.LENGTH_SHORT).show();
            activity.finish();
        }
    }

    // 关闭当前的摄像头设备
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            synchronized (mCameraStateLock) {
                // 重置状态并清理资源，调用后ImageReader会从保存图像的后台任务完成后关闭
                mPendingUserCaptures = 0;
                mState = STATE_CLOSED;
                if (null != mCaptureSession) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
                if (null != mCameraDevice) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
                if (null != mJpegImageReader) {
                    mJpegImageReader.close();
                    mJpegImageReader = null;
                }
                if (null != mRawImageReader) {
                    mRawImageReader.close();
                    mRawImageReader = null;
                }
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        finally {
            mCameraOpenCloseLock.release();
        }
    }

    // 开始后台线程和它的句柄
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        synchronized (mCameraStateLock) {
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    // 停止后台线程和它的句柄
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            synchronized (mCameraStateLock) {
                mBackgroundHandler = null;
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // 为相机预览创建一个新的CameraCaptureSession，只会在mCameraStateLock保持的时候会被调用
    private void createCameraPreviewSessionLocked() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            // 将默认缓冲区大小配置为我们想要的相机预览的大小
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            // 这是我们想要开启预览的输出surface
            Surface surface = new Surface(texture);
            // 给输出surface设置一个Builder
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);
            // 这里给相机预览创建一个Session
            mCameraDevice.createCaptureSession(
                Arrays.asList(surface, mJpegImageReader.get().getSurface(), mRawImageReader.get().getSurface()), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                        synchronized (mCameraStateLock) {
                            if (null == mCameraDevice) {
                                return; // 相机早就关闭了
                            }
                            try {
                                setup3AControlsLocked(mPreviewRequestBuilder);
                                // 最后开始显示相机预览
                                cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mPreCaptureCallback, mBackgroundHandler);
                                mState = STATE_PREVIEW;
                            }
                            catch (CameraAccessException | IllegalStateException e) {
                                e.printStackTrace();
                                return;
                            }
                            // session准备好的时候就可以开始显示预览
                            mCaptureSession = cameraCaptureSession;
                        }
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        showToast("Failed to configure camera.");
                    }
                }, mBackgroundHandler
            );
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 配置一下builder来使用自动对焦、自动曝光、自动白平衡，只在mCameraStateLock保持的时候调用
    private void setup3AControlsLocked(CaptureRequest.Builder builder) {
        // 启动相机设备的3A
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        Float minFocusDist = mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        // 如果最小的对焦距离是0，说明是定焦镜头，需要跳过自动对焦
        mNoAFRun = (minFocusDist == null || minFocusDist == 0);
        if (!mNoAFRun) {
            // 如果连续拍照可用的话，就用它，不然就默认AUTO
            if (contains(mCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }
        else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        }
        // 如果有自动白平衡就用它
        if (contains(mCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // 如果设备支持就允许自动白平衡自动运行
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    // 给textureview配置一下必要的旋转矩阵，这个要在相机状态被初始化的时候调用
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        synchronized (mCameraStateLock) {
            if (null == mTextureView || null == activity) {
                return;
            }
            StreamConfigurationMap map = mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            // 对于静态图像捕获，总是用最大的可用尺寸
            Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            // 找一下设备相对于原始设备方向的旋转
            int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            Point displaySize = new Point();
            activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
            // 找一下设备跟相机传感器方向的角度
            int totalRotation = sensorToDeviceRotation(mCharacteristics, deviceRotation);
            // 如果这跟传感器有旋转，就需要根据view的维度进行交换以进行计算
            // the sensor.
            boolean swappedDimensions = totalRotation == 90 || totalRotation == 270;
            int rotatedViewWidth = viewWidth;
            int rotatedViewHeight = viewHeight;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;
            if (swappedDimensions) {
                rotatedViewWidth = viewHeight;
                rotatedViewHeight = viewWidth;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }
            // 预览不应该比显示尺寸和1080p大
            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }
            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }
            // 为这些view维度和配置的JPEG尺寸找到最合适的预览尺寸
            Size previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight, largestJpeg);
            // 找到设备的旋转角度
            int rotation = (mCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) ? (360 + ORIENTATIONS.get(deviceRotation)) % 360 : (360 - ORIENTATIONS.get(deviceRotation)) % 360;
            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
                matrix.postScale(scale, scale, centerX, centerY);
            }
            matrix.postRotate(rotation, centerX, centerY);
            mTextureView.setTransform(matrix);
            if (mPreviewSize == null || !checkAspectsEqual(previewSize, mPreviewSize)) {
                mPreviewSize = previewSize;
                if (mState != STATE_CLOSED) {
                    createCameraPreviewSessionLocked();
                }
            }
        }
    }

    // 初始化静态图像捕获
    private void takePicture() {
        synchronized (mCameraStateLock) {
            mPendingUserCaptures++;
            // 如果已经触发了预捕获序列，或者处于无法执行此操作的状态，就要立刻返回
            if (mState != STATE_PREVIEW) {
                return;
            }
            try {
                // 如果相机支持，就触发运行自动对焦，如果相机对好焦了，就不做操作
                if (!mNoAFRun) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                }
                // 如果不是旧设备，还可以触发运行一下自动曝光
                if (!isLegacyLocked()) {
                    // 告诉相机要锁焦
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                }
                // 更新状态机以等待自动对焦、自动曝光、自动白平衡完成
                mState = STATE_WAITING_FOR_3A_CONVERGENCE;
                // 给预捕获序列开启计数器
                startTimerLocked();
                // 用更新的3A触发器替换现有的重复请求
                mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback, mBackgroundHandler);
            }
            catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    // 发送一个捕获请求给相机设备以初始化JPEG和RAW的捕获
    private void captureStillPictureLocked() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // 用来拍照的Builder
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mJpegImageReader.get().getSurface());
            captureBuilder.addTarget(mRawImageReader.get().getSurface());
            // 用跟预览一样的自动对焦和自动曝光模式
            setup3AControlsLocked(captureBuilder);
            // 设置方向
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorToDeviceRotation(mCharacteristics, rotation));
            // 设置请求tag来跟踪回调的结果
            captureBuilder.setTag(mRequestCounter.getAndIncrement());
            CaptureRequest request = captureBuilder.build();
            // 创建一个ImageSaverBuilder来收集结果，将它加到请求序列里面去
            ImageSaver.ImageSaverBuilder jpegBuilder = new ImageSaver.ImageSaverBuilder(activity).setCharacteristics(mCharacteristics);
            ImageSaver.ImageSaverBuilder rawBuilder = new ImageSaver.ImageSaverBuilder(activity).setCharacteristics(mCharacteristics);
            mJpegResultQueue.put((int) request.getTag(), jpegBuilder);
            mRawResultQueue.put((int) request.getTag(), rawBuilder);
            mCaptureSession.capture(request, mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 在RAW/JPEG捕获完成后回调，为预捕获序列重置自动曝光触发状态
    private void finishedCaptureLocked() {
        try {
            // 如果自动对焦运行速度不够快，就重置自动对焦触发器
            if (!mNoAFRun) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback, mBackgroundHandler);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 从ImageReader里面检索Image，保留ImageReader直到不再用Image，并将该Image设置为待请求队列的下一个请求。
    private void dequeueAndSaveImage(TreeMap<Integer, ImageSaver.ImageSaverBuilder> pendingQueue, RefCountedAutoCloseable<ImageReader> reader) {
        synchronized (mCameraStateLock) {
            Map.Entry<Integer, ImageSaver.ImageSaverBuilder> entry = pendingQueue.firstEntry();
            ImageSaver.ImageSaverBuilder builder = entry.getValue();
            // 增加引用计数，避免ImageReader在保存图像到后台线程的时候被关掉（否则在写入文件时它的资源有可能被释放掉）
            if (reader == null || reader.getAndRetain() == null) {
                Log.e(TAG, "Paused the activity before we could save the image," + " ImageReader already closed.");
                pendingQueue.remove(entry.getKey());
                return;
            }
            Image image;
            try {
                image = reader.get().acquireNextImage();
            }
            catch (IllegalStateException e) {
                Log.e(TAG, "Too many images queued for saving, dropping image for request: " + entry.getKey());
                pendingQueue.remove(entry.getKey());
                return;
            }
            builder.setRefCountedReader(reader).setImage(image);
            handleCompletionLocked(entry.getKey(), builder, pendingQueue);
        }
    }

    // 将Image保存到指定的File中并更新MediaStore
    private static class ImageSaver implements Runnable {
        private final Image mImage; // 要保存的图像
        private final File mFile; // 要保存图像的文件
        private final CaptureResult mCaptureResult; // 这个捕获图像的捕获结果
        private final CameraCharacteristics mCharacteristics; // 这个相机设备的CameraCharacteristics
        private final Context mContext; // 用保存的图像更新MediaStore时用的context
        private final RefCountedAutoCloseable<ImageReader> mReader; // 拥有给定图像的ImageReader的引用技术包装器
        private ImageSaver(Image image, File file, CaptureResult result, CameraCharacteristics characteristics, Context context,
                           RefCountedAutoCloseable<ImageReader> reader) {
            mImage = image;
            mFile = file;
            mCaptureResult = result;
            mCharacteristics = characteristics;
            mContext = context;
            mReader = reader;
        }
        @Override
        public void run() {
            boolean success = false;
            int format = mImage.getFormat();
            switch (format) {
                case ImageFormat.JPEG: {
                    try {
                        success = true;
                    }
                    finally {
                        mImage.close();
                    }
                    break;
                }
                case ImageFormat.RAW_SENSOR: {
                    try {

                        Log.i(TAG, "start inference ----------------------------- " + generateTimestamp());
                        Image.Plane[] planes = mImage.getPlanes();
                        int remaining0 = planes[0].getBuffer().remaining();

                        byte[] BayerSrcBytes = new byte[remaining0];
                        planes[0].getBuffer().get(BayerSrcBytes);

                        int[] bayer = new int[remaining0/2];
                        for(int i = 0; i < remaining0/2; i++){
                            bayer[i] = (int)((BayerSrcBytes[2*i+1] << 8) | (BayerSrcBytes[2*i] & 0xff));
                        }
                        nanodetncnn.detectDraw(mImage.getWidth(),mImage.getHeight(),bayer,bitmap);
                        Log.i(TAG, "inference finish -----------------------------" + generateTimestamp());

                        success = true;
                    }
                    finally {
                        mImage.close();
                    }
                    break;
                }
                default: {
                    Log.e(TAG, "Cannot save image, unexpected image format:" + format);
                    break;
                }
            }

            // Decrement reference count to allow ImageReader to be closed to free up resources.
            mReader.close();

            // If saving the file succeeded, update MediaStore.
            if (success) {
                MediaScannerConnection.scanFile(mContext, new String[]{mFile.getPath()},
                /*mimeTypes*/null, new MediaScannerConnection.MediaScannerConnectionClient() {
                    @Override
                    public void onMediaScannerConnected() {
                        // Do nothing
                    }

                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        tv.post(new Runnable() {
                            @Override
                            public void run() {
                                tv.setImageBitmap(bitmap);
                            }
                        });
                    }
                });
            }
        }

        /**
         * Builder class for constructing {@link ImageSaver}s.
         * <p/>
         * This class is thread safe.
         */
        public static class ImageSaverBuilder {
            private Image mImage;
            private File mFile;
            private CaptureResult mCaptureResult;
            private CameraCharacteristics mCharacteristics;
            private Context mContext;
            private RefCountedAutoCloseable<ImageReader> mReader;

            /**
             * Construct a new ImageSaverBuilder using the given {@link Context}.
             *
             * @param context a {@link Context} to for accessing the
             *                {@link android.provider.MediaStore}.
             */
            public ImageSaverBuilder(final Context context) {
                mContext = context;
            }

            public synchronized ImageSaverBuilder setRefCountedReader(
                    RefCountedAutoCloseable<ImageReader> reader) {
                if (reader == null) throw new NullPointerException();

                mReader = reader;
                return this;
            }

            public synchronized ImageSaverBuilder setImage(final Image image) {
                if (image == null) throw new NullPointerException();
                mImage = image;
                return this;
            }

            public synchronized ImageSaverBuilder setFile(final File file) {
                if (file == null) throw new NullPointerException();
                mFile = file;
                return this;
            }

            public synchronized ImageSaverBuilder setResult(final CaptureResult result) {
                if (result == null) throw new NullPointerException();
                mCaptureResult = result;
                return this;
            }

            public synchronized ImageSaverBuilder setCharacteristics(
                    final CameraCharacteristics characteristics) {
                if (characteristics == null) throw new NullPointerException();
                mCharacteristics = characteristics;
                return this;
            }

            public synchronized ImageSaver buildIfComplete() {
                if (!isComplete()) {
                    return null;
                }
                return new ImageSaver(mImage, mFile, mCaptureResult, mCharacteristics, mContext,
                        mReader);
            }

            public synchronized String getSaveLocation() {
                return (mFile == null) ? "Unknown" : mFile.toString();
            }

            private boolean isComplete() {
                return mImage != null && mFile != null && mCaptureResult != null
                        && mCharacteristics != null;
            }
        }
    }

    // 面积比较器
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    // 显示error用的对话框
    public static class ErrorDialog extends DialogFragment {
        private String mErrorMessage;
        public ErrorDialog() {
            mErrorMessage = "Unknown error occurred!";
        }
        // 用自定义的消息对话框
        public static ErrorDialog buildErrorDialog(String errorMessage) {
            ErrorDialog dialog = new ErrorDialog();
            dialog.mErrorMessage = errorMessage;
            return dialog;
        }
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(mErrorMessage)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    // 允许资源管理的AutoCloseable的wrapper
    public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
        private T mObject;
        private long mRefCount = 0;
        // wrap给定的对象
        public RefCountedAutoCloseable(T object) {
            if (object == null) throw new NullPointerException();
            mObject = object;
        }
        // 增加引用技术并返回wrap的对象
        public synchronized T getAndRetain() {
            if (mRefCount < 0) {
                return null;
            }
            mRefCount++;
            return mObject;
        }
        // 返回wrapped的对象
        public synchronized T get() {
            return mObject;
        }
        // 没有其他用户保留就较少引用并释放对象
        @Override
        public synchronized void close() {
            if (mRefCount >= 0) {
                mRefCount--;
                if (mRefCount < 0) {
                    try {
                        mObject.close();
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    finally {
                        mObject = null;
                    }
                }
            }
        }
    }

    // 从相机支持的尺寸里面选一个最优的
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        // 收集至少与预览surface一样大的受到支持的分辨率
        List<Size> bigEnough = new ArrayList<>();
        // 收集受到支持的小于预览surface的分辨率
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                }
                else {
                    notBigEnough.add(option);
                }
            }
        }
        // 从足够大的里面选最小的。没有足够大的，就从不够大的选最大的
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        }
        else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    // 生成包含当前日期和事件的格式化时间戳的string
    private static String generateTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US);
        return sdf.format(new Date());
    }

    // 如果给定的数组包含给定的整数就返回true
    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    // 如果两个给定的尺寸有相同的纵横比就返回true
    private static boolean checkAspectsEqual(Size a, Size b) {
        double aAspect = a.getWidth() / (double) a.getHeight();
        double bAspect = b.getWidth() / (double) b.getHeight();
        return Math.abs(aAspect - bAspect) <= ASPECT_RATIO_TOLERANCE;
    }

    // 旋转需要从相机传感器方向转换为设备的当前方向
    private static int sensorToDeviceRotation(CameraCharacteristics c, int deviceOrientation) {
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // 给定设备方向角度
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        // 前摄的话反转一下角度
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            deviceOrientation = -deviceOrientation;
        }
        // 计算相对于相机方向所需的JPEG方向，使图像相对于设备方向直立
        return (sensorOrientation - deviceOrientation + 360) % 360;
    }

    // 在UI显示一个text
    private void showToast(String text) {
        Message message = Message.obtain();
        message.obj = text;
        mMessageHandler.sendMessage(message);
    }

    // 给定请求完成了就从活动队列中删除，并将结果发送到后台线程来保存文件
    private void handleCompletionLocked(int requestId, ImageSaver.ImageSaverBuilder builder, TreeMap<Integer, ImageSaver.ImageSaverBuilder> queue) {
        if (builder == null) return;
        ImageSaver saver = builder.buildIfComplete();
        if (saver != null) {
            queue.remove(requestId);
            AsyncTask.THREAD_POOL_EXECUTOR.execute(saver);
        }
    }

    // 检查是否在使用只支持legacy硬件级别的设备
    private boolean isLegacyLocked() {
        return mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
    }

    // 启动预捕获序列的定时器
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    // 检查预捕获序列定时器是否以及命中
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    // 解释必要权限的对话
    public static class PermissionConfirmationDialog extends DialogFragment {
        public static PermissionConfirmationDialog newInstance() {
            return new PermissionConfirmationDialog();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                getActivity().finish();
                            }
                        })
                    .create();
        }

    }

}
