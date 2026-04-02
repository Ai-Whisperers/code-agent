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
import java.util.concurrent.atomic.AtomicLong;
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
            String mimeType   = (form.mimeType != null && !form.mimeType.isBlank())
                ? form.mimeType : "audio/webm";

            MediaEncoding encoding = resolveEncoding(mimeType);

            String transcript = streamToTranscribe(audioBytes, region, sampleRate, language, encoding);

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
            String language,
            MediaEncoding encoding) throws Exception {

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
                .mediaEncoding(encoding)
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
    // Reactive Streams Publisher that emits AudioEvent chunks from a byte array
    // -------------------------------------------------------------------------

    /**
     * Wraps a byte array in a Reactive Streams {@link Publisher} of {@link AudioStream}
     * events. Each {@link AudioEvent} carries at most {@value #CHUNK_BYTES} bytes.
     */
    private static final class ByteArrayAudioPublisher implements Publisher<AudioStream> {

        private static final int CHUNK_BYTES = 32 * 1024;

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
            private final AtomicLong demand = new AtomicLong(0);
            private volatile boolean cancelled = false;
            private int offset = 0;

            AudioSubscription(Subscriber<? super AudioStream> subscriber, byte[] audio) {
                this.subscriber = subscriber;
                this.audio      = audio;
            }

            @Override
            public void request(long n) {
                if (n <= 0) {
                    subscriber.onError(new IllegalArgumentException("Demand must be positive"));
                    return;
                }
                demand.addAndGet(n);
                executor.submit(this::drain);
            }

            @Override
            public void cancel() {
                cancelled = true;
                executor.shutdown();
            }

            private void drain() {
                try {
                    while (!cancelled && demand.get() > 0 && offset < audio.length) {
                        int end   = Math.min(offset + CHUNK_BYTES, audio.length);
                        int len   = end - offset;
                        ByteBuffer buf = ByteBuffer.wrap(audio, offset, len);
                        offset = end;

                        AudioEvent event = AudioEvent.builder()
                            .audioChunk(SdkBytes.fromByteBuffer(buf))
                            .build();

                        subscriber.onNext(event);
                        demand.decrementAndGet();
                    }
                    if (!cancelled && offset >= audio.length) {
                        subscriber.onComplete();
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps the browser-reported MIME type to the Transcribe {@link MediaEncoding}.
     * <ul>
     *   <li>OGG/Opus → {@code OGG_OPUS} (native, no re-encoding needed)</li>
     *   <li>WAV/PCM  → {@code PCM}</li>
     *   <li>WebM/Opus → {@code OGG_OPUS} (Transcribe accepts WebM/Opus as ogg_opus)</li>
     * </ul>
     */
    private static MediaEncoding resolveEncoding(String mimeType) {
        String base = mimeType.toLowerCase().split(";")[0].trim();
        return switch (base) {
            case "audio/wav", "audio/wave", "audio/pcm" -> MediaEncoding.PCM;
            default -> MediaEncoding.OGG_OPUS;
        };
    }

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

        /** Raw audio bytes (webm/opus, ogg/opus, wav). */
        @RestForm("audio")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream audio;

        /** MIME type reported by MediaRecorder, e.g. "audio/webm;codecs=opus". */
        @RestForm("mimeType")
        @PartType(MediaType.TEXT_PLAIN)
        public String mimeType;

        /** Optional BCP-47 language hint, e.g. "en" or "nl-NL". Defaults to "en-US". */
        @RestForm("language")
        @PartType(MediaType.TEXT_PLAIN)
        public String language;
    }
}
