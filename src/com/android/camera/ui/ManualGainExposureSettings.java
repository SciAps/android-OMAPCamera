package com.android.camera.ui;

import com.android.camera.R;
import com.android.camera.Camera;

import android.app.Dialog;
import android.opengl.Visibility;
import android.os.Bundle;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class ManualGainExposureSettings extends Dialog {
    // 3D Variables
    private View manualGainExposurePanel;
    private SeekBar manualExposureControlLeft, manualExposureControlRight, manualGainControlLeft, manualGainControlRight;
    private TextView manualExposureCaptionLeft, manualExposureCaptionRight, manualGainCaptionLeft, manualGainCaptionRight;
    private int mManualExposureValueLeft, mManualExposureValueRight;
    private Handler cameraHandler;
    private int mProgressBarExposureValueLeft, mProgressBarExposureValueRight;
    private int mProgressBarGainValueLeft, mProgressBarGainValueRight;
    private int mManualGainValueLeft, mManualGainValueRight;
    private int mMinIso, mMaxIso;
    private int mExpRightValue,mExpLeftValue,mIsoRightValue,mIsoLeftValue;

    // 2D Variables
    private int mManualExposureValue,mManualGainValue;
    private int mExposureValue, mISOValue;
    private SeekBar manualExposureControl, manualGainControl;
    private TextView manualExposureCaption, manualGainCaption;
    private int mProgressBarExposureValue, mProgressBarGainValue;

    private Bundle data;

    // 2D Constructor
    public ManualGainExposureSettings (final Context context, final Handler handler,
            int expValue, int isoValue,
            int minExposure, int maxExposure,
            int minIso, int maxIso,
            int stepExposure, int stepIso) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Given default values
        mManualExposureValue = expValue;
        mManualGainValue = isoValue;
        mMinIso = minIso;
        mMaxIso = maxIso;
        mExposureValue = expValue;
        mISOValue = isoValue;
        cameraHandler = handler;
        setContentView(R.layout.manual_gain_exposure_slider_control);

        // Views handlers
        manualGainExposurePanel = findViewById(R.id.panel);

        manualExposureControl = (SeekBar) findViewById(R.id.exposure_seek);
        manualGainControl = (SeekBar) findViewById(R.id.gain_seek);

        manualExposureControl.setMax(maxExposure-1);
        manualGainControl.setMax(maxIso-mMinIso);

        manualExposureControl.setKeyProgressIncrement(stepExposure);
        manualGainControl.setKeyProgressIncrement(stepIso);

        manualExposureCaption = (TextView) findViewById(R.id.exposure_caption);
        manualGainCaption = (TextView) findViewById(R.id.gain_caption);

        String sCaptionExposure = context.getString(R.string.settings_manual_2d_exposure_caption);
        sCaptionExposure = sCaptionExposure + " " + Integer.toString(mManualExposureValue);
        manualExposureCaption.setText(sCaptionExposure);
        mProgressBarExposureValue = mManualExposureValue;
        manualExposureControl.setProgress(mProgressBarExposureValue);

        String sCaptionGain = context.getString(R.string.settings_manual_2d_gain_caption);
        sCaptionGain = sCaptionGain + " " + Integer.toString(mManualGainValue);
        manualGainCaption.setText(sCaptionGain);
        mProgressBarGainValue = mManualGainValue - mMinIso;
        manualGainControl.setProgress(mProgressBarGainValue);

        manualGainExposurePanel.setVisibility(View.VISIBLE);

        Button btn = (Button) findViewById(R.id.buttonOK);
        btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                Message msg = new Message();

                data = new Bundle ();
                if (mManualExposureValue != mExposureValue) {
                    data.putInt("EXPOSURE", mManualExposureValue);
                } else {
                    data.putInt("EXPOSURE", mExposureValue);
                }
                if (mManualGainValue != mISOValue) {
                    data.putInt("ISO", mManualGainValue + mMinIso);
                } else {
                    data.putInt("ISO", mISOValue);
                }

                msg.what = Camera.MANUAL_GAIN_EXPOSURE_CHANGED;
                msg.setData(data);
                cameraHandler.sendMessage(msg);
                dismiss();
                }
        });

        manualExposureControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar,
                    int progress, boolean fromUser) {
                        Message msg = new Message();
                        data = new Bundle ();
                        manualExposureCaption = (TextView) findViewById(R.id.exposure_caption);
                        mManualExposureValue = progress+1;
                        String sCaptionLeft = context.getString(R.string.settings_manual_2d_exposure_caption);
                        sCaptionLeft = sCaptionLeft + " " + Integer.toString(mManualExposureValue);
                        manualExposureCaption.setText(sCaptionLeft);
//                        if (mManualExposureValue != mExposureValue) {
//                            data.putInt("EXPOSURE", mManualExposureValue);
//                        } else {
//                            data.putInt("EXPOSURE", mExposureValue);
//                        }
//                        msg.what = Camera.MANUAL_GAIN_EXPOSURE_CHANGED;
//                        msg.setData(data);
//                        cameraHandler.sendMessage(msg);
                    }
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        manualGainControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar,
                    int progress, boolean fromUser) {
                Message msg = new Message();
                data = new Bundle ();
                manualGainCaption = (TextView) findViewById(R.id.gain_caption);
                mManualGainValue = progress;
                String sCaptionLeft = context.getString(R.string.settings_manual_2d_gain_caption);
                sCaptionLeft = sCaptionLeft + " " + Integer.toString(mManualGainValue + mMinIso);
                manualGainCaption.setText(sCaptionLeft);
//                if (mManualGainValue != mISOValue) {
//                    data.putInt("ISO", mManualGainValue + mMinIso);
//                } else {
//                    data.putInt("ISO", mISOValue);
//                }
//                msg.what = Camera.MANUAL_GAIN_EXPOSURE_CHANGED;
//                msg.setData(data);
//                cameraHandler.sendMessage(msg);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        manualGainCaption.setVisibility(View.VISIBLE);
        manualExposureCaption.setVisibility(View.VISIBLE);
        manualGainControl.setVisibility(View.VISIBLE);
        manualExposureControl.setVisibility(View.VISIBLE);
    }
}
