package com.hamonsoft.netismaker.service.deployconfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 배포 설정 값 조립용 순수 함수 모음. */
public final class DeployValueTemplates {

    private static final Pattern TOKEN = Pattern.compile("[{]([^{}]*)[}]");
    private static final String VALUE_TOKEN = "{값}";

    private DeployValueTemplates() {
    }

    public record DbValues(String host, int port, String database, String username, String password) {
        @Override
        public String toString() {
            return "DbValues[host=" + host + ", port=" + port + ", database=" + database
                    + ", username=" + username + ", password=***]";
        }
    }

    public static String fillDb(String template, DbValues v) {
        if (template == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String repl = switch (m.group(1)) {
                case "host" -> v.host();
                case "port" -> String.valueOf(v.port());
                case "database" -> v.database();
                case "username" -> v.username();
                case "password" -> v.password();
                case "username:url" -> urlEncode(v.username());
                case "password:url" -> urlEncode(v.password());
                default -> m.group();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(repl == null ? "" : repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Set<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    public static String wrapVariable(String format, String input) {
        if (format == null) {
            return input;
        }
        int idx = format.indexOf(VALUE_TOKEN);
        if (idx < 0 || input == null) {
            return input;
        }
        String prefix = format.substring(0, idx);
        String suffix = format.substring(idx + VALUE_TOKEN.length());
        if (!(prefix + suffix).isEmpty() && input.startsWith(prefix) && input.endsWith(suffix)
                && input.length() >= prefix.length() + suffix.length()) {
            return input;
        }
        return prefix + input + suffix;
    }

    static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
