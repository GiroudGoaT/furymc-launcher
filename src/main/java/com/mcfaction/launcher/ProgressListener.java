package com.mcfaction.launcher;

/** Callback for long-running launcher steps (download/extract), so the UI can show progress. */
public interface ProgressListener {

    /**
     * @param percent 0-100, or -1 if the step's total size/duration isn't known ahead of time
     * @param status  short human-readable status text (already localized/final, ready to display)
     */
    void onProgress(int percent, String status);
}
