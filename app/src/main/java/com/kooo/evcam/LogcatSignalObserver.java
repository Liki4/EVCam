package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logcat 信号观察者
 * 专门解析转向灯系统日志信号
 */
public class LogcatSignalObserver {
    private static final String TAG = "LogcatSignalObserver";
    
    // 日志特征: I/avm_vc_signal: [videox_i]signal_process_light:533 signal transfer light[10] data1 = 170, status is 0xaa
    private static final String SIGNAL_LOG_PREFIX = "avm_vc_signal";
    private static final Pattern SIGNAL_PATTERN = Pattern.compile("data1 = (\\d+)");

    public interface SignalListener {
        /**
         * 转向灯信号回调
         * @param data1 170:右, 85:左, 0:熄灭
         */
        void onTurnSignalReceived(int data1);
    }

    private final SignalListener listener;
    private Thread logcatThread;
    private volatile boolean isRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LogcatSignalObserver(SignalListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        logcatThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                // 清理旧日志并开始读取
                Runtime.getRuntime().exec("logcat -c").waitFor();
                process = Runtime.getRuntime().exec("logcat -v brief " + SIGNAL_LOG_PREFIX + ":I *:S");
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    if (line.contains("data1 =")) {
                        Matcher matcher = SIGNAL_PATTERN.matcher(line);
                        if (matcher.find()) {
                            try {
                                final int data1 = Integer.parseInt(matcher.group(1));
                                mainHandler.post(() -> {
                                    if (listener != null) {
                                        listener.onTurnSignalReceived(data1);
                                    }
                                });
                            } catch (NumberFormatException e) {
                                // Ignore
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Logcat reading error: " + e.getMessage());
            } finally {
                isRunning = false;
                try {
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
        logcatThread.setPriority(Thread.MAX_PRIORITY);
        logcatThread.start();
    }

    public void stop() {
        isRunning = false;
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }
}
