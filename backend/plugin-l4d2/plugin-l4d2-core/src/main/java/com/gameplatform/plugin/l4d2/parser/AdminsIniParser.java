package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.admin.AdminEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * admins_simple.ini 解析器。
 *
 * <p>行格式：{@code "identity" "flags" // remark}
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class AdminsIniParser {

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^\"([^\"]+)\"\\s+\"([^\"]+)\"(?:\\s*//\\s*(.*))?$");

    public List<AdminEntry> parse(String content) {
        List<AdminEntry> entries = new ArrayList<>();
        if (content == null) return entries;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
            Matcher m = LINE_PATTERN.matcher(trimmed);
            if (!m.matches()) continue;
            AdminEntry entry = new AdminEntry();
            entry.setIdentity(m.group(1));
            entry.setFlags(m.group(2));
            entry.setRemark(m.group(3));
            entries.add(entry);
        }
        return entries;
    }

    public String serialize(List<AdminEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (AdminEntry e : entries) {
            sb.append('"').append(e.getIdentity()).append('"').append(' ');
            sb.append('"').append(e.getFlags()).append('"');
            if (e.getRemark() != null && !e.getRemark().isEmpty()) {
                sb.append(" // ").append(e.getRemark());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String addEntry(String content, AdminEntry entry) {
        String base = content == null ? "" : content;
        if (!base.endsWith("\n") && !base.isEmpty()) base += "\n";
        return base + serialize(List.of(entry));
    }

    public String removeEntry(String content, String identity) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher m = LINE_PATTERN.matcher(line.trim());
            if (m.matches() && m.group(1).equals(identity)) continue;
            sb.append(line).append("\n");
        }
        String result = sb.toString();
        if (!content.endsWith("\n") && result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public String updateEntry(String content, AdminEntry entry) {
        String removed = removeEntry(content, entry.getIdentity());
        return addEntry(removed, entry);
    }
}
