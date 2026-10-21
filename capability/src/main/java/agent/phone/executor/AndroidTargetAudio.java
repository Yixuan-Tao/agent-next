package agent.phone.executor;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.util.Log;
import java.util.Arrays;

/** Shell-only UID loopback routing. Discards PCM; never changes system volume or AppOps. */
final class AndroidTargetAudio implements TargetAudioIsolation.Backend {
    private Resource active;

    @Override public synchronized TargetAudioIsolation.Lease mute(int uid) throws Exception {
        close();
        Resource next = new Resource();
        active = next; // Retain partial allocation if a compensating cleanup needs retrying.
        try {
            next.open(uid);
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return () -> {
            synchronized (AndroidTargetAudio.this) {
                if (active != next) throw new IllegalStateException("Audio ownership changed");
                close();
            }
        };
    }

    @Override public synchronized void close() throws Exception {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private static final class Resource {
        private AudioManager manager;
        private Class<?> policyType;
        private Object policy;
        private AudioRecord record;
        private Thread drainer;
        private volatile boolean running;
        private boolean registered;

        void open(int uid) throws Exception {
            Context context = AndroidDisplayResources.shellContext();
            Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderType = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderType.getConstructor().newInstance();
            ruleBuilderType.getMethod("addMixRule", int.class, Object.class).invoke(ruleBuilder,
                    ruleType.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(uid));
            ruleBuilderType.getMethod("allowPrivilegedPlaybackCapture", boolean.class)
                    .invoke(ruleBuilder, true);
            Object rule = ruleBuilderType.getMethod("build").invoke(ruleBuilder);
            AudioFormat format = new AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderType = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderType.getConstructor(ruleType).newInstance(rule);
            mixBuilderType.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
            mixBuilderType.getMethod("setRouteFlags", int.class).invoke(mixBuilder,
                    mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
            Object mix = mixBuilderType.getMethod("build").invoke(mixBuilder);
            policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> builderType = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object builder = builderType.getConstructor(Context.class).newInstance(context);
            builderType.getMethod("addMix", mixType).invoke(builder, mix);
            policy = builderType.getMethod("build").invoke(builder);
            manager = AudioManager.class.getConstructor(Context.class).newInstance(context);
            int status = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyType)
                    .invoke(manager, policy);
            if (status != AudioManager.class.getField("SUCCESS").getInt(null)) {
                throw new IllegalStateException("UID audio routing was rejected");
            }
            registered = true;
            record = (AudioRecord) createSink(policy, mix, policyType, mixType);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Silent audio sink could not be initialized");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Silent audio sink could not start");
            }
            running = true;
            AudioRecord source = record;
            drainer = new Thread(() -> drain(source), "agent-audio-discard");
            drainer.start();
            Log.i("AgentShellAudio", "target audio isolated uid=" + uid);
        }

        /**
         * Shizuku runs this service as uid 2000 inside a framework process whose application
         * context still claims the host app package. AudioFlinger validates the uid/package pair
         * for a trusted uid, so the policy sink must temporarily report the shell package the
         * process actually owns. The application context keeps its original attribution.
         */
        private static Object createSink(Object policy, Object mix, Class<?> policyType,
                Class<?> mixType) throws Exception {
            java.lang.reflect.Method create = policyType.getMethod("createAudioRecordSink", mixType);
            Runnable restore = installShellAttribution();
            if (restore == null) return create.invoke(policy, mix);
            try {
                return create.invoke(policy, mix);
            } finally {
                restore.run();
            }
        }

        /** Returns a restore action, or null when this ROM does not expose the expected fields. */
        private static Runnable installShellAttribution() {
            try {
                Object thread = Class.forName("android.app.ActivityThread")
                        .getMethod("currentActivityThread").invoke(null);
                if (thread == null) return null;
                java.lang.reflect.Field application = thread.getClass()
                        .getDeclaredField("mInitialApplication");
                application.setAccessible(true);
                Object app = application.get(thread);
                if (!(app instanceof android.content.ContextWrapper)) return null;
                Class<?> contextImpl = Class.forName("android.app.ContextImpl");
                java.lang.reflect.Field attribution = contextImpl
                        .getDeclaredField("mAttributionSource");
                attribution.setAccessible(true);
                Object base = ((android.content.ContextWrapper) app).getBaseContext();
                Object previous = attribution.get(base);
                Class<?> sourceType = Class.forName("android.content.AttributionSource");
                Object current = sourceType.getMethod("myAttributionSource").invoke(null);
                Object shell = sourceType.getMethod("withPackageName", String.class)
                        .invoke(current, "com.android.shell");
                attribution.set(base, shell);
                return () -> {
                    try {
                        attribution.set(base, previous);
                    } catch (ReflectiveOperationException restoreFailure) {
                        Log.w("AgentShellAudio", "shell attribution restore failed", restoreFailure);
                    }
                };
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // Older or modified ROMs may not expose this layout; the sink attempt itself decides.
                Log.w("AgentShellAudio", "shell attribution patch unavailable", unavailable);
                return null;
            }
        }

        private void drain(AudioRecord source) {
            byte[] buffer = new byte[2048];
            try {
                while (running) {
                    int count = source.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    Arrays.fill(buffer, (byte) 0);
                    if (count < 0) {
                        if (running) Log.w("AgentShellAudio", "audio discard stopped code=" + count);
                        return;
                    }
                }
            } catch (RuntimeException failure) {
                if (running) Log.w("AgentShellAudio", "audio discard stopped", failure);
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }

        void close() throws Exception {
            running = false;
            Exception failure = null;
            AudioRecord source = record;
            record = null;
            if (source != null) {
                try {
                    if (source.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        source.stop();
                    }
                } catch (Exception stopFailure) {
                    failure = stopFailure;
                }
                Thread sink = drainer;
                drainer = null;
                if (sink != null) {
                    try {
                        sink.join(1500);
                        if (sink.isAlive()) {
                            throw new IllegalStateException("Audio sink cleanup not confirmed");
                        }
                    } catch (Exception joinFailure) {
                        if (failure == null) failure = joinFailure;
                        else failure.addSuppressed(joinFailure);
                    }
                }
                try {
                    source.release();
                } catch (Exception releaseFailure) {
                    if (failure == null) failure = releaseFailure;
                    else failure.addSuppressed(releaseFailure);
                }
            }
            if (registered) {
                try {
                    AudioManager.class.getMethod("unregisterAudioPolicy", policyType)
                            .invoke(manager, policy);
                    registered = false;
                    Log.i("AgentShellAudio", "target audio restored");
                } catch (Exception restoreFailure) {
                    // Keep ownership so a later close() can retry the unregister.
                    if (failure == null) failure = restoreFailure;
                    else failure.addSuppressed(restoreFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
