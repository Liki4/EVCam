package com.kooo.evcam;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextWatcher;
import android.text.Editable;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * 补盲选项设置界面
 */
public class BlindSpotSettingsFragment extends Fragment {
    private static final String TAG = "BlindSpotSettingsFragment";

    private SwitchMaterial mainFloatingSwitch;
    private Spinner mainFloatingCameraSpinner;
    
    private SwitchMaterial turnSignalLinkageSwitch;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private SwitchMaterial reuseMainFloatingSwitch;
    private Button setupBlindSpotPosButton;

    private SwitchMaterial secondaryDisplaySwitch;
    private Spinner cameraSpinner;
    private Spinner displaySpinner;
    private TextView displayInfoText;
    private SeekBar seekbarX, seekbarY, seekbarWidth, seekbarHeight;
    private EditText etX, etY, etWidth, etHeight;
    private Spinner rotationSpinner;
    private Spinner orientationSpinner;
    private SwitchMaterial borderSwitch;
    private Button mockLeftButton, mockRightButton;
    private View joystickContainer, joystickHandle;
    private Button saveButton;
    private Button logcatDebugButton;
    private android.widget.EditText logFilterEditText;
    private Button menuButton;
    private Button homeButton;

    private AppConfig appConfig;
    private DisplayManager displayManager;
    private List<Display> availableDisplays = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        displayManager = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
        initViews(view);
        loadSettings();
        setupListeners();
        return view;
    }

    private void initViews(View view) {
        // 主屏悬浮窗
        mainFloatingSwitch = view.findViewById(R.id.switch_main_floating);
        mainFloatingCameraSpinner = view.findViewById(R.id.spinner_main_floating_camera);

        // 转向灯联动
        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        reuseMainFloatingSwitch = view.findViewById(R.id.switch_reuse_main_floating);
        setupBlindSpotPosButton = view.findViewById(R.id.btn_setup_blind_spot_pos);

        // 副屏显示
        secondaryDisplaySwitch = view.findViewById(R.id.switch_secondary_display);
        cameraSpinner = view.findViewById(R.id.spinner_camera_selection);
        displaySpinner = view.findViewById(R.id.spinner_display_selection);
        displayInfoText = view.findViewById(R.id.tv_display_info);
        seekbarX = view.findViewById(R.id.seekbar_x);
        seekbarY = view.findViewById(R.id.seekbar_y);
        seekbarWidth = view.findViewById(R.id.seekbar_width);
        seekbarHeight = view.findViewById(R.id.seekbar_height);
        etX = view.findViewById(R.id.et_x_value);
        etY = view.findViewById(R.id.et_y_value);
        etWidth = view.findViewById(R.id.et_width_value);
        etHeight = view.findViewById(R.id.et_height_value);
        rotationSpinner = view.findViewById(R.id.spinner_rotation);
        orientationSpinner = view.findViewById(R.id.spinner_screen_orientation);
        borderSwitch = view.findViewById(R.id.switch_border);
        
        mockLeftButton = view.findViewById(R.id.btn_mock_left);
        mockRightButton = view.findViewById(R.id.btn_mock_right);
        joystickContainer = view.findViewById(R.id.joystick_container);
        joystickHandle = view.findViewById(R.id.joystick_handle);
        
        saveButton = view.findViewById(R.id.btn_save_apply);
        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        // 初始化 Spinner 数据
        String[] cameras = {"front", "back", "left", "right"};
        String[] cameraNames = {"前摄像头", "后摄像头", "左摄像头", "右摄像头"};
        ArrayAdapter<String> cameraAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cameraNames);
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraAdapter);
        mainFloatingCameraSpinner.setAdapter(cameraAdapter);

        String[] rotations = {"0°", "90°", "180°", "270°"};
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, rotations);
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rotationSpinner.setAdapter(rotationAdapter);

        String[] orientations = {"正常 (0°)", "顺时针90°", "倒置 (180°)", "逆时针90°"};
        ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, orientations);
        orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientationSpinner.setAdapter(orientationAdapter);

        // 检测显示器
        updateDisplayList();
    }

    private void updateDisplayList() {
        Display[] displays = displayManager.getDisplays();
        availableDisplays.clear();
        List<String> displayNames = new ArrayList<>();
        for (Display d : displays) {
            availableDisplays.add(d);
            displayNames.add("Display " + d.getDisplayId() + (d.getDisplayId() == 0 ? " (主屏)" : ""));
        }
        ArrayAdapter<String> displayAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, displayNames);
        displayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySpinner.setAdapter(displayAdapter);
    }

    private void loadSettings() {
        // 主屏悬浮窗
        mainFloatingSwitch.setChecked(appConfig.isMainFloatingEnabled());
        String mainCam = appConfig.getMainFloatingCamera();
        mainFloatingCameraSpinner.setSelection(getCameraIndex(mainCam));

        // 转向灯联动
        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        tvTurnSignalTimeout.setText(timeout + "s");
        reuseMainFloatingSwitch.setChecked(appConfig.isTurnSignalReuseMainFloating());
        setupBlindSpotPosButton.setVisibility(appConfig.isTurnSignalReuseMainFloating() ? View.GONE : View.VISIBLE);

        // 副屏显示
        secondaryDisplaySwitch.setChecked(appConfig.isSecondaryDisplayEnabled());
        String camera = appConfig.getSecondaryDisplayCamera();
        cameraSpinner.setSelection(getCameraIndex(camera));

        int displayId = appConfig.getSecondaryDisplayId();
        for (int i = 0; i < availableDisplays.size(); i++) {
            if (availableDisplays.get(i).getDisplayId() == displayId) {
                displaySpinner.setSelection(i);
                updateDisplayInfo(availableDisplays.get(i));
                break;
            }
        }

        seekbarX.setProgress(appConfig.getSecondaryDisplayX());
        seekbarY.setProgress(appConfig.getSecondaryDisplayY());
        seekbarWidth.setProgress(appConfig.getSecondaryDisplayWidth());
        seekbarHeight.setProgress(appConfig.getSecondaryDisplayHeight());
        
        etX.setText(String.valueOf(appConfig.getSecondaryDisplayX()));
        etY.setText(String.valueOf(appConfig.getSecondaryDisplayY()));
        etWidth.setText(String.valueOf(appConfig.getSecondaryDisplayWidth()));
        etHeight.setText(String.valueOf(appConfig.getSecondaryDisplayHeight()));

        rotationSpinner.setSelection(appConfig.getSecondaryDisplayRotation() / 90);
        orientationSpinner.setSelection(appConfig.getSecondaryDisplayOrientation() / 90);
        borderSwitch.setChecked(appConfig.isSecondaryDisplayBorderEnabled());
    }

    private int getCameraIndex(String pos) {
        switch (pos) {
            case "front": return 0;
            case "back": return 1;
            case "left": return 2;
            case "right": return 3;
            default: return 0;
        }
    }

    private String getCameraPos(int index) {
        switch (index) {
            case 0: return "front";
            case 1: return "back";
            case 2: return "left";
            case 3: return "right";
            default: return "front";
        }
    }

    private void setupListeners() {
        mainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                mainFloatingSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
            }
        });

        turnSignalLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                turnSignalLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
            }
        });

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSignalTimeout.setText(progress + "s");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        secondaryDisplaySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                secondaryDisplaySwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
            }
        });

        displaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDisplayInfo(availableDisplays.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (seekBar == seekbarX) etX.setText(String.valueOf(progress));
                else if (seekBar == seekbarY) etY.setText(String.valueOf(progress));
                else if (seekBar == seekbarWidth) etWidth.setText(String.valueOf(progress));
                else if (seekBar == seekbarHeight) etHeight.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        seekbarX.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarY.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarWidth.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarHeight.setOnSeekBarChangeListener(seekBarChangeListener);

        // EditText 监听器
        android.text.TextWatcher textWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int val = Integer.parseInt(s.toString());
                    if (etX.getEditableText() == s) seekbarX.setProgress(val);
                    else if (etY.getEditableText() == s) seekbarY.setProgress(val);
                    else if (etWidth.getEditableText() == s) seekbarWidth.setProgress(val);
                    else if (etHeight.getEditableText() == s) seekbarHeight.setProgress(val);
                } catch (Exception e) {}
            }
        };
        etX.addTextChangedListener(textWatcher);
        etY.addTextChangedListener(textWatcher);
        etWidth.addTextChangedListener(textWatcher);
        etHeight.addTextChangedListener(textWatcher);

        // 模拟按钮监听
        mockLeftButton.setOnClickListener(v -> sendMockSignal("left"));
        mockRightButton.setOnClickListener(v -> sendMockSignal("right"));

        reuseMainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setTurnSignalReuseMainFloating(isChecked);
            setupBlindSpotPosButton.setVisibility(isChecked ? View.GONE : View.VISIBLE);
        });

        setupBlindSpotPosButton.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), BlindSpotService.class);
            intent.putExtra("action", "setup_blind_spot_window");
            requireContext().startService(intent);
        });

        // 摇杆逻辑
        setupJoystick();

        saveButton.setOnClickListener(v -> saveAndApply());

        logcatDebugButton.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
            intent.putExtra("filter_keyword", logFilterEditText.getText().toString());
            startActivity(intent);
        });

        menuButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).toggleDrawer();
            }
        });

        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });
    }

    private void sendMockSignal(String pos) {
        Intent intent = new Intent(requireContext(), BlindSpotService.class);
        intent.putExtra("mock_turn_signal", pos);
        requireContext().startService(intent);
        Toast.makeText(requireContext(), "已发送模拟信号: " + (pos.equals("left") ? "左" : "右") + " (3秒)", Toast.LENGTH_SHORT).show();
    }

    private void setupJoystick() {
        joystickContainer.setOnTouchListener(new View.OnTouchListener() {
            private float lastTouchX, lastTouchY;
            private int initialPosX, initialPosY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float touchX = event.getX();
                float touchY = event.getY();

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = touchX;
                        lastTouchY = touchY;
                        initialPosX = seekbarX.getProgress();
                        initialPosY = seekbarY.getProgress();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = touchX - lastTouchX;
                        float dy = touchY - lastTouchY;

                        // 缩放灵敏度：摇杆容器 200dp，假设映射到全屏
                        // 简单起见，按位移比例映射
                        int moveX = (int) (dx * 5); // 灵敏度系数 5
                        int moveY = (int) (dy * 5);

                        int newX = Math.max(0, Math.min(seekbarX.getMax(), initialPosX + moveX));
                        int newY = Math.max(0, Math.min(seekbarY.getMax(), initialPosY + moveY));

                        seekbarX.setProgress(newX);
                        seekbarY.setProgress(newY);
                        etX.setText(String.valueOf(newX));
                        etY.setText(String.valueOf(newY));

                        // 更新摇杆球位置（视觉反馈）
                        float ballX = touchX - (joystickHandle.getWidth() / 2f);
                        float ballY = touchY - (joystickHandle.getHeight() / 2f);
                        
                        // 限制在容器内
                        ballX = Math.max(0, Math.min(joystickContainer.getWidth() - joystickHandle.getWidth(), ballX));
                        ballY = Math.max(0, Math.min(joystickContainer.getHeight() - joystickHandle.getHeight(), ballY));
                        
                        joystickHandle.setX(ballX);
                        joystickHandle.setY(ballY);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 复位摇杆球到中心
                        joystickHandle.animate()
                                .x((joystickContainer.getWidth() - joystickHandle.getWidth()) / 2f)
                                .y((joystickContainer.getHeight() - joystickHandle.getHeight()) / 2f)
                                .setDuration(200)
                                .start();
                        return true;
                }
                return false;
            }
        });
    }

    private void updateDisplayInfo(Display display) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        displayInfoText.setText(String.format("当前屏幕分辨率: %d x %d", metrics.widthPixels, metrics.heightPixels));
        
        seekbarX.setMax(metrics.widthPixels);
        seekbarY.setMax(metrics.heightPixels);
        seekbarWidth.setMax(metrics.widthPixels);
        seekbarHeight.setMax(metrics.heightPixels);
    }

    private void saveAndApply() {
        // 主屏悬浮窗
        appConfig.setMainFloatingEnabled(mainFloatingSwitch.isChecked());
        appConfig.setMainFloatingCamera(getCameraPos(mainFloatingCameraSpinner.getSelectedItemPosition()));

        // 转向灯联动
        appConfig.setTurnSignalLinkageEnabled(turnSignalLinkageSwitch.isChecked());
        appConfig.setTurnSignalTimeout(turnSignalTimeoutSeekBar.getProgress());
        appConfig.setTurnSignalReuseMainFloating(reuseMainFloatingSwitch.isChecked());

        // 副屏显示
        appConfig.setSecondaryDisplayEnabled(secondaryDisplaySwitch.isChecked());
        appConfig.setSecondaryDisplayCamera(getCameraPos(cameraSpinner.getSelectedItemPosition()));
        
        int displayId = availableDisplays.get(displaySpinner.getSelectedItemPosition()).getDisplayId();
        appConfig.setSecondaryDisplayId(displayId);
        
        appConfig.setSecondaryDisplayBounds(
                seekbarX.getProgress(),
                seekbarY.getProgress(),
                seekbarWidth.getProgress(),
                seekbarHeight.getProgress()
        );
        
        appConfig.setSecondaryDisplayRotation(rotationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayOrientation(orientationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayBorderEnabled(borderSwitch.isChecked());

        Toast.makeText(requireContext(), "配置已保存并应用", Toast.LENGTH_SHORT).show();
        
        // 触发服务更新
        BlindSpotService.update(requireContext());
    }
}
