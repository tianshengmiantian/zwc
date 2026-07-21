package com.westart.ai.westart.ai.model.speech;

import dev.langchain4j.model.output.Response;

/** Project-level, LangChain4j-style contract for text-to-speech synthesis. */
public interface TextToSpeechModel {

    Response<SynthesizedSpeech> synthesizeSpeech(String text);

    Response<SynthesizedSpeech> synthesizeSpeech(String text, String requestedVoice);

    record SynthesizedSpeech(
            String model,
            String voice,
            String text,
            byte[] audioBytes,
            String fileExtension
    ) {
        public String fileName() {
            return "bailian-voice." + fileExtension;
        }
    }
}
