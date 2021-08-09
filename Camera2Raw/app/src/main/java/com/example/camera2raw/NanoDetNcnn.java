package com.example.camera2raw;

import android.content.res.AssetManager;

public class NanoDetNcnn {

    public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
    public native boolean detectDraw(int w, int h, int[] pixArr, Object bitmap);

    static {
        System.loadLibrary("nanodetncnn");
    }

}
