package io.automated.ventures.everypods.audioprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Locale;

public final class ReceiveService extends Service {
    private volatile boolean closing;
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile AudioTrack track;
    private AudioManager manager;
    private AudioFocusRequest focus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private AudioDeviceCallback deviceCallback;
    private boolean focusHeld;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (RelayState.receiving) return START_NOT_STICKY;
        if (ProbeState.running) { stopSelf(); return START_NOT_STICKY; }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("receive", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ReceiveService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "receive").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.receive_notification)).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop_receiver), stop).build()).build();
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            manager = getSystemService(AudioManager.class);
            if (bluetoothOutput() == null) throw new IllegalStateException("Connect AirPods audio to this device first");
            RelayState.error = null; RelayState.receivedBytes = 0; RelayState.connected = false;
            RelayState.code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
            RelayState.addresses = RelayState.localAddresses();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) { RelayState.error = "Audio focus lost; restart sender"; closeClient(); }
                }, main).build();
            deviceCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    if (bluetoothOutput() == null) { RelayState.error = "Bluetooth audio disconnected; receiver stopped"; stopSelf(); }
                }
            };
            manager.registerAudioDeviceCallback(deviceCallback, main);
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EveryPodsAudioProbe:receive");
            wake.acquire();
            RelayState.receiving = true;
            new Thread(this::listen, "AudioProbeReceiver").start();
        } catch (Exception e) { RelayState.error = e.getClass().getSimpleName() + ": " + e.getMessage(); stopSelf(); }
        return START_NOT_STICKY;
    }
    private static boolean bluetooth(AudioDeviceInfo device) {
        return device != null && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    private AudioDeviceInfo bluetoothOutput() {
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (bluetooth(device)) return device;
        return null;
    }
    private void listen() {
        try (ServerSocket listener = new ServerSocket()) {
            server = listener; listener.setReuseAddress(true); listener.bind(new InetSocketAddress(RelayState.PORT), 1);
            if (closing) return;
            while (!closing) {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    if (closing) break;
                    if (!socket.getInetAddress().isSiteLocalAddress()) continue;
                    socket.setSoTimeout(3000); socket.setTcpNoDelay(true); socket.setReceiveBufferSize(4096);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    if (in.readInt() != RelayState.MAGIC || in.readInt() != Integer.parseInt(RelayState.code)) {
                        out.writeInt(0); out.flush(); continue;
                    }
                    AudioDeviceInfo device = bluetoothOutput();
                    if (device == null) throw new IOException("Bluetooth audio unavailable");
                    if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new IOException("Audio focus denied");
                    focusHeld = true;
                    int min = AudioTrack.getMinBufferSize(ProbeState.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (min <= 0) throw new IOException("Unsupported playback format");
                    AudioTrack output = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(ProbeState.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min * 2, 19200)).build();
                    track = output;
                    if (!output.setPreferredDevice(device)) throw new IOException("Bluetooth output selection rejected");
                    output.addOnRoutingChangedListener(route -> {
                        AudioDeviceInfo actual = output.getRoutedDevice();
                        if (!closing && actual != null && !bluetooth(actual)) {
                            RelayState.error = "Audio route left Bluetooth; stream stopped"; closeClient();
                        }
                    }, main);
                    output.play();
                    // Prime routing with silence only; never send captured audio to a speaker.
                    output.write(new short[480], 0, 480, AudioTrack.WRITE_NON_BLOCKING);
                    long routeDeadline = SystemClock.elapsedRealtime() + 500;
                    while (!closing && output.getRoutedDevice() == null && SystemClock.elapsedRealtime() < routeDeadline) Thread.sleep(10);
                    if (!bluetooth(output.getRoutedDevice())) throw new IOException("Bluetooth route not active; no audio sent to speaker");
                    RelayState.receivedBytes = 0; RelayState.error = null; RelayState.output = output.getRoutedDevice().getProductName().toString(); RelayState.connected = true;
                    out.writeInt(RelayState.ACK); out.flush();
                    byte[] bytes = new byte[9600]; short[] pcm = new short[4800];
                    while (!closing && !socket.isClosed()) {
                        int length = in.readInt();
                        if (length <= 0 || length > bytes.length || length % 2 != 0) throw new IOException("Invalid audio frame length");
                        in.readFully(bytes, 0, length);
                        if (!bluetooth(output.getRoutedDevice())) throw new IOException("Bluetooth route lost");
                        for (int i = 0; i < length / 2; i++) pcm[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8));
                        int offset = 0, count = length / 2;
                        long writeDeadline = SystemClock.elapsedRealtime() + 2000;
                        while (offset < count && !closing && !socket.isClosed()) {
                            int wrote = output.write(pcm, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING);
                            if (wrote < 0) throw new IOException("AudioTrack write " + wrote);
                            if (SystemClock.elapsedRealtime() > writeDeadline) throw new IOException("Bluetooth playback stalled");
                            if (wrote == 0) { Thread.sleep(5); }
                            offset += wrote;
                        }
                        RelayState.receivedBytes += length;
                    }
                } catch (Exception e) {
                    if (!closing) { RelayState.error = e.getClass().getSimpleName() + ": " + e.getMessage(); Log.i("EveryPodsAudioProbe", "Stream ended: " + RelayState.error); }
                } finally {
                    RelayState.connected = false;
                    closeClient();
                    AudioTrack output = track; track = null;
                    if (output != null) { try { output.pause(); output.flush(); output.release(); } catch (RuntimeException ignored) { } }
                    if (focusHeld) { manager.abandonAudioFocusRequest(focus); focusHeld = false; }
                }
            }
        } catch (Exception e) {
            if (!closing) { RelayState.error = e.getClass().getSimpleName() + ": " + e.getMessage(); main.post(this::stopSelf); }
        }
    }
    private void closeClient() {
        Socket socket = client;
        if (socket != null) { try { socket.close(); } catch (IOException ignored) { } }
        AudioTrack output = track;
        if (output != null) { try { output.pause(); output.flush(); } catch (RuntimeException ignored) { } }
    }
    @Override public void onDestroy() {
        closing = true; RelayState.receiving = false; RelayState.connected = false;
        closeClient();
        ServerSocket listener = server;
        if (listener != null) { try { listener.close(); } catch (IOException ignored) { } }
        if (manager != null && deviceCallback != null) manager.unregisterAudioDeviceCallback(deviceCallback);
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
