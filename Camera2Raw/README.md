## Camera2Raw文件夹说明

## 功能
在Android下用ncnn和opencv-mobile跑起来转换出来的ncnn模型

## 使用
Android打开就好了，这个工程我是用以前的工程改的，原工程在这：
https://github.com/EdVince/Android_learning/tree/main/Camera2Raw

## 实现说明
这个最麻烦的其实是RAW图像的获取，这里用的是Camera2这个API，原工程是参考google改写的，这里我在raw图像保存的地方把raw数据的byte数组取了出来，然后像素合并成int数组(raw数据是16bit的，一个像素是两个byte)，调用jni的ncnn推理代码，返回的结果是个Bitmap，最后在ImageView里面把Bitmap显示出来