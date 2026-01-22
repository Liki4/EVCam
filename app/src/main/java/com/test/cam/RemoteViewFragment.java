package com.test.cam;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.test.cam.dingtalk.DingTalkApiClient;
import com.test.cam.dingtalk.DingTalkConfig;
import com.test.cam.dingtalk.DingTalkStreamManager;

public class RemoteViewFragment extends Fragment {
    private static final String TAG = "RemoteViewFragment";

    private EditText etClientId, etClientSecret;
    private Button btnSaveConfig, btnStartService, btnStopService, btnMenu;
    private TextView tvConnectionStatus;

    private DingTalkConfig config;
    private DingTalkApiClient apiClient;
    private DingTalkStreamManager streamManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remote_view, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadConfig();
        setupListeners();
    }

    private void initViews(View view) {
        btnMenu = view.findViewById(R.id.btn_menu);
        etClientId = view.findViewById(R.id.et_client_id);
        etClientSecret = view.findViewById(R.id.et_client_secret);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        config = new DingTalkConfig(requireContext());
    }

    private void loadConfig() {
        if (config.isConfigured()) {
            etClientId.setText(config.getClientId());
            etClientSecret.setText(config.getClientSecret());
        }
    }

    private void setupListeners() {
        btnMenu.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) getActivity();
                DrawerLayout drawerLayout = activity.findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            }
        });
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnStartService.setOnClickListener(v -> startService());
        btnStopService.setOnClickListener(v -> stopService());
    }

    private void saveConfig() {
        String clientId = etClientId.getText().toString().trim();
        String clientSecret = etClientSecret.getText().toString().trim();

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            Toast.makeText(requireContext(), "请填写完整的配置信息", Toast.LENGTH_SHORT).show();
            return;
        }

        config.saveConfig(clientId, clientSecret);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void startService() {
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }

        apiClient = new DingTalkApiClient(config);

        // 创建连接回调
        DingTalkStreamManager.ConnectionCallback connectionCallback = new DingTalkStreamManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                // 检查 Fragment 是否还附加到 Activity
                if (!isAdded() || getActivity() == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    // 再次检查，因为可能在线程切换时 Fragment 被销毁
                    if (!isAdded() || getView() == null) {
                        return;
                    }
                    tvConnectionStatus.setText("已连接");
                    tvConnectionStatus.setTextColor(0xFF66FF66);
                    btnStartService.setEnabled(false);
                    btnStopService.setEnabled(true);
                    Toast.makeText(requireContext(), "服务已启动", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                // 检查 Fragment 是否还附加到 Activity
                if (!isAdded() || getActivity() == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    // 再次检查，因为可能在线程切换时 Fragment 被销毁
                    if (!isAdded() || getView() == null) {
                        return;
                    }
                    tvConnectionStatus.setText("未连接");
                    tvConnectionStatus.setTextColor(0xFFFF6666);
                    btnStartService.setEnabled(true);
                    btnStopService.setEnabled(false);
                });
            }

            @Override
            public void onError(String error) {
                // 检查 Fragment 是否还附加到 Activity
                if (!isAdded() || getActivity() == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    // 再次检查，因为可能在线程切换时 Fragment 被销毁
                    if (!isAdded() || getView() == null) {
                        return;
                    }
                    tvConnectionStatus.setText("连接失败");
                    tvConnectionStatus.setTextColor(0xFFFF6666);
                    Toast.makeText(requireContext(), "连接失败: " + error, Toast.LENGTH_LONG).show();
                    btnStartService.setEnabled(true);
                    btnStopService.setEnabled(false);
                });
            }
        };

        // 创建指令回调
        DingTalkStreamManager.CommandCallback commandCallback = (conversationId, userId) -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).startRemoteRecording(conversationId, userId);
            }
        };

        // 创建并启动 Stream 管理器
        streamManager = new DingTalkStreamManager(requireContext(), config, apiClient, connectionCallback);
        streamManager.start(commandCallback);

        Toast.makeText(requireContext(), "正在启动服务...", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        if (streamManager != null) {
            streamManager.stop();
            streamManager = null;
        }
        tvConnectionStatus.setText("未连接");
        tvConnectionStatus.setTextColor(0xFFFF6666);
        btnStartService.setEnabled(true);
        btnStopService.setEnabled(false);
        Toast.makeText(requireContext(), "服务已停止", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 不要在这里停止服务，让用户手动停止
        // 或者将 streamManager 移到 Activity 级别
        // stopService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 只在 Fragment 真正销毁时停止服务
        if (streamManager != null && streamManager.isRunning()) {
            streamManager.stop();
            streamManager = null;
        }
    }

    public DingTalkApiClient getApiClient() {
        return apiClient;
    }

    public DingTalkStreamManager getStreamManager() {
        return streamManager;
    }
}
