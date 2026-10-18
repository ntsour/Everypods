package io.automated.ventures.everypods.audioprobe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.Log;

public final class CaptureService extends Service {
    private MediaProjection projection;
    private AudioRecord recorder;
    private Thread worker;
    private volatile boolean closing;
    private AudioSender sender;
    private PowerManager.WakeLock wake;
    private int savedVolume = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable volumePoll = new Runnable() {
        @Override public void run() {
            if (closing) return;
            updateMute(); main.postDelayed(this, 300);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) { finishCapture(); return START_NOT_STICKY; }
        if (ProbeState.running) return START_NOT_STICKY;
        if (RelayState.receiving) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, CaptureService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification)).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("RECORD_AUDIO");
            Intent token = intent.getParcelableExtra("token", Intent.class);
            if (token == null) throw new IllegalArgumentException("Missing capture consent");
            ProbeState.reset(intent.getIntExtra("uid", -1));
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", 0), token);
            if (projection == null) throw new IllegalStateException("No MediaProjection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!closing) { ProbeState.systemStopped = true; finishCapture(); }
                }
            }, main);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(ProbeState.uid)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int minimum = AudioRecord.getMinBufferSize(ProbeState.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Unsupported audio format");
            recorder = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(ProbeState.RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, 19200)).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord initialization failed");
            if (intent.getBooleanExtra("relay", false)) {
                sender = new AudioSender(this, intent.getStringExtra("host"), intent.getStringExtra("code"), error -> {
                    if (!closing) {
                        RelayState.error = error.getClass().getSimpleName() + ": " + error.getMessage();
                        ProbeState.error = RelayState.error;
                        main.post(this::finishCapture);
                    }
                });
                wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EveryPodsAudioProbe:send");
                wake.acquire();
            }
            recorder.startRecording();
            ProbeState.running = true;
            AudioRecord input = recorder;
            worker = new Thread(() -> {
                short[] chunk = new short[960];
                try {
                    while (!closing) {
                        int count = input.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                        if (count < 0) throw new IllegalStateException("AudioRecord read " + count);
                        if (count > 0 && !closing) {
                            ProbeState.append(chunk, count);
                            if (sender != null) sender.offer(chunk, count);
                        }
                    }
                } catch (Exception e) {
                    if (!closing) { ProbeState.error = e.getClass().getSimpleName() + ": " + e.getMessage(); main.post(this::finishCapture); }
                }
            }, "AudioProbeCapture");
            worker.start(); main.post(volumePoll);
            Log.i("EveryPodsAudioProbe", "Capture started uid=" + ProbeState.uid);
        } catch (Exception e) {
            ProbeState.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e("EveryPodsAudioProbe", "Capture failed", e); finishCapture();
        }
        return START_NOT_STICKY;
    }
    private void updateMute() {
        AudioManager audio = getSystemService(AudioManager.class);
        try {
            if (ProbeState.muteRequested && savedVolume < 0) {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } else if (!ProbeState.muteRequested) restoreVolume();
        } catch (RuntimeException e) { ProbeState.error = "Volume: " + e.getMessage(); }
    }
    private void restoreVolume() {
        if (savedVolume < 0) return;
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
        savedVolume = -1;
    }
    private void finishCapture() { cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void cleanup() {
        if (closing) return;
        closing = true; ProbeState.running = false; ProbeState.muteRequested = false;
        main.removeCallbacksAndMessages(null);
        if (sender != null) { sender.close(); sender = null; }
        if (wake != null && wake.isHeld()) wake.release();
        if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) { } recorder.release(); recorder = null; }
        if (projection != null) { projection.stop(); projection = null; }
        try { restoreVolume(); } catch (RuntimeException e) { Log.w("EveryPodsAudioProbe", "Restore media volume failed", e); }
        Log.i("EveryPodsAudioProbe", "Capture stopped samples=" + ProbeState.samples + " system=" + ProbeState.systemStopped);
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
}
