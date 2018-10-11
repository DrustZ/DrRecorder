package ai.kitt.snowboy.audio;


/**
 * Callback to run when the keyword is detected.
 */
public interface KeywordCallback {
    void kwDetected(int keyword_index);
}
