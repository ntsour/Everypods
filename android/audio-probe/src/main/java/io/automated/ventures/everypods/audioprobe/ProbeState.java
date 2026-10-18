package io.automated.ventures.everypods.audioprobe;

/** Local, process-memory-only probe. Never writes or transmits captured audio. */
final class ProbeState {
    static final int RATE = 48000;
    static volatile boolean running;
    static volatile long started, samples;
    static volatile double level;
    static volatile String error;
    static volatile boolean systemStopped;
    static volatile int uid;
    static volatile boolean muteRequested;
    private static final short[] ring = new short[RATE * 10];
    private static int position, size;

    static synchronized void reset(int sourceUid) {
        position = size = 0;
        java.util.Arrays.fill(ring, (short) 0);
        samples = 0; level = 0; error = null; systemStopped = false; uid = sourceUid;
        started = android.os.SystemClock.elapsedRealtime();
    }
    static synchronized void append(short[] data, int count) {
        double squares = 0;
        for (int i = 0; i < count; i++) {
            ring[position] = data[i];
            position = (position + 1) % ring.length;
            squares += (double) data[i] * data[i];
        }
        size = Math.min(ring.length, size + count);
        samples += count;
        level = Math.sqrt(squares / count) / 32768.0;
    }
    static synchronized short[] snapshot() {
        short[] result = new short[size];
        int begin = (position - size + ring.length) % ring.length;
        for (int i = 0; i < size; i++) result[i] = ring[(begin + i) % ring.length];
        return result;
    }
    static synchronized void clear() {
        java.util.Arrays.fill(ring, (short) 0); size = position = 0;
        samples = 0; level = 0;
    }
}
