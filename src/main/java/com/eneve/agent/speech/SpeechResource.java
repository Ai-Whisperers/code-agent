package com.eneve.agent.speech;

import com.eneve.agent.settings.SettingsService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.AudioEvent;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.LanguageCode;
import software.amazon.awssdk.services.transcribestreaming.model.MediaEncoding;
import software.amazon.awssdk.services.transcribestreaming.model.Result;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponseHandler;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transcribes audio via Amazon Transcribe Streaming (AWS SDK v2).
 *
 * <p>The frontend records audio with MediaRecorder, splits on silence (VAD), and POSTs
 * each chunk here as multipart/form-data. This endpoint streams the audio to Amazon
 * Transcribe Streaming, collects the final transcript, and returns it as
 * {@code { "transcript": "…" }}.
 *
 * <p>No sidecar or external service is required — authentication is handled by the
 * standard AWS credential chain (IAM task role on ECS Fargate). The task role must
 * have the {@code transcribe:StartStreamTranscription} permission.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code transcribe.region} — AWS region for the Transcribe API (default: {@code eu-west-1})</li>
 *   <li>{@code transcribe.sample-rate} — PCM sample rate in Hz (default: {@code 16000})</li>
 * </ul>
 */
@Path("/speech")
@Authenticated
@Tag(name = "Speech", description = "Speech-to-text transcription via Amazon Transcribe Streaming")
@Produces(MediaType.APPLICATION_JSON)
public class SpeechResource {

    private static final Logger LOG = Logger.getLogger(SpeechResource.class);

    static final String TRANSCRIBE_REGION_KEY          = "transcribe.region";
    static final String TRANSCRIBE_REGION_DEFAULT      = "eu-west-1";
    static final String TRANSCRIBE_SAMPLE_RATE_KEY     = "transcribe.sample-rate";
    static final String TRANSCRIBE_SAMPLE_RATE_DEFAULT = "16000";

    @Inject
    SettingsService settings;

    @POST
    @Path("/transcribe")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(
        operationId = "transcribeAudio",
        summary = "Transcribe audio to text",
        description = "Accepts a raw audio chunk (webm, ogg, wav) and returns the transcript via Amazon Transcribe Streaming."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Transcription successful"),
        @APIResponse(responseCode = "400", description = "No audio provided"),
        @APIResponse(responseCode = "500", description = "Transcription failed")
    })
    public Response transcribe(AudioUploadForm form) {
        if (form.audio == null) {
            return Response.status(400)
                .entity(Map.of("error", "No audio provided"))
                .build();
        }

        try {
            byte[] audioBytes = form.audio.readAllBytes();
            if (audioBytes.length == 0) {
                return Response.status(400)
                    .entity(Map.of("error", "Empty audio payload"))
                    .build();
            }

            String region     = settings.get(TRANSCRIBE_REGION_KEY, TRANSCRIBE_REGION_DEFAULT);
            int    sampleRate = Integer.parseInt(
                settings.get(TRANSCRIBE_SAMPLE_RATE_KEY, TRANSCRIBE_SAMPLE_RATE_DEFAULT));
            String language   = resolveLanguageCode(form.language);

            // The frontend always sends signed 16-bit LE PCM wrapped in a WAV container.
            // We strip the 44-byte WAV header before streaming raw PCM to Transcribe.
            String transcript = streamToTranscribe(audioBytes, region, sampleRate, language);

            LOG.debugf("Transcribed %d bytes → \"%s\"", audioBytes.length, transcript);
            return Response.ok(Map.of("transcript", transcript)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Transcription failed: %s", e.getMessage());
            return Response.status(500)
                .entity(Map.of("error", "Transcription failed: " + e.getMessage()))
                .build();
        }
    }

    // -------------------------------------------------------------------------
    // Core streaming logic
    // -------------------------------------------------------------------------

    private String streamToTranscribe(
            byte[] audioBytes,
            String region,
            int sampleRate,
            String language) throws Exception {

        // Strip the RIFF/WAV header — Transcribe PCM expects raw samples only.
        // Parse the actual data-chunk offset from the header instead of hardcoding 44,
        // in case the browser ever writes an extended fmt chunk.
        int dataOffset = findWavDataOffset(audioBytes);
        LOG.debugf("WAV: total=%d bytes, dataOffset=%d, PCM payload=%d bytes (~%.1f ms at %d Hz)",
            audioBytes.length, dataOffset, audioBytes.length - dataOffset,
            (audioBytes.length - dataOffset) / 2.0 / sampleRate * 1000, sampleRate);
        if (dataOffset > 0 && audioBytes.length > dataOffset) {
            audioBytes = java.util.Arrays.copyOfRange(audioBytes, dataOffset, audioBytes.length);
        }

        AtomicReference<StringBuilder> transcriptRef = new AtomicReference<>(new StringBuilder());

        StartStreamTranscriptionResponseHandler responseHandler =
            StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> LOG.debugf("Transcribe stream started, session=%s", r.sessionId()))
                .onError(e -> LOG.warnf("Transcribe stream error: %s", e.getMessage()))
                .subscriber(event -> {
                    if (event instanceof TranscriptEvent te) {
                        te.transcript().results().stream()
                            .filter(r -> !r.isPartial())
                            .map(Result::alternatives)
                            .filter(alts -> !alts.isEmpty())
                            .map(alts -> alts.get(0).transcript())
                            .forEach(t -> transcriptRef.get().append(t).append(' '));
                    }
                })
                .build();

        try (TranscribeStreamingAsyncClient client = TranscribeStreamingAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            StartStreamTranscriptionRequest request = StartStreamTranscriptionRequest.builder()
                .languageCode(LanguageCode.fromValue(language))
                .mediaEncoding(MediaEncoding.PCM)
                .mediaSampleRateHertz(sampleRate)
                .build();

            CompletableFuture<Void> future = client.startStreamTranscription(
                request,
                new ByteArrayAudioPublisher(audioBytes),
                responseHandler);

            future.get();
        }

        return transcriptRef.get().toString().trim();
    }

    // -------------------------------------------------------------------------
    // WAV header parser
    // -------------------------------------------------------------------------

    /**
     * Walks the RIFF chunk list to find the byte offset of the {@code data} chunk payload.
     * Returns 44 as a safe fallback if the header cannot be parsed.
     */
    private static int findWavDataOffset(byte[] wav) {
        // Minimum RIFF header: "RIFF"(4) + size(4) + "WAVE"(4) + at least one chunk(8)
        if (wav == null || wav.length < 20) return 44;
        // Verify RIFF + WAVE magic
        if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return 44;
        if (wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') return 44;

        int pos = 12; // start of first chunk after "WAVE"
        while (pos + 8 <= wav.length) {
            // chunk id (4 bytes) + chunk size (4 bytes, little-endian)
            int chunkSize = ((wav[pos + 4] & 0xFF))
                          | ((wav[pos + 5] & 0xFF) << 8)
                          | ((wav[pos + 6] & 0xFF) << 16)
                          | ((wav[pos + 7] & 0xFF) << 24);
            if (wav[pos] == 'd' && wav[pos + 1] == 'a' && wav[pos + 2] == 't' && wav[pos + 3] == 'a') {
                return pos + 8; // data payload starts right after the 8-byte chunk header
            }
            // Advance to next chunk (chunk size must be even-padded per RIFF spec)
            pos += 8 + chunkSize + (chunkSize % 2);
        }
        return 44; // fallback
    }

    // -------------------------------------------------------------------------
    // Reactive Streams Publisher that emits AudioEvent chunks from a byte array
    // -------------------------------------------------------------------------

    /**
     * Wraps a byte array in a Reactive Streams {@link Publisher} of {@link AudioStream}
     * events. Each {@link AudioEvent} carries at most {@value #CHUNK_BYTES} bytes.
     *
     * <p>The subscription uses an unbounded-demand model: on the first {@code request(n)}
     * call it submits all chunks to a single-thread executor and signals {@code onComplete()}
     * immediately after the last chunk. This avoids the race where a demand-gated loop
     * exits before all audio is sent and never calls {@code onComplete()}, which would
     * cause Amazon Transcribe to time out waiting for more data.
     */
    private static final class ByteArrayAudioPublisher implements Publisher<AudioStream> {

        // Transcribe Streaming's EventStream framing adds ~100 bytes of header overhead
        // per frame. The documented maximum audio payload per event is 8 KB.
        private static final int CHUNK_BYTES = 8 * 1024;

        private final byte[] audio;

        ByteArrayAudioPublisher(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public void subscribe(Subscriber<? super AudioStream> subscriber) {
            subscriber.onSubscribe(new AudioSubscription(subscriber, audio));
        }

        private static final class AudioSubscription implements Subscription {

            private final Subscriber<? super AudioStream> subscriber;
            private final byte[] audio;
            private final ExecutorService executor = Executors.newSingleThreadExecutor();
            private volatile boolean started   = false;
            private volatile boolean cancelled = false;

            AudioSubscription(Subscriber<? super AudioStream> subscriber, byte[] audio) {
                this.subscriber = subscriber;
                this.audio      = audio;
            }

            @Override
            public void request(long n) {
                // Guard against re-entrant calls; emit all chunks exactly once.
                if (started) return;
                started = true;
                executor.submit(this::emitAll);
            }

            @Override
            public void cancel() {
                cancelled = true;
                executor.shutdown();
            }

            private void emitAll() {
                try {
                    int offset = 0;
                    while (!cancelled && offset < audio.length) {
                        int end = Math.min(offset + CHUNK_BYTES, audio.length);
                        ByteBuffer buf = ByteBuffer.wrap(audio, offset, end - offset);
                        offset = end;

                        subscriber.onNext(AudioEvent.builder()
                            .audioChunk(SdkBytes.fromByteBuffer(buf))
                            .build());
                    }
                    if (!cancelled) {
                        subscriber.onComplete();
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                } finally {
                    executor.shutdown();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a BCP-47 / ISO-639-1 language tag (e.g. {@code "en"}, {@code "nl-NL"})
     * to the closest Amazon Transcribe language code.
     *
     * <p>Transcribe requires full locale codes (e.g. {@code "en-US"}). When only
     * a two-letter code is supplied we default to the most common locale for that
     * language. Unknown codes fall back to {@code "en-US"}.
     */
    private static String resolveLanguageCode(String lang) {
        if (lang == null || lang.isBlank()) return "en-US";
        String l = lang.trim();
        if (l.length() >= 5 && l.charAt(2) == '-') return l;
        return switch (l.toLowerCase()) {
            case "en" -> "en-US";
            case "nl" -> "nl-NL";
            case "de" -> "de-DE";
            case "fr" -> "fr-FR";
            case "es" -> "es-ES";
            case "it" -> "it-IT";
            case "pt" -> "pt-BR";
            case "ja" -> "ja-JP";
            case "ko" -> "ko-KR";
            case "zh" -> "zh-CN";
            case "ar" -> "ar-SA";
            case "hi" -> "hi-IN";
            case "ru" -> "ru-RU";
            case "pl" -> "pl-PL";
            case "sv" -> "sv-SE";
            case "da" -> "da-DK";
            case "fi" -> "fi-FI";
            case "nb" -> "nb-NO";
            case "tr" -> "tr-TR";
            default   -> "en-US";
        };
    }

    // -------------------------------------------------------------------------
    // Form
    // -------------------------------------------------------------------------

    public static class AudioUploadForm {

        /** Raw audio as a RIFF/WAV file (signed 16-bit LE PCM, mono, 16 kHz). */
        @RestForm("audio")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream audio;

        /** Optional BCP-47 language hint, e.g. "en" or "nl-NL". Defaults to "en-US". */
        @RestForm("language")
        @PartType(MediaType.TEXT_PLAIN)
        public String language;
    }
}
