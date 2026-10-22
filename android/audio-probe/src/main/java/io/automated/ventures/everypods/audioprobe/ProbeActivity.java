package io.automated.ventures.everypods.audioprobe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class ProbeActivity extends Activity {
    private static final String[] PACKAGES = {"com.google.android.youtube", "org.videolan.vlc", "au.com.shiftyjelly.pocketcasts"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Spinner apps;
    private Button start, stop, play, clear, send, receive, stopReceiver, gateway;
    private EditText host, pairing;
    private TextView relayStatus;
    private boolean pendingRelay;
    private String pendingHost = "", pendingCode = "";
    private Switch mute;
    private volatile boolean playing;
    private int pendingUid;
    private boolean consentPending;
    private boolean notificationRequested;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 400); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { pendingUid = state.getInt("uid"); consentPending = state.getBoolean("pending"); notificationRequested = state.getBoolean("notifications"); pendingRelay = state.getBoolean("relay"); pendingHost = state.getString("host", ""); pendingCode = state.getString("code", ""); }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density); body.setPadding(pad, pad, pad, pad);
        scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        text(body, getString(R.string.app_name), 25);
        text(body, getString(R.string.intro), 17);
        apps = new Spinner(this);
        apps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"YouTube", "VLC", "Pocket Casts"})); body.addView(apps);
        for (int index = 0; index < PACKAGES.length; index++) {
            try {
                if (getPackageManager().getApplicationInfo(PACKAGES[index], 0).uid == ProbeState.uid) apps.setSelection(index);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        start = button(body, R.string.start, () -> { pendingRelay = false; begin(); });
        stop = button(body, R.string.stop, () -> startService(new Intent(this, CaptureService.class).setAction("stop")));
        mute = new Switch(this); mute.setText(R.string.mute); mute.setChecked(ProbeState.muteRequested);
        mute.setOnCheckedChangeListener((v, checked) -> ProbeState.muteRequested = checked); body.addView(mute);
        text(body, getString(R.string.mute_hint), 15);
        status = text(body, "", 18); status.setTextIsSelectable(true);
        play = button(body, R.string.play, this::playSample);
        clear = button(body, R.string.clear, () -> { stopPlayback(); ProbeState.clear(); render(); });
        text(body, getString(R.string.relay_title), 23);
        text(body, getString(R.string.relay_intro), 17);
        receive = button(body, R.string.receive, this::beginReceive);
        stopReceiver = button(body, R.string.stop_receiver, () -> startService(new Intent(this, ReceiveService.class).setAction("stop")));
        relayStatus = text(body, "", 18); relayStatus.setTextIsSelectable(true);
        host = new EditText(this); host.setHint(R.string.host);
        host.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        host.setText(getPreferences(0).getString("host", "")); body.addView(host);
        gateway = button(body, R.string.gateway, () -> {
            String address = RelayState.gateway(this);
            if (address.isEmpty()) Toast.makeText(this, R.string.no_gateway, Toast.LENGTH_LONG).show();
            else host.setText(address);
        });
        pairing = new EditText(this); pairing.setHint(R.string.pairing);
        pairing.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pairing.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)}); body.addView(pairing);
        send = button(body, R.string.send, () -> {
            try {
                pendingHost = host.getText().toString().trim(); pendingCode = pairing.getText().toString().trim();
                RelayState.localAddress(pendingHost);
                if (!pendingCode.matches("[0-9]{6}")) throw new IllegalArgumentException("Enter the six-digit receiver code");
                getPreferences(0).edit().putString("host", pendingHost).apply();
                pendingRelay = true;
                if (networkPermission(4)) begin();
            } catch (Exception e) { Toast.makeText(this, getString(R.string.error, e.getMessage()), Toast.LENGTH_LONG).show(); }
        });
        text(body, getString(R.string.relay_steps), 17);
        text(body, getString(R.string.steps), 17);
    }
    private TextView text(LinearLayout body, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 12, 0, 12); body.addView(view); return view;
    }
    private Button button(LinearLayout body, int label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        body.addView(button, new LinearLayout.LayoutParams(-1, -2)); button.setOnClickListener(v -> action.run()); return button;
    }
    private void begin() {
        if (ProbeState.running || RelayState.receiving || consentPending) return;
        stopPlayback();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (!notificationRequested && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            notificationRequested = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1); return;
        }
        try { pendingUid = getPackageManager().getApplicationInfo(PACKAGES[apps.getSelectedItemPosition()], 0).uid; }
        catch (PackageManager.NameNotFoundException e) { Toast.makeText(this, R.string.not_installed, Toast.LENGTH_LONG).show(); return; }
        consentPending = true;
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 2);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 3 || request == 4) {
            String permission = Build.VERSION.SDK_INT >= 37 ? "android.permission.ACCESS_LOCAL_NETWORK" : Manifest.permission.NEARBY_WIFI_DEVICES;
            boolean granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            if (granted) { if (request == 3) beginReceive(); else begin(); }
            else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            return;
        }
        if (request == 1 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Notifications may be declined; foreground capture remains visible in Android's task manager.
            try { pendingUid = getPackageManager().getApplicationInfo(PACKAGES[apps.getSelectedItemPosition()], 0).uid; }
            catch (PackageManager.NameNotFoundException e) { Toast.makeText(this, R.string.not_installed, Toast.LENGTH_LONG).show(); return; }
            consentPending = true;
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 2);
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 2) return;
        consentPending = false;
        if (result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, CaptureService.class).putExtra("uid", pendingUid).putExtra("result", result).putExtra("token", data).putExtra("relay", pendingRelay).putExtra("host", pendingHost).putExtra("code", pendingCode));
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("relay", pendingRelay); out.putString("host", pendingHost); out.putString("code", pendingCode); out.putBoolean("notifications", notificationRequested); out.putInt("uid", pendingUid); out.putBoolean("pending", consentPending); super.onSaveInstanceState(out);
    }
    private void render() {
        boolean running = ProbeState.running;
        boolean idle = !running && !RelayState.receiving && !playing && !consentPending;
        send.setEnabled(idle); receive.setEnabled(idle); stopReceiver.setEnabled(RelayState.receiving);
        host.setEnabled(idle); pairing.setEnabled(idle); gateway.setEnabled(idle);
        start.setEnabled(idle); apps.setEnabled(idle);
        stop.setEnabled(running); mute.setEnabled(running);
        if (mute.isChecked() != ProbeState.muteRequested) mute.setChecked(ProbeState.muteRequested);
        play.setEnabled(idle && ProbeState.samples > 0); clear.setEnabled(idle);
        double db = ProbeState.level > 0 ? 20 * Math.log10(ProbeState.level) : -120;
        String description = getString(playing ? R.string.playing : running ? R.string.capturing : R.string.stopped);
        description += "\n" + getString(R.string.stats, ProbeState.samples / (double) ProbeState.RATE, db);
        if (running && ProbeState.level < 0.0001) description += "\n" + getString(R.string.silence);
        if (ProbeState.systemStopped) description += "\n" + getString(R.string.system_stopped);
        if (ProbeState.error != null) description += "\n" + getString(R.string.error, ProbeState.error);
        status.setText(description);
        String relay = getString(RelayState.connected ? R.string.receiving_audio : RelayState.receiving ? R.string.waiting_sender : RelayState.sending ? R.string.sending_audio : RelayState.connecting ? R.string.connecting_audio : R.string.relay_stopped);
        if (RelayState.receiving) relay += "\n" + getString(R.string.receiver_details, RelayState.addresses, RelayState.code);
        if (RelayState.connected) relay += "\n" + getString(R.string.output_device, RelayState.output);
        relay += "\n" + getString(R.string.relay_counts, RelayState.sentBytes / 1024, RelayState.receivedBytes / 1024, RelayState.dropped);
        if (RelayState.error != null) relay += "\n" + getString(R.string.error, RelayState.error);
        relayStatus.setText(relay);
    }
    private boolean networkPermission(int request) {
        String permission = Build.VERSION.SDK_INT >= 37 ? "android.permission.ACCESS_LOCAL_NETWORK" : Manifest.permission.NEARBY_WIFI_DEVICES;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !notificationRequested) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); notificationRequested = true;
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), request); return false;
    }
    private void beginReceive() {
        if (ProbeState.running || RelayState.receiving || consentPending) return;
        if (!networkPermission(3)) return;
        stopPlayback();
        startForegroundService(new Intent(this, ReceiveService.class));
    }
    private void playSample() {
        if (ProbeState.running || playing) return;
        short[] data = ProbeState.snapshot(); if (data.length == 0) return;
        playing = true;
        new Thread(() -> {
            AudioTrack track = null;
            AudioManager manager = getSystemService(AudioManager.class);
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0) playing = false; }, handler).build();
            try {
                if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw new IllegalStateException("Audio focus denied");
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(ProbeState.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
                if (!playing) return;
                int written = track.write(data, 0, data.length);
                if (written <= 0) throw new IllegalStateException("AudioTrack write " + written);
                track.play();
                while (playing && track.getPlaybackHeadPosition() < written) Thread.sleep(50);
            } catch (Exception e) { ProbeState.error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            finally {
                if (track != null) { try { track.stop(); } catch (RuntimeException ignored) { } track.release(); }
                manager.abandonAudioFocusRequest(focus);
                playing = false;
                java.util.Arrays.fill(data, (short) 0);
            }
        }, "AudioProbePlayback").start();
    }
    private void stopPlayback() { playing = false; }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); stopPlayback(); super.onPause(); }
}
