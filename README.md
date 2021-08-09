本项目是将CVPR2018的“Learning to See in the Dark”的暗光成像，用ncnn在安卓上进行简单的部署实现。

“Learning to See in the Dark”的主要工作是用了一个U-net like的模型，将摄像头原始输出的RAW图像，进行暗光增强，输出RGB图像。



本项目有三个文件夹，分三个大的工作步骤，按照操作顺序说明一下：

SID：对SID的模型进行简单的测试，同时进行模型的转换：ckpt->pb->onnx->onnx->ncnn

Win：在windows上对上一步生成的ncnn模型，进行测试

Camera2Raw：在安卓上用ncnn部署生成的ncnn模型。



对模型转换和windows测试没兴趣的可以跳过，直接用Android Studio打开Camera2Raw编译生成就好。



注意：安卓的工程，经过测试发现不同的手机效果不太一样，有些手机可能app能使用，但是效果出不来，目前测试成功效果正常的有：Samsung Galaxy S9，经测试效果不正常的有：Xiaomi 8



相关依赖参考：

Learning to See in the Dark：https://github.com/cchen156/Learning-to-See-in-the-Dark

windows上运行ncnn模型：https://github.com/EdVince/Ncnn-Win/tree/main/vs2019_ncnn_opencv-mobile_demo

Android相机获取RAW图像：https://github.com/EdVince/Android_learning/tree/main/Camera2Raw