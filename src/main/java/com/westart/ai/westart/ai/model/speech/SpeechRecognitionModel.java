package com.westart.ai.westart.ai.model.speech;

import dev.langchain4j.model.output.Response;

/** Project-level, LangChain4j-style contract for speech recognition. */
public interface SpeechRecognitionModel {

    long MAX_AUDIO_BYTES = 10L * 1024 * 1024;

    Response<Transcription> recognizeFile(byte[] bytes, String fileName);

    Response<Transcription> recognizeVoice(
            byte[] bytes,
            Integer encodeType,
            Integer sampleRate,
            Integer bitsPerSample,
            Integer playtimeMs
    );

    boolean isSupportedAudioFile(String fileName, byte[] bytes);

    record Transcription(String model, String transcript) {
    }
}
