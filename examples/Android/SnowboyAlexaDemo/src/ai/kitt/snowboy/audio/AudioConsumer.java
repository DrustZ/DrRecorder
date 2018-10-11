package ai.kitt.snowboy.audio;

import ai.picovoice.porcupinemanager.PorcupineManagerException;

interface AudioConsumer {
    /**
     * Consume the raw PCM data.
     * @throws PorcupineManagerException An exception is thrown if there is an error while processing
     * the PCM data.
     */
    void consume(short[] pcm) throws PorcupineManagerException;

    /**
     * Number of audio samples per frame.
     * @return Number of samples per frame.
     */
    int getFrameLength();

    /**
     * Number of audio samples per second.
     * @return Sample rate of the audio data.
     */
    int getSampleRate();
}
