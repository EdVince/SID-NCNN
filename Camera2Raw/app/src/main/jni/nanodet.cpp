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

#include "nanodet.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "cpu.h"

NanoDet::NanoDet()
{
    blob_pool_allocator.set_size_compare_ratio(0.f);
    workspace_pool_allocator.set_size_compare_ratio(0.f);
}

int NanoDet::load(AAssetManager* mgr, const char* modeltype, int _target_size, bool use_gpu)
{
    // 把原有的环境清空一下
    nanodet.clear();
    blob_pool_allocator.clear();
    workspace_pool_allocator.clear();

    ncnn::set_cpu_powersave(2);
    ncnn::set_omp_num_threads(ncnn::get_big_cpu_count());

    nanodet.opt = ncnn::Option();

#if NCNN_VULKAN
    nanodet.opt.use_vulkan_compute = use_gpu;
#endif

    nanodet.opt.num_threads = ncnn::get_big_cpu_count();
    nanodet.opt.blob_allocator = &blob_pool_allocator;
    nanodet.opt.workspace_allocator = &workspace_pool_allocator;

    // 加载模型和设置模型对应的一些参数
    char parampath[256];
    char modelpath[256];
    sprintf(parampath, "nanodet-%s.param", modeltype);
    sprintf(modelpath, "nanodet-%s.bin", modeltype);
    nanodet.load_param(mgr, parampath);
    nanodet.load_model(mgr, modelpath);
    target_size = _target_size;

    return 0;
}

int NanoDet::detect(const ncnn::Mat& raw, cv::Mat& rgb)
{
    // 模型推理
    ncnn::Extractor ex = nanodet.create_extractor();
    ex.input("in_raw", raw);
    ncnn::Mat out;
    ex.extract("g_conv10/BiasAdd:0", out);

    // depth to space
    ncnn::Mat res(1024, 1024, 3);
    float* ptr0 = out.channel(0);
    float* ptr1 = out.channel(1);
    float* ptr2 = out.channel(2);
    float* ptr3 = out.channel(3);
    float* ptr4 = out.channel(4);
    float* ptr5 = out.channel(5);
    float* ptr6 = out.channel(6);
    float* ptr7 = out.channel(7);
    float* ptr8 = out.channel(8);
    float* ptr9 = out.channel(9);
    float* ptr10 = out.channel(10);
    float* ptr11 = out.channel(11);
    float* res0 = res.channel(0);
    float* res1 = res.channel(1);
    float* res2 = res.channel(2);
    for (int h = 0; h < 512; h++) {
        for (int w = 0; w < 512; w++) {
            int idx_in = w * 512 + h;
            int idx_out = (2 * h) * 1024 + 2 * w;
            res0[idx_out] = 255.0 * std::min(std::max(ptr0[idx_in], 0.0f), 1.0f);
            res1[idx_out] = 255.0 * std::min(std::max(ptr1[idx_in], 0.0f), 1.0f);
            res2[idx_out] = 255.0 * std::min(std::max(ptr2[idx_in], 0.0f), 1.0f);
            res0[idx_out + 1] = 255.0 * std::min(std::max(ptr3[idx_in], 0.0f), 1.0f);
            res1[idx_out + 1] = 255.0 * std::min(std::max(ptr4[idx_in], 0.0f), 1.0f);
            res2[idx_out + 1] = 255.0 * std::min(std::max(ptr5[idx_in], 0.0f), 1.0f);
            res0[idx_out + 1024] = 255.0 * std::min(std::max(ptr6[idx_in], 0.0f), 1.0f);
            res1[idx_out + 1024] = 255.0 * std::min(std::max(ptr7[idx_in], 0.0f), 1.0f);
            res2[idx_out + 1024] = 255.0 * std::min(std::max(ptr8[idx_in], 0.0f), 1.0f);
            res0[idx_out + 1024 + 1] = 255.0 * std::min(std::max(ptr9[idx_in], 0.0f), 1.0f);
            res1[idx_out + 1024 + 1] = 255.0 * std::min(std::max(ptr10[idx_in], 0.0f), 1.0f);
            res2[idx_out + 1024 + 1] = 255.0 * std::min(std::max(ptr11[idx_in], 0.0f), 1.0f);
        }
    }

    // 转opencv
    res.to_pixels(rgb.data,ncnn::Mat::PIXEL_RGB);
    cv::flip(rgb, rgb, 1);

    return 0;
}
