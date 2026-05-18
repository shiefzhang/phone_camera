package com.example.phonecamera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;

    public AutoFitTextureView(Context context) {
        super(context);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height);
            return;
        }
        if (width * ratioHeight < height * ratioWidth) {
            setMeasuredDimension(width, width * ratioHeight / ratioWidth);
        } else {
            setMeasuredDimension(height * ratioWidth / ratioHeight, height);
        }
    }
}
