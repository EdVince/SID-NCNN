## SID文件夹说明

## 功能
将Learning to See in the Dark提供的模型转换成ncnn能运行的模型

## 使用
按照ipynb的序号，一个一个运行就好了，文件夹都是存各ipynb生成的模型的

## 实现说明
官方提供的是tf1.x训出来的ckpt模型，我们的目标模型格式是ncnn
1. ckpt转pb
2. pb转onnx，这个要用到tf2onnx这个库，可以直接pip下来
3. onnx转onnx，tf是hwc的，ncnn和onnx都是chw的，第二步转出来的模型会插入permute，这个东西有点不太正常，在onnx下微调一下模型的graph
4. onnx转ncnn，这个用ncnn提供的工具就行了，不多说

## 其他
test.ipynb里面是原始的ckpt数据的一个调用的代码，可以看看，用的测试图是上一个目录的train.ARW