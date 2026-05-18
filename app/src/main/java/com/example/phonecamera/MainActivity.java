package com.example.phonecamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 42;
    private static final int SERVER_PORT = 8080;
    private static final int RTSP_PORT = 8554;

    private TextureView preview;
    private TextView linkText;
    private TextView mjpegText;
    private TextView rtspText;
    private TextView statusText;
    private TextView connectionsText;
    private TextView zoomText;
    private Button toggleInfoButton;
    private ImageButton toggleAboutButton;
    private ScrollView infoScrollView;
    private LinearLayout infoContent;
    private SeekBar zoomSeekBar;
    private ImageButton switchCameraButton;
    private ImageButton orientationButton;
    private EditText userInput;
    private EditText passwordInput;
    private Button saveButton;

    private CameraStreamer cameraStreamer;
    private AacAudioEncoder audioEncoder;
    private MjpegServer mjpegServer;
    private RtspServer rtspServer;
    private ConnectionRegistry connectionRegistry;
    private SharedPreferences preferences;
    private boolean infoExpanded = false;
    private final Handler uiHandler = new Handler();
    private final Runnable connectionsRefresh = new Runnable() {
        @Override
        public void run() {
            updateConnectionsList();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("phone_camera", MODE_PRIVATE);
        connectionRegistry = new ConnectionRegistry();
        buildUi();
        loadCredentials();
        saveButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                saveCredentials();
            }
        });
        toggleInfoButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                toggleInfoPanel();
            }
        });
        toggleAboutButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                showAboutDialog();
            }
        });
        switchCameraButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                switchCamera();
            }
        });
        orientationButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                toggleOutputOrientation();
            }
        });
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && cameraStreamer != null) {
                    cameraStreamer.setZoom(progress);
                    updateZoomText();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        if (hasRequiredPermissions()) {
            startCameraAndServer();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_REQUEST);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraStreamer != null) {
            cameraStreamer.stop();
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
        }
        if (mjpegServer != null) {
            mjpegServer.stop();
        }
        if (rtspServer != null) {
            rtspServer.stop();
        }
        uiHandler.removeCallbacks(connectionsRefresh);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST && permissionsGranted(grantResults)) {
            startCameraAndServer();
        } else {
            showStatus("\u9700\u8981\u76f8\u673a\u548c\u9ea6\u514b\u98ce\u6743\u9650\u624d\u80fd\u542f\u52a8\u97f3\u89c6\u9891\u7f51\u7edc\u6444\u50cf\u5934");
        }
    }

    public void showStatus(String message) {
        statusText.setText(message);
    }

    public void updateCameraControls() {
        if (cameraStreamer == null) {
            return;
        }
        switchCameraButton.setEnabled(cameraStreamer.hasMultipleCameras());
        switchCameraButton.setContentDescription(cameraStreamer.isFrontCamera()
                ? "\u5207\u6362\u5230\u540e\u7f6e\u6444\u50cf\u5934"
                : "\u5207\u6362\u5230\u524d\u7f6e\u6444\u50cf\u5934");

        int maxZoom = cameraStreamer.getMaxZoom();
        boolean zoomSupported = maxZoom > 0 && cameraStreamer.isZoomSupported();
        zoomSeekBar.setEnabled(zoomSupported);
        zoomSeekBar.setMax(maxZoom);
        zoomSeekBar.setProgress(cameraStreamer.getZoom());
        updateZoomText();
        updateOrientationButton();
    }

    private boolean permissionsGranted(int[] grantResults) {
        if (grantResults.length == 0) {
            return false;
        }
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRequiredPermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 13, 24));

        preview = new AutoFitTextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        previewParams.gravity = Gravity.CENTER;
        preview.setLayoutParams(previewParams);
        root.addView(preview);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(rounded(Color.rgb(15, 23, 42), dp(18), Color.rgb(30, 41, 59), 1));
        root.addView(panel);

        toggleInfoButton = new Button(this);
        toggleInfoButton.setAllCaps(false);
        toggleInfoButton.setTextColor(Color.WHITE);
        toggleInfoButton.setTextSize(16);
        toggleInfoButton.setBackground(rounded(Color.rgb(14, 116, 144), dp(12), Color.rgb(103, 232, 249), 1));
        panel.addView(toggleInfoButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        infoScrollView = new ScrollView(this);
        panel.addView(infoScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(330)
        ));

        infoContent = new LinearLayout(this);
        infoContent.setOrientation(LinearLayout.VERTICAL);
        infoContent.setPadding(dp(4), dp(10), dp(4), 0);
        infoScrollView.addView(infoContent, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = label("\u7f51\u7edc\u6444\u50cf\u5934\u5730\u5740");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(226, 232, 240));
        infoContent.addView(title);

        linkText = label("\u6b63\u5728\u83b7\u53d6\u5c40\u57df\u7f51\u5730\u5740...");
        linkText.setTextSize(16);
        linkText.setTypeface(Typeface.MONOSPACE);
        linkText.setTextColor(Color.rgb(125, 211, 252));
        linkText.setPadding(0, dp(8), 0, dp(8));
        infoContent.addView(linkText);

        mjpegText = label("\u6b63\u5728\u542f\u52a8 MJPEG...");
        mjpegText.setTextSize(15);
        mjpegText.setTypeface(Typeface.MONOSPACE);
        mjpegText.setTextColor(Color.rgb(103, 232, 249));
        mjpegText.setPadding(0, 0, 0, dp(8));
        infoContent.addView(mjpegText);

        rtspText = label("\u6b63\u5728\u542f\u52a8 RTSP H.264...");
        rtspText.setTextSize(15);
        rtspText.setTypeface(Typeface.MONOSPACE);
        rtspText.setTextColor(Color.rgb(94, 234, 212));
        rtspText.setPadding(0, 0, 0, dp(8));
        infoContent.addView(rtspText);

        statusText = label("\u542f\u52a8\u4e2d...");
        statusText.setTextColor(Color.rgb(203, 213, 225));
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackground(rounded(Color.rgb(30, 41, 59), dp(10), Color.rgb(51, 65, 85), 1));
        infoContent.addView(statusText);

        connectionsText = label("\u5f53\u524d\u8fde\u63a5\uff1a0");
        connectionsText.setTypeface(Typeface.MONOSPACE);
        connectionsText.setTextColor(Color.rgb(226, 232, 240));
        connectionsText.setPadding(dp(10), dp(10), dp(10), dp(10));
        connectionsText.setBackground(rounded(Color.rgb(2, 6, 23), dp(10), Color.rgb(51, 65, 85), 1));
        infoContent.addView(connectionsText);

        LinearLayout cameraControls = new LinearLayout(this);
        cameraControls.setOrientation(LinearLayout.HORIZONTAL);
        cameraControls.setGravity(Gravity.CENTER_VERTICAL);
        cameraControls.setPadding(0, dp(10), 0, dp(4));
        infoContent.addView(cameraControls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        switchCameraButton = iconButton(R.drawable.ic_camera_switch, "\u5207\u6362\u6444\u50cf\u5934",
                Color.rgb(37, 99, 235), Color.rgb(96, 165, 250));
        switchCameraButton.setEnabled(false);
        cameraControls.addView(switchCameraButton, new LinearLayout.LayoutParams(
                dp(52),
                dp(48)
        ));

        orientationButton = iconButton(R.drawable.ic_orientation_portrait, "\u5207\u6362\u8f93\u51fa\u6a2a\u7ad6\u5c4f",
                Color.rgb(79, 70, 229), Color.rgb(129, 140, 248));
        LinearLayout.LayoutParams orientationParams = new LinearLayout.LayoutParams(dp(52), dp(48));
        orientationParams.leftMargin = dp(10);
        cameraControls.addView(orientationButton, orientationParams);

        zoomText = label("\u7f29\u653e\uff1a\u4e0d\u652f\u6301");
        zoomText.setGravity(Gravity.RIGHT);
        zoomText.setTextColor(Color.rgb(203, 213, 225));
        cameraControls.addView(zoomText, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        toggleAboutButton = iconButton(R.drawable.ic_about_spark, "\u5173\u4e8e\u7a0b\u5e8f",
                Color.rgb(88, 28, 135), Color.rgb(216, 180, 254));
        LinearLayout.LayoutParams aboutButtonParams = new LinearLayout.LayoutParams(
                dp(52),
                dp(48)
        );
        aboutButtonParams.leftMargin = dp(10);
        cameraControls.addView(toggleAboutButton, aboutButtonParams);

        zoomSeekBar = new SeekBar(this);
        zoomSeekBar.setEnabled(false);
        zoomSeekBar.setPadding(0, dp(12), 0, dp(8));
        infoContent.addView(zoomSeekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        TextView zoomTicks = label("0%        25%        50%        75%        100%");
        zoomTicks.setTextColor(Color.rgb(148, 163, 184));
        zoomTicks.setTextSize(12);
        zoomTicks.setGravity(Gravity.CENTER);
        infoContent.addView(zoomTicks);

        userInput = input("\u7528\u6237\u540d");
        passwordInput = input("\u5bc6\u7801");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        infoContent.addView(userInput);
        infoContent.addView(passwordInput);

        saveButton = new Button(this);
        saveButton.setText("\u4fdd\u5b58\u7528\u6237\u548c\u5bc6\u7801");
        saveButton.setAllCaps(false);
        saveButton.setTextColor(Color.rgb(8, 13, 24));
        saveButton.setBackground(rounded(Color.rgb(45, 212, 191), dp(12), Color.rgb(153, 246, 228), 1));
        saveButton.setGravity(Gravity.CENTER);
        infoContent.addView(saveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        applyInfoPanelState();
        updateOrientationButton();

        setContentView(root);
    }

    private void toggleOutputOrientation() {
        if (cameraStreamer == null) {
            return;
        }
        cameraStreamer.setOutputPortrait(!cameraStreamer.isOutputPortrait());
        updateOrientationButton();
        Toast.makeText(this, cameraStreamer.isOutputPortrait()
                ? "\u5df2\u5207\u6362\u5230\u7ad6\u5c4f\u8f93\u51fa"
                : "\u5df2\u5207\u6362\u5230\u6a2a\u5c4f\u8f93\u51fa", Toast.LENGTH_SHORT).show();
    }

    private void updateOrientationButton() {
        if (orientationButton == null) {
            return;
        }
        boolean portrait = cameraStreamer == null || cameraStreamer.isOutputPortrait();
        orientationButton.setImageResource(portrait ? R.drawable.ic_orientation_portrait : R.drawable.ic_orientation_landscape);
        orientationButton.setContentDescription(portrait ? "\u8f93\u51fa\u6a2a\u5c4f" : "\u8f93\u51fa\u7ad6\u5c4f");
    }

    private void toggleInfoPanel() {
        infoExpanded = !infoExpanded;
        applyInfoPanelState();
    }

    private void applyInfoPanelState() {
        if (infoScrollView == null || toggleInfoButton == null) {
            return;
        }
        infoScrollView.setVisibility(infoExpanded ? View.VISIBLE : View.GONE);
        toggleInfoButton.setText(infoExpanded
                ? "\u6536\u8d77\u8fde\u63a5\u4fe1\u606f"
                : "\u5c55\u5f00\u8fde\u63a5\u4fe1\u606f");
    }

    private void showAboutDialog() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.rgb(15, 23, 42));
        scrollView.addView(buildAboutContent(), new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(this)
                .setView(scrollView)
                .setPositiveButton("\u5173\u95ed", null)
                .show();
    }

    private LinearLayout buildAboutContent() {
        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);
        aboutContent.setPadding(dp(18), dp(18), dp(18), dp(10));

        TextView title = label("\u5173\u4e8e Phone Camera");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(248, 250, 252));
        aboutContent.addView(title);

        TextView version = label("\u7248\u672c\uff1a" + getVersionText());
        version.setTextColor(Color.rgb(203, 213, 225));
        version.setPadding(0, dp(8), 0, dp(4));
        aboutContent.addView(version);

        TextView author = label("\u4f5c\u8005\uff1aPyrrhus");
        author.setTextColor(Color.rgb(203, 213, 225));
        aboutContent.addView(author);

        TextView email = label("\u90ae\u7bb1\uff1azhangxuefeng@batonsoft.com");
        email.setTextColor(Color.rgb(125, 211, 252));
        email.setPadding(0, dp(4), 0, dp(10));
        aboutContent.addView(email);

        TextView donate = label("\u672c\u8f6f\u4ef6\u6c38\u4e45\u514d\u8d39\u5f00\u6e90\uff0c\u65e0\u5e7f\u544a\u3001\u65e0\u6346\u7ed1\u3001\u65e0\u529f\u80fd\u9650\u5236\u3002\n\n"
                + "\u82e5\u60a8\u89c9\u5f97\u8f6f\u4ef6\u597d\u7528\uff0c\u65e5\u5e38\u4f7f\u7528\u5e26\u6765\u4fbf\u5229\uff0c\u53ef\u81ea\u613f\u5c0f\u989d\u6350\u52a9\u652f\u6301\u4f5c\u8005\u6301\u7eed\u66f4\u65b0\u7ef4\u62a4\u3002\n\n"
                + "\u6350\u52a9\u5b8c\u5168\u81ea\u613f\uff0c\u4e0d\u6350\u52a9\u4e0d\u5f71\u54cd\u4efb\u4f55\u4f7f\u7528\u6743\u9650\uff0c\u6240\u6709\u529f\u80fd\u6c38\u4e45\u514d\u8d39\u5f00\u653e\u3002\n\n"
                + "\u611f\u8c22\u6bcf\u4e00\u4efd\u5584\u610f\u4e0e\u652f\u6301\uff01\u672c\u6350\u52a9\u4e3a\u7528\u6237\u81ea\u613f\u5584\u610f\u8d5e\u52a9\uff0c\u4e0d\u5c5e\u4e8e\u5546\u54c1\u4ea4\u6613\u3001\u4ed8\u8d39\u670d\u52a1\uff0c\u4e0d\u5bf9\u5e94\u4efb\u4f55\u5546\u54c1\u53ca\u6743\u76ca\uff0c\u4ec5\u7528\u4e8e\u652f\u6301\u5f00\u53d1\u8005\u65e5\u5e38\u5f00\u53d1\u7ef4\u62a4\u3002");
        donate.setTextColor(Color.rgb(226, 232, 240));
        donate.setTextSize(15);
        donate.setLineSpacing(0, 1.25f);
        donate.setPadding(dp(10), dp(10), dp(10), dp(10));
        donate.setBackground(rounded(Color.rgb(30, 41, 59), dp(10), Color.rgb(71, 85, 105), 1));
        aboutContent.addView(donate);

        ImageView qrCode = new ImageView(this);
        qrCode.setImageResource(getResources().getIdentifier("donate", "drawable", getPackageName()));
        qrCode.setAdjustViewBounds(true);
        qrCode.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrCode.setBackgroundColor(Color.TRANSPARENT);
        qrCode.setPadding(0, 0, 0, 0);
        qrCode.setContentDescription("\u652f\u4ed8\u5b9d\u6350\u52a9\u6536\u6b3e\u7801");
        qrCode.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                saveDonateImage();
                return true;
            }
        });
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280)
        );
        imageParams.setMargins(0, dp(12), 0, dp(8));
        aboutContent.addView(qrCode, imageParams);

        TextView saveHint = label("\u957f\u6309\u4e8c\u7ef4\u7801\u53ef\u4fdd\u5b58\u56fe\u7247");
        saveHint.setGravity(Gravity.CENTER);
        saveHint.setTextColor(Color.rgb(148, 163, 184));
        saveHint.setPadding(0, 0, 0, dp(8));
        aboutContent.addView(saveHint);
        return aboutContent;
    }

    private String getVersionText() {
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.getLongVersionCode();
            } else {
                versionCode = packageInfo.versionCode;
            }
            return packageInfo.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0 (1)";
        }
    }

    private void saveDonateImage() {
        InputStream input = null;
        OutputStream output = null;
        try {
            File directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (directory == null) {
                directory = getFilesDir();
            }
            if (!directory.exists() && !directory.mkdirs()) {
                Toast.makeText(this, "\u4fdd\u5b58\u5931\u8d25\uff1a\u65e0\u6cd5\u521b\u5efa\u76ee\u5f55", Toast.LENGTH_SHORT).show();
                return;
            }
            File target = new File(directory, "phone_camera_donate.jpg");
            input = getResources().openRawResource(getResources().getIdentifier("donate", "drawable", getPackageName()));
            output = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            Toast.makeText(this, "\u5df2\u4fdd\u5b58\uff1a" + target.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "\u4fdd\u5b58\u5931\u8d25", Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(203, 213, 225));
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.rgb(148, 163, 184));
        editText.setBackgroundColor(Color.TRANSPARENT);
        editText.setPadding(0, dp(10), 0, dp(8));
        return editText;
    }

    private ImageButton iconButton(int iconRes, String description, int backgroundColor, int strokeColor) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(rounded(backgroundColor, dp(12), strokeColor, 1));
        return button;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private void loadCredentials() {
        userInput.setText(preferences.getString("username", "admin"));
        passwordInput.setText(preferences.getString("password", "123456"));
    }

    private void saveCredentials() {
        String username = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (username.length() == 0 || password.length() == 0) {
            Toast.makeText(this, "\u7528\u6237\u540d\u548c\u5bc6\u7801\u4e0d\u80fd\u4e3a\u7a7a", Toast.LENGTH_SHORT).show();
            return;
        }
        preferences.edit()
                .putString("username", username)
                .putString("password", password)
                .apply();
        if (mjpegServer != null) {
            mjpegServer.setCredentials(username, password);
        }
        if (rtspServer != null) {
            rtspServer.setCredentials(username, password);
            updateRtspLink(username, password);
        }
        Toast.makeText(this, "\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show();
    }

    private void startCameraAndServer() {
        cameraStreamer = new CameraStreamer(this, preview);
        cameraStreamer.start();
        audioEncoder = new AacAudioEncoder();
        boolean audioStarted = audioEncoder.start();
        updateCameraControls();

        mjpegServer = new MjpegServer(SERVER_PORT, new MjpegServer.FrameProvider() {
            @Override
            public byte[] getLatestJpeg() {
                return cameraStreamer.getLatestJpeg();
            }
        }, new MjpegServer.CameraControl() {
            @Override
            public String getStatusJson() {
                return cameraStatusJson("");
            }

            @Override
            public String setZoom(final int value) {
                runOnUiThreadAndWait(new Runnable() {
                    @Override
                    public void run() {
                        if (cameraStreamer != null) {
                            cameraStreamer.setZoom(value);
                            updateCameraControls();
                        }
                    }
                });
                return cameraStatusJson("\u5df2\u8bbe\u7f6e\u7f29\u653e");
            }

            @Override
            public String switchCamera() {
                final boolean[] switched = new boolean[]{false};
                runOnUiThreadAndWait(new Runnable() {
                    @Override
                    public void run() {
                        if (cameraStreamer != null) {
                            switched[0] = cameraStreamer.switchCamera();
                            updateCameraControls();
                        }
                    }
                });
                return cameraStatusJson(switched[0] ? "\u5df2\u5207\u6362\u6444\u50cf\u5934" : "\u6b64\u8bbe\u5907\u53ea\u6709\u4e00\u4e2a\u6444\u50cf\u5934");
            }

            @Override
            public String toggleOutputOrientation() {
                runOnUiThreadAndWait(new Runnable() {
                    @Override
                    public void run() {
                        if (cameraStreamer != null) {
                            cameraStreamer.setOutputPortrait(!cameraStreamer.isOutputPortrait());
                            updateOrientationButton();
                        }
                    }
                });
                return cameraStatusJson(cameraStreamer != null && cameraStreamer.isOutputPortrait()
                        ? "\u5df2\u5207\u6362\u5230\u7ad6\u5c4f\u8f93\u51fa"
                        : "\u5df2\u5207\u6362\u5230\u6a2a\u5c4f\u8f93\u51fa");
            }
        }, connectionRegistry);
        mjpegServer.setCredentials(
                preferences.getString("username", "admin"),
                preferences.getString("password", "123456")
        );
        mjpegServer.start();

        rtspServer = new RtspServer(RTSP_PORT, new RtspServer.FrameProvider() {
            @Override
            public H264Encoder.H264Frame getLatestH264Frame() {
                return cameraStreamer.getLatestH264Frame();
            }

            @Override
            public byte[] getSps() {
                return cameraStreamer.getH264Sps();
            }

            @Override
            public byte[] getPps() {
                return cameraStreamer.getH264Pps();
            }

            @Override
            public AacAudioEncoder.AacFrame getLatestAacFrame() {
                return audioEncoder == null ? null : audioEncoder.getLatestFrame();
            }

            @Override
            public String getAacConfig() {
                return audioEncoder == null ? "1208" : audioEncoder.getAudioSpecificConfigHex();
            }
        }, connectionRegistry);
        rtspServer.setCredentials(
                preferences.getString("username", "admin"),
                preferences.getString("password", "123456")
        );
        rtspServer.start();

        String ipAddress = findIpAddress();
        String pageUrl = "http://" + ipAddress + ":" + SERVER_PORT + "/";
        linkText.setText(pageUrl);
        mjpegText.setText("http://" + ipAddress + ":" + SERVER_PORT + "/stream.mjpg");
        updateRtspLink(preferences.getString("username", "admin"), preferences.getString("password", "123456"));
        showStatus(audioStarted
                ? "\u540c\u4e00 Wi-Fi \u4e0b\u53ef\u7528\u6d4f\u89c8\u5668\u6253\u5f00\u63a7\u5236\u9875\uff0cVLC \u6216 ffmpeg \u6253\u5f00 RTSP H.264/AAC \u94fe\u63a5"
                : "\u89c6\u9891\u5df2\u542f\u52a8\uff0c\u4f46\u9ea6\u514b\u98ce\u6216 AAC \u7f16\u7801\u5668\u542f\u52a8\u5931\u8d25");
        uiHandler.removeCallbacks(connectionsRefresh);
        uiHandler.post(connectionsRefresh);
    }

    private void updateConnectionsList() {
        if (connectionRegistry == null || connectionsText == null) {
            return;
        }
        List<ConnectionRegistry.ConnectionInfo> connections = connectionRegistry.snapshot();
        List<ConnectionRegistry.ConnectionInfo> history = connectionRegistry.historySnapshot();
        StringBuilder builder = new StringBuilder();
        builder.append("\u5f53\u524d\u8fde\u63a5\uff1a").append(connections.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < connections.size(); i++) {
            ConnectionRegistry.ConnectionInfo connection = connections.get(i);
            long seconds = Math.max(0, (now - connection.connectedAtMs) / 1000);
            builder.append('\n')
                    .append(connection.protocol)
                    .append("  ")
                    .append(connection.username)
                    .append("@")
                    .append(connection.address)
                    .append("  ")
                    .append(formatDuration(seconds));
        }
        builder.append('\n').append("\u8fde\u63a5\u5386\u53f2\uff1a").append(history.size());
        for (int i = 0; i < history.size(); i++) {
            ConnectionRegistry.ConnectionInfo connection = history.get(i);
            long seconds = Math.max(0, (connection.disconnectedAtMs - connection.connectedAtMs) / 1000);
            builder.append('\n')
                    .append(connection.protocol)
                    .append("  ")
                    .append(connection.username)
                    .append("@")
                    .append(connection.address)
                    .append("  ")
                    .append(formatDuration(seconds))
                    .append("  ")
                    .append(formatClock(connection.connectedAtMs))
                    .append("-")
                    .append(formatClock(connection.disconnectedAtMs));
        }
        connectionsText.setText(builder.toString());
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remain = seconds % 60;
        if (minutes == 0) {
            return remain + "s";
        }
        return minutes + "m " + remain + "s";
    }

    private String formatClock(long timeMs) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = calendar.get(java.util.Calendar.MINUTE);
        int second = calendar.get(java.util.Calendar.SECOND);
        return twoDigits(hour) + ":" + twoDigits(minute) + ":" + twoDigits(second);
    }

    private String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private void updateRtspLink(String username, String password) {
        String ipAddress = findIpAddress();
        String rtspUrl = "rtsp://" + username + ":" + password + "@" + ipAddress + ":" + RTSP_PORT + "/camera";
        rtspText.setText(rtspUrl);
    }

    private void switchCamera() {
        if (cameraStreamer == null) {
            return;
        }
        if (cameraStreamer.switchCamera()) {
            updateCameraControls();
            Toast.makeText(this, "\u5df2\u5207\u6362\u6444\u50cf\u5934", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "\u6b64\u8bbe\u5907\u53ea\u6709\u4e00\u4e2a\u6444\u50cf\u5934", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateZoomText() {
        if (cameraStreamer == null || !cameraStreamer.isZoomSupported() || cameraStreamer.getMaxZoom() == 0) {
            zoomText.setText("\u7f29\u653e\uff1a\u4e0d\u652f\u6301");
            return;
        }
        int zoom = cameraStreamer.getZoom();
        int maxZoom = cameraStreamer.getMaxZoom();
        int percent = maxZoom == 0 ? 0 : (zoom * 100 / maxZoom);
        zoomText.setText("\u7f29\u653e\uff1a" + percent + "%");
    }

    private String cameraStatusJson(String message) {
        if (cameraStreamer == null) {
            return "{\"multipleCameras\":false,\"frontCamera\":false,\"outputPortrait\":true,\"zoomSupported\":false,\"maxZoom\":0,\"zoom\":0,\"message\":\"Camera not ready\"}";
        }
        boolean zoomSupported = cameraStreamer.isZoomSupported() && cameraStreamer.getMaxZoom() > 0;
        return "{"
                + "\"multipleCameras\":" + cameraStreamer.hasMultipleCameras() + ","
                + "\"frontCamera\":" + cameraStreamer.isFrontCamera() + ","
                + "\"outputPortrait\":" + cameraStreamer.isOutputPortrait() + ","
                + "\"zoomSupported\":" + zoomSupported + ","
                + "\"maxZoom\":" + cameraStreamer.getMaxZoom() + ","
                + "\"zoom\":" + cameraStreamer.getZoom() + ","
                + "\"message\":\"" + jsonEscape(message) + "\""
                + "}";
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void runOnUiThreadAndWait(final Runnable action) {
        if (Thread.currentThread() == getMainLooper().getThread()) {
            action.run();
            return;
        }
        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String findIpAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
