#include "net.h"
#include <algorithm>
#include <opencv2/core/core.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <vector>
#include <iostream>
#include <fstream>
#include "raw.h"

using namespace std;

int main()
{
    // 创建模型
    cout << "create model" << endl;
    ncnn::Net sid;
    sid.load_param("res/model.param");
    sid.load_model("res/model.bin");

    // 产生输入
    cout << "generate in data" << endl;
    ncnn::Mat in(512, 512, 4);
    float* in0 = in.channel(0);
    float* in1 = in.channel(1);
    float* in2 = in.channel(2);
    float* in3 = in.channel(3);
    for (int h = 0; h < 512; h++) {
        for (int w = 0; w < 512; w++) {
            in0[h * 512 + w] = raw[4 * (h * 512 + w) + 0];
            in1[h * 512 + w] = raw[4 * (h * 512 + w) + 1];
            in2[h * 512 + w] = raw[4 * (h * 512 + w) + 2];
            in3[h * 512 + w] = raw[4 * (h * 512 + w) + 3];
        }
    }

    // 模型推理
    cout << "model extractor" << endl;
    ncnn::Extractor ex = sid.create_extractor();
    ex.input("in_raw", in);
    ncnn::Mat out;
    ex.extract("g_conv10/BiasAdd:0", out);

    // 获取输出
    cout << "do depth to space" << endl;
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
            int idx_in = h * 512 + w;
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

    cv::Mat img(cv::Size(1024, 1024), CV_8UC3);
    res.to_pixels(img.data, ncnn::Mat::PIXEL_BGR);
    cv::imwrite("save.png", img);

    return 0;
}
