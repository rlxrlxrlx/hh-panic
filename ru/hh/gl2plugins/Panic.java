package ru.hh.gl2plugins;

import org.graylog2.plugin.GraylogServer;
import org.graylog2.plugin.logmessage.LogMessage;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.outputs.OutputStreamConfiguration;
import org.graylog2.plugin.streams.Stream;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;
import static java.nio.file.StandardCopyOption.*;

public class Panic implements MessageOutput {
    private class LogEntry {
        Integer level;
        Integer count;
        String streams;

        private LogEntry(Integer level, Integer count, String streams) {
            this.level = level;
            this.count = count;
            this.streams = streams;
        }
    }

    public static final String NAME = "Panic";

    private static ConcurrentHashMap<String, LogEntry> messageCounts = new ConcurrentHashMap<String, LogEntry>();

    private String getStreamsString(List<Stream> streams) {
        String res = "";
        for (Stream s : streams) {
            if (!(s.getTitle().equals("panic") && streams.size() > 1)) {
                res += "<a href='http://graylog.hh.ru/streams/" + s.getId() + "-" + s.getTitle() + "/messages'>" + s.getTitle() + "</a> ";
            }
        }
        if (streams.size() == 0) {
            res = "(no streams)";
        }
        return res;
    }

    @Override
    public void initialize(Map<String, String> config) throws MessageOutputConfigurationException {
    }

    @Override
    public void write(List<LogMessage> messages, OutputStreamConfiguration streamConfiguration, GraylogServer server) throws Exception {
        if (messages.size() > 0) {
            for (LogMessage m : messages) {
                if (m.getLevel() <= 4) {
                    String escaped = escapeHtml4(m.getShortMessage().replace('\n', ' ')).replaceAll("[0-9]", "");
                    messageCounts.put(escaped, messageCounts.containsKey(escaped) ?
                            new LogEntry(messageCounts.get(escaped).level, messageCounts.get(escaped).count + 1, messageCounts.get(escaped).streams)
                            :
                            new LogEntry(m.getLevel(), 1, getStreamsString(m.getStreams())));
                }
            }
            long stamp = new Date().getTime() + new Random().nextLong();
            BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/panic-www/superpanic.html." + stamp));

            bw.write("<html><head><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>");
            bw.write("<div class='container'><h2><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" + new Date() + " </h2><table class='table table-striped table-condenced'>");

            LinkedList<Map.Entry<String, LogEntry>> sorted = new LinkedList<Map.Entry<String, LogEntry>>(messageCounts.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, LogEntry>>() {
                @Override
                public int compare(Map.Entry<String, LogEntry> o1, Map.Entry<String, LogEntry> o2) {
                    if (!o2.getValue().count.equals(o1.getValue().count)) {
                        return o2.getValue().count.compareTo(o1.getValue().count);
                    }
                    return o1.getKey().compareTo(o2.getKey());
                }
            });

            for (Map.Entry<String, LogEntry> e : sorted) {
                bw.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + "><td>" + e.getValue().count + "</td><td>" + e.getValue().streams + "</td><td>" + e.getKey() + "</td></tr>\n");
            }
            bw.write("</div></table></body></html>");
            bw.close();
            Files.move(Paths.get("/tmp/panic-www/superpanic.html." + stamp), Paths.get("/tmp/panic-www/current.html"), ATOMIC_MOVE);
        }
    }

    @Override
    public Map<String, String> getRequestedConfiguration() {
        return new HashMap<String, String>() {{
            put("stub", "stub");
        }};
    }

    @Override
    public Map<String, String> getRequestedStreamConfiguration() {
        return new HashMap<String, String>() {{
            put("stub", "stub");
        }};
    }

    @Override
    public String getName() {
        return NAME;
    }
}
