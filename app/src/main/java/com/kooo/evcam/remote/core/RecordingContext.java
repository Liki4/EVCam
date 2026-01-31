package com.kooo.evcam.remote.core;

/**
 * 录制上下文
 * 封装一次远程录制任务的所有状态信息
 */
public class RecordingContext {
    
    private final ChatIdentifier chatId;
    private final int durationSeconds;
    private final String timestamp;
    
    // 状态标志
    private boolean wasManualRecordingBefore = false;
    private boolean isCompleted = false;
    private boolean isCancelled = false;
    
    // 错误信息
    private String errorMessage = null;
    
    public RecordingContext(ChatIdentifier chatId, int durationSeconds, String timestamp) {
        this.chatId = chatId;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
    }
    
    // ==================== Getters ====================
    
    public ChatIdentifier getChatId() {
        return chatId;
    }
    
    public int getDurationSeconds() {
        return durationSeconds;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    public boolean wasManualRecordingBefore() {
        return wasManualRecordingBefore;
    }
    
    public boolean isCompleted() {
        return isCompleted;
    }
    
    public boolean isCancelled() {
        return isCancelled;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public RemotePlatform getPlatform() {
        return chatId.getPlatform();
    }
    
    // ==================== Setters ====================
    
    public void setWasManualRecordingBefore(boolean wasManualRecordingBefore) {
        this.wasManualRecordingBefore = wasManualRecordingBefore;
    }
    
    public void setCompleted(boolean completed) {
        this.isCompleted = completed;
    }
    
    public void setCancelled(boolean cancelled) {
        this.isCancelled = cancelled;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    @Override
    public String toString() {
        return "RecordingContext{" +
                "platform=" + getPlatform().getDisplayName() +
                ", chatId=" + chatId.getId() +
                ", duration=" + durationSeconds + "s" +
                ", timestamp=" + timestamp +
                ", wasManualBefore=" + wasManualRecordingBefore +
                '}';
    }
}
