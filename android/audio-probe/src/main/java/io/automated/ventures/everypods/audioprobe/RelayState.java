package io.automated.ventures.everypods.audioprobe;

import android.content.Context;
import android.net.*;
import java.net.*;
import java.util.*;

final class RelayState {
    static final int PORT = 47473, MAGIC = 0x45504132, ACK = 0x4f4b0002;
    static volatile boolean receiving, connected, sending, connecting;
    static volatile long receivedBytes, sentBytes, dropped;
    static volatile String code = "", error, addresses = "", output = "";

    static InetAddress localAddress(String text) throws Exception {
        if (!text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Enter a local IPv4 address");
        for (String part : text.split("\\.")) if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        InetAddress address = InetAddress.getByName(text);
        if (!address.isSiteLocalAddress()) throw new IllegalArgumentException("Use a private LAN or hotspot address");
        return address;
    }
    static String localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) result.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return String.join(" / ", result);
    }
    static String gateway(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties properties = cm.getLinkProperties(network);
            if (properties == null) continue;
            for (RouteInfo route : properties.getRoutes()) {
                InetAddress gateway = route.getGateway();
                if (route.isDefaultRoute() && gateway instanceof Inet4Address && gateway.isSiteLocalAddress()) return gateway.getHostAddress();
            }
        }
        return "";
    }
}
