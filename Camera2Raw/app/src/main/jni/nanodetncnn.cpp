// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>
#include <algorithm>

#include <platform.h>
#include <benchmark.h>

#include "nanodet.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/opencv.hpp>
#include <opencv2/core/mat.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON

static int draw_fps(cv::Mat& rgb, int ratio, int ms)
{
    char text[32];
    sprintf(text, "R=%d T:%dms", ratio, ms);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 2, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);
    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 2, cv::Scalar(0, 0, 0));

    return 0;
}

static int convertPoint(cv::Size sizeFull, cv::Size sizeCenter, cv::Point relative) {
    cv::Point offset((sizeFull.width-sizeCenter.width)/2,(sizeFull.height-sizeCenter.height)/2);
    cv::Point absolute(offset.x+relative.x,offset.y+relative.y);
    return absolute.y*sizeFull.width+absolute.x;
}

static NanoDet* g_nanodet = 0;
static ncnn::Mutex lock;

void MatToBitmap2(JNIEnv *env, cv::Mat& mat, jobject& bitmap, jboolean needPremultiplyAlpha) {
    AndroidBitmapInfo info;
    void *pixels = 0;
    cv::Mat &src = mat;

    CV_Assert(AndroidBitmap_getInfo(env, bitmap, &info) >= 0);
    CV_Assert(info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 || info.format == ANDROID_BITMAP_FORMAT_RGB_565);
    CV_Assert(src.dims == 2 && info.height == (uint32_t) src.rows && info.width == (uint32_t) src.cols);
    CV_Assert(src.type() == CV_8UC1 || src.type() == CV_8UC3 || src.type() == CV_8UC4);
    CV_Assert(AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0);
    CV_Assert(pixels);
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
        if (src.type() == CV_8UC1) {
            cvtColor(src, tmp, cv::COLOR_GRAY2RGBA);
        } else if (src.type() == CV_8UC3) {
            cvtColor(src, tmp, cv::COLOR_RGB2RGBA);
        } else if (src.type() == CV_8UC4) {
            if (needPremultiplyAlpha)
                cvtColor(src, tmp, cv::COLOR_RGBA2mRGBA);
            else
                src.copyTo(tmp);
        }
    } else {
        cv::Mat tmp(info.height, info.width, CV_8UC2, pixels);
        if (src.type() == CV_8UC1) {
            cvtColor(src, tmp, cv::COLOR_GRAY2BGR565);
        } else if (src.type() == CV_8UC3) {
            cvtColor(src, tmp, cv::COLOR_RGB2BGR565);
        } else if (src.type() == CV_8UC4) {
            cvtColor(src, tmp, cv::COLOR_RGBA2BGR565);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return;
}

void MatToBitmap(JNIEnv *env, cv::Mat& mat, jobject& bitmap) {
    MatToBitmap2(env, mat, bitmap, false);
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_example_camera2raw_NanoDetNcnn_detectDraw(JNIEnv* env, jobject thiz,
                                                                              jint jw, jint jh, jintArray jPixArr,
                                                                              jobject bitmap)
{
    jint *cPixArr = env->GetIntArrayElements(jPixArr, JNI_FALSE);
    if (cPixArr == NULL) {
        return JNI_FALSE;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    double t0 = ncnn::get_current_time();
    // 进来的数据是height*width*4的一维数据，从中间centercrop一个(512,512,4)的ncnn::Mat出来
    float gMin = 0.0;
    ncnn::Mat in(512,512,4);
    float* in0 = in.channel(0);
    float* in1 = in.channel(1);
    float* in2 = in.channel(2);
    float* in3 = in.channel(3);
    for(int h = 0; h < 512; h++) {
        for(int w = 0; w < 512; w++) {
            in1[h*512+w] = std::min(std::max(cPixArr[convertPoint(cv::Size(jw,jh),cv::Size(2*512,2*512),cv::Point(2*w+0,2*h+0))], 0) ,1023) / 1023.0f;
            in0[h*512+w] = std::min(std::max(cPixArr[convertPoint(cv::Size(jw,jh),cv::Size(2*512,2*512),cv::Point(2*w+1,2*h+0))], 0) ,1023) / 1023.0f;
            in2[h*512+w] = std::min(std::max(cPixArr[convertPoint(cv::Size(jw,jh),cv::Size(2*512,2*512),cv::Point(2*w+0,2*h+1))], 0) ,1023) / 1023.0f;
            in3[h*512+w] = std::min(std::max(cPixArr[convertPoint(cv::Size(jw,jh),cv::Size(2*512,2*512),cv::Point(2*w+1,2*h+1))], 0) ,1023) / 1023.0f;
            gMin = std::max(gMin,std::max(std::max(in0[h*512+w],in1[h*512+w]),std::max(in2[h*512+w],in3[h*512+w])));
        }
    }
    int ratio = ((int)(1/gMin)); // 计算一下理论要放大到的亮度
    ratio = std::min(ratio,300); // 不能超过300倍
    ratio = std::max(ratio,1); // 不能小于1倍
    for(int i = 0; i < 512*512; i++) {
        in0[i] *= ratio;
        in1[i] *= ratio;
        in2[i] *= ratio;
        in3[i] *= ratio;
    }

    cv::Mat rgb(cv::Size(1024, 1024), CV_8UC3);

    // 将RAW图喂入ncnn进行推理
    {
        ncnn::MutexLockGuard g(lock);
        g_nanodet->detect(in,rgb);
    }
    double t1 = ncnn::get_current_time();

    draw_fps(rgb, ratio, t1-t0);

    MatToBitmap(env,rgb,bitmap);

    // 释放掉C数组
    env->ReleaseIntArrayElements(jPixArr, cPixArr, 0);

    ////////////////////////////////////////////////////////////////////////////////////////////////

    return JNI_TRUE;
}

// public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_example_camera2raw_NanoDetNcnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu)
{
    // 检查一下选的设备是不是在范围
    if (modelid < 0 || modelid > 6 || cpugpu < 0 || cpugpu > 1) {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadM odel %p", mgr);

    const char* modeltype = "model";
    int target_size = 512;
    bool use_gpu = (int)cpugpu == 1;

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        if (use_gpu && ncnn::get_gpu_count() == 0)
        {
            // no gpu
            delete g_nanodet;
            g_nanodet = 0;
        }
        else
        {
            if (!g_nanodet)
                g_nanodet = new NanoDet;
            g_nanodet->load(mgr, modeltype, target_size, use_gpu);
        }
    }

    return JNI_TRUE;
}

}
