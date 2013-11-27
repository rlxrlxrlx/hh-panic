package ru.hh.gl2plugins;

import org.graylog2.plugin.GraylogServer;
import org.graylog2.plugin.logmessage.LogMessage;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.outputs.OutputStreamConfiguration;
import org.graylog2.plugin.streams.Stream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;
import static java.nio.file.StandardCopyOption.*;

public class Panic implements MessageOutput {
    private static class LogEntry {
        Integer level;
        Integer count;
        String streams;

        private LogEntry(Integer level, Integer count, String streams) {
            this.level = level;
            this.count = count;
            this.streams = streams;
        }
    }

    /* http://www.catalysoft.com/articles/StrikeAMatch.html */
    private static double compareStrings(String str1, String str2) {
        ArrayList pairs1 = wordLetterPairs(str1.toUpperCase());
        ArrayList pairs2 = wordLetterPairs(str2.toUpperCase());
        int intersection = 0;
        int union = pairs1.size() + pairs2.size();
        for (Object pair1 : pairs1) {
            for (int j = 0; j < pairs2.size(); j++) {
                Object pair2 = pairs2.get(j);
                if (pair1.equals(pair2)) {
                    intersection++;
                    pairs2.remove(j);
                    break;
                }
            }
        }
        return (2.0 * intersection) / union;
    }

    private static ArrayList wordLetterPairs(String str) {
        ArrayList<String> allPairs = new ArrayList<String>();
        String[] words = str.split("\\s");
        for (String word : words) {
            String[] pairsInWord = letterPairs(word);
            Collections.addAll(allPairs, pairsInWord);
        }
        return allPairs;
    }

    private static String[] letterPairs(String str) {
        int numPairs = Math.max(0, str.length() - 1);
        String[] pairs = new String[numPairs];
        for (int i = 0; i < numPairs; i++) {
            pairs[i] = str.substring(i, i + 2);
        }
        return pairs;
    }

    public static final String NAME = "Panic";

    private static final int INTERVAL_SIZE = 120;
    private static final int SHOW_INTERVALS = 5;

    //private static ConcurrentHashMap<String, LogEntry> messageCounts = new ConcurrentHashMap<String, LogEntry>();
    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>> intervals = new ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>>();

    private static String getStreamsString(List<Stream> streams) {
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
        writeSync(messages, streamConfiguration, server);
    }

    private static void writeSync(List<LogMessage> messages, OutputStreamConfiguration streamConfiguration, GraylogServer server) throws Exception {
        // first run
        if (intervals.keySet().size() == 0) {
            synchronized (NAME) {
                if (new File("/tmp/panic-www/saved-state").exists()) {
                    BufferedReader br = new BufferedReader(new FileReader("/tmp/panic-www/saved-state"));
                    String timestamp = br.readLine();
                    while (timestamp != null) {
                        String message = br.readLine();
                        Integer count = Integer.parseInt(br.readLine());
                        Integer level = Integer.parseInt(br.readLine());
                        String streams = br.readLine();
                        Long stamp = Long.parseLong(timestamp);
                        if (!intervals.containsKey(stamp)) {
                            intervals.put(stamp, new ConcurrentHashMap<String, LogEntry>());
                        }
                        intervals.get(stamp).put(message, new LogEntry(level, count, streams));
                        timestamp = br.readLine();
                    }
                    br.close();
                    new File("/tmp/panic-www/saved-state").delete();
                }
            }
        }
        Long curTimestamp = new Date().getTime() / 1000;
        Long curInterval = curTimestamp - curTimestamp % INTERVAL_SIZE;
        if (messages.size() > 0) {
            for (LogMessage m : messages) {
                if (m.getLevel() <= 4) {
                    String escaped = escapeHtml4(m.getShortMessage().replace('\n', ' ').replace('\r', ' ')).replaceAll("[0-9]", "");
                    double similarity = 0;
                    String similar = escaped;
                    if (escaped.length() > 1) {
                        for (Long key : intervals.keySet()) {
                            for (String message : intervals.get(key).keySet()) {
                                if (message.length() > 1) {
                                    double new_similarity = compareStrings(escaped, message);
                                    if (new_similarity > similarity) {
                                        similarity = new_similarity;
                                        similar = message;
                                    }
                                }
                            }
                        }
                    }
                    if (similarity > 0.8) {
                        escaped = similar;
                    }

                    if (!intervals.containsKey(curInterval)) {
                        intervals.put(curInterval, new ConcurrentHashMap<String, LogEntry>());
                    }
                    intervals.get(curInterval).put(escaped, intervals.get(curInterval).containsKey(escaped) ?
                            new LogEntry(intervals.get(curInterval).get(escaped).level,
                                    intervals.get(curInterval).get(escaped).count + 1,
                                    intervals.get(curInterval).get(escaped).streams)
                            :
                            new LogEntry(m.getLevel(), 1, getStreamsString(m.getStreams())));
                }
            }
            String stamp = new Date().getTime() + "" + new Random().nextLong();
            BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/panic-www/superpanic.html." + stamp));
            BufferedWriter bw_lite = new BufferedWriter(new FileWriter("/tmp/panic-www/superpanic.html." + stamp + ".lite"));

            bw.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>");
            bw_lite.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>");
            bw.write("<div style='margin-left: 3%; margin-right: 3%;'><h2><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" + new Date() + " </h2><table class='table table-striped table-condenced'>");
            bw_lite.write("<div style='margin-left: 3%; margin-right: 3%;'><h2><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" + new Date() + " </h2><table class='table table-striped table-condenced'>");

            bw.write("<h3>Логи за последние " + (INTERVAL_SIZE / 60) * (SHOW_INTERVALS - 1) + " - " + (INTERVAL_SIZE / 60) * SHOW_INTERVALS + " минут</h3>");
            bw_lite.write("<h3>Логи за последние " + (INTERVAL_SIZE / 60) * (SHOW_INTERVALS - 1) + " - " + (INTERVAL_SIZE / 60) * SHOW_INTERVALS + " минут</h3>");

            HashMap<String, LogEntry> combined = new HashMap<String, LogEntry>();
            for (Long key : intervals.keySet()) {
                for (String message : intervals.get(key).keySet()) {
                    combined.put(message, combined.containsKey(message) ?
                            new LogEntry(combined.get(message).level,
                                    combined.get(message).count + intervals.get(key).get(message).count,
                                    combined.get(message).streams.length() > intervals.get(key).get(message).streams.length() ?
                                            combined.get(message).streams : intervals.get(key).get(message).streams
                            )
                            :
                            intervals.get(key).get(message)
                    );
                }
            }

            LinkedList<Map.Entry<String, LogEntry>> sorted = new LinkedList<Map.Entry<String, LogEntry>>(combined.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, LogEntry>>() {
                @Override
                public int compare(Map.Entry<String, LogEntry> o1, Map.Entry<String, LogEntry> o2) {
                    if (!o2.getValue().count.equals(o1.getValue().count)) {
                        return o2.getValue().count.compareTo(o1.getValue().count);
                    }
                    return o1.getKey().compareTo(o2.getKey());
                }
            });

            int cnt = 0;
            for (Map.Entry<String, LogEntry> e : sorted) {
                bw.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + "><td>" + e.getValue().count + "</td><td>" + e.getValue().streams + "</td><td>" + e.getKey() + "</td></tr>\n");
                if (cnt < 100) {
                    bw_lite.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + "><td>" + e.getValue().count + "</td><td>" + e.getValue().streams + "</td><td>" + e.getKey() + "</td></tr>\n");
                }
                cnt++;
            }
            bw.write("</table></div></body></html>");
            bw_lite.write("</table><a href='/panic/current-full.html'>Show me everything</a><br/><br/></div></body></html>");
            bw.close();
            bw_lite.close();
            Files.move(Paths.get("/tmp/panic-www/superpanic.html." + stamp + ".lite"), Paths.get("/tmp/panic-www/current.html"), ATOMIC_MOVE);
            Files.move(Paths.get("/tmp/panic-www/superpanic.html." + stamp), Paths.get("/tmp/panic-www/current-full.html"), ATOMIC_MOVE);
        }
        /* clean up intervals */
        synchronized (NAME) {
            HashSet<Long> keysToRemove = new HashSet<Long>();
            for (Long key : intervals.keySet()) {
                if (key + INTERVAL_SIZE * SHOW_INTERVALS < curTimestamp) {
                    keysToRemove.add(key);
                }
            }
            for (Long key : keysToRemove) {
                intervals.remove(key);
            }
        }
        /* save state for future use */
        if (new Random().nextInt(10) == 0) {
            synchronized (NAME) {
                BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/panic-www/saved-state"));
                for (Long key : intervals.keySet()) {
                    for (String message : intervals.get(key).keySet()) {
                        LogEntry e = intervals.get(key).get(message);
                        bw.write(key + "\n");
                        bw.write(message + "\n");
                        bw.write(e.count + "\n");
                        bw.write(e.level + "\n");
                        bw.write(e.streams + "\n");
                    }
                }
                bw.close();
            }
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

    public static void main(String[] args) {
        System.out.println(compareStrings("Request to \"http://...:/tickets/resetPassword\" with params \"email\"=\"\", header \"X-Forwarded-For\"=\"...\", header \"X-Request-ID\"=\"dbbdfefffcbbaffe\" has returned HTTP status is \" Bad Request\", header \"X-HHID-War", "Request to \"http://...:/tickets/resetPassword\" with params \"email\"=\"\", header \"X-Forwarded-For\"=\"...\", header \"X-Request-ID\"=\"edbeabedbccefb\" has returned HTTP status is \" Bad Request\", header \"X-HHID-Wa"));
    }
}
