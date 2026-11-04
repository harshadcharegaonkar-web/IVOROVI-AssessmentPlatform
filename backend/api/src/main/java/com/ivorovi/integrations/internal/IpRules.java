package com.ivorovi.integrations.internal;

import com.ivorovi.platform.web.ApiException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** IP allow-list entries: single addresses or CIDR ranges (IPv4 and IPv6), literal addresses only (no DNS). */
final class IpRules {

    private IpRules() {}

    static List<String> normalize(List<String> entries) {
        List<String> out = new ArrayList<>();
        if (entries == null) return out;
        if (entries.size() > 50) throw ApiException.badRequest("invalid_ip_rule", "At most 50 address rules.");
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.trim();
            parse(e);
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    static boolean allowed(List<String> rules, String clientIp) {
        if (rules == null || rules.isEmpty()) return true;
        byte[] ip;
        try {
            ip = literal(clientIp);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String r : rules) {
            Rule rule = parse(r);
            if (rule.address().length == ip.length && matches(rule.address(), ip, rule.prefix())) return true;
        }
        return false;
    }

    private record Rule(byte[] address, int prefix) {}

    private static Rule parse(String e) {
        String[] parts = e.split("/", 2);
        byte[] a;
        try {
            a = literal(parts[0]);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_ip_rule", "Not an IP address or CIDR range: " + e);
        }
        int prefix = a.length * 8;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("invalid_ip_rule", "Invalid prefix length: " + e);
            }
            if (prefix < 0 || prefix > a.length * 8) throw ApiException.badRequest("invalid_ip_rule", "Invalid prefix length: " + e);
        }
        return new Rule(a, prefix);
    }

    /** Parses an IP literal without any name lookup. */
    private static byte[] literal(String s) {
        if (s == null || s.isBlank() || !s.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException();
        try {
            return InetAddress.getByName(s).getAddress();   // literal only: the character check above excludes host names
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean matches(byte[] net, byte[] ip, int prefix) {
        int full = prefix / 8, rest = prefix % 8;
        for (int i = 0; i < full; i++) if (net[i] != ip[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (net[full] & mask) == (ip[full] & mask);
    }
}
