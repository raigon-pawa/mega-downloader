/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

/**
 * Plain, toolkit-free state of one transfer (status line, speed, slots).
 * The engine writes it; a front-end (gui package, CLI) only reads it by
 * polling -- nothing here touches Swing/AWT, so the engine runs headless.
 */
public final class TransferState implements TransferenceView {

    public static final int NORMAL = 0, OK = 1, ERROR = 2, WARNING = 3;

    private final boolean _download;
    private volatile String _status = "";
    private volatile int _status_kind = NORMAL;
    private volatile String _speed = "";
    private volatile String _cbc = "";
    private volatile int _slots;
    private volatile boolean _keep_temp = false;

    public TransferState(boolean download, int default_slots) {
        _download = download;
        _slots = default_slots;
    }

    public String getStatus() {
        return _status;
    }

    public int getStatusKind() {
        return _status_kind;
    }

    public String getSpeed() {
        return _speed;
    }

    /** Upload-only: CBC-MAC progress text ("CBC-MAC 42%"). */
    public String getCbc() {
        return _cbc;
    }

    public void updateCBC(String status) {
        _cbc = status == null ? "" : status;
    }

    public void setSlots(int slots) {
        _slots = Math.max(Transference.MIN_WORKERS, Math.min(Transference.MAX_WORKERS, slots));
    }

    /** Download-only: keep the temp file when the download is cancelled. */
    public boolean isKeepTempFileSelected() {
        return _keep_temp;
    }

    public void setKeepTemp(boolean keep_temp) {
        _keep_temp = keep_temp;
    }

    /** Terminal states call this; only the status line stays meaningful. */
    public void hideAllExceptStatus() {
        _speed = "";
        _cbc = "";
    }

    public void printStatusWarning(String message) {
        _print(message, WARNING);
    }

    private void _print(String message, int kind) {
        _status = message == null ? "" : LabelTranslatorSingleton.getInstance().translate(message);
        _status_kind = kind;
    }

    @Override
    public void pause() {
        printStatusNormal(_download ? "Pausing download ..." : "Pausing upload ...");
    }

    @Override
    public void resume() {
        printStatusNormal(_download ? "Downloading file from mega ..." : "Uploading file to mega ...");
    }

    @Override
    public void stop(String status) {
        printStatusNormal(status);
    }

    @Override
    public void updateSpeed(String speed, Boolean visible) {
        if (visible != null && !visible) {
            _speed = "";
        } else if (speed != null) {
            _speed = speed;
        }
    }

    // Progress is read straight from Transference.getProgress()/getFile_size().
    @Override
    public void updateProgressBar(long progress, double bar_rate) {
    }

    @Override
    public void updateProgressBar(int value) {
    }

    // Slot/worker health is computed on demand: Download/Upload.getSlotsStatus().
    @Override
    public void updateSlotsStatus() {
    }

    @Override
    public int getSlots() {
        return _slots;
    }

    @Override
    public void printStatusNormal(String msg) {
        _print(msg, NORMAL);
    }

    @Override
    public void printStatusOK(String msg) {
        _print(msg, OK);
    }

    @Override
    public void printStatusError(String msg) {
        _print(msg, ERROR);
    }
}
