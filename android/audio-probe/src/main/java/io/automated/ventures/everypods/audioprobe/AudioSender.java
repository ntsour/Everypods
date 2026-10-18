package io.automated.ventures.everypods.audioprobe;

import android.os.SystemClock;
import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Bounded, mono PCM relay. Each new Start creates one connection; no automatic reconnect. */
final class AudioSender implements AutoCloseable {
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(5);
    private final Socket socket = new Socket();
    private volatile boolean closed;
    private volatile long writeAt;
    private volatile boolean writing;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    AudioSender(Context context, String host, String code, java.util.function.Consumer<Exception> onError) {
        RelayState.connecting = true; RelayState.sentBytes = RelayState.dropped = 0; RelayState.error = null;
        Thread thread = new Thread(() -> {
            try {
                InetAddress address = RelayState.localAddress(host);
                if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("Enter the six-digit receiver code");
                // A hotspot without Internet may not be Android's default network.
                // Bind this socket to the Wi-Fi network containing the receiver.
                ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
                Network wifi = null;
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    LinkProperties links = cm.getLinkProperties(network);
                    if (caps == null || links == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    for (RouteInfo route : links.getRoutes()) {
                        if (!route.isDefaultRoute() && route.getDestination().contains(address)) wifi = network;
                    }
                }
                if (wifi == null) throw new IOException("Join the receiver's Wi-Fi or Pixel hotspot first");
                wifi.bindSocket(socket);
                socket.setTcpNoDelay(true); socket.setSendBufferSize(4096); socket.setSoTimeout(3000);
                socket.connect(new InetSocketAddress(address, RelayState.PORT), 5000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());
                out.writeInt(RelayState.MAGIC); out.writeInt(Integer.parseInt(code)); out.flush();
                if (in.readInt() != RelayState.ACK) throw new IOException("Pairing or Bluetooth output rejected");
                if (closed) return;
                queue.clear(); RelayState.connecting = false; RelayState.sending = true;
                while (!closed) {
                    byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;
                    writeAt = SystemClock.elapsedRealtime(); writing = true;
                    out.writeInt(chunk.length); out.write(chunk); out.flush();
                    writing = false; RelayState.sentBytes += chunk.length;
                }
            } catch (Exception e) { if (!closed) onError.accept(e); }
            finally { close(); }
        }, "AudioProbeSender");
        watchdog.scheduleAtFixedRate(() -> {
            if (!closed && writing && SystemClock.elapsedRealtime() - writeAt > 2000) {
                onError.accept(new IOException("Network stalled; relay stopped")); close();
            }
        }, 1, 1, TimeUnit.SECONDS);
        thread.start();
    }
    void offer(short[] samples, int count) {
        if (closed || !RelayState.sending) return;
        byte[] chunk = new byte[count * 2];
        for (int i = 0; i < count; i++) { chunk[i * 2] = (byte) samples[i]; chunk[i * 2 + 1] = (byte) (samples[i] >> 8); }
        if (!queue.offer(chunk)) {
            queue.poll(); RelayState.dropped++;
            queue.offer(chunk);
        }
    }
    @Override public void close() {
        closed = true; RelayState.sending = false; RelayState.connecting = false;
        try { socket.close(); } catch (IOException ignored) { }
        queue.clear(); watchdog.shutdownNow();
    }
}
