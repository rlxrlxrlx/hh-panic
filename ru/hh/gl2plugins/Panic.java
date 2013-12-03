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
    public static final String NAME = "Panic";

    private static final int INTERVAL_SIZE = 120;
    private static final int SHOW_INTERVALS = 5;
    private static final int SUBSTRING_LENGTH = 100;
    private static final double MERGE_THRESHOLD = 0.6;
    private static final String HTML_TEMP_FILE_PREFIX = "/tmp/panic-www/superpanic.html.";
    private static final String SAVED_STATE_FILE = "/tmp/panic-www/saved-state";
    private static final String HTML_LITE = "/tmp/panic-www/current.html";
    private static final String HTML_FULL = "/tmp/panic-www/current-full.html";

    private static Date startedProcessing = null;
    private static Long messageCountProcessed = 0L;
    private static Long errWarnCountProcessed = 0L;

    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>> intervals = new ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>>();

    private static class LogEntry {
        Integer level;
        Integer count;
        String streams;
        String substringForMatching;

        private LogEntry(Integer level, Integer count, String streams, String substringForMatching) {
            this.level = level;
            this.count = count;
            this.streams = streams;
            this.substringForMatching = substringForMatching;
        }
    }

    /* http://www.catalysoft.com/articles/StrikeAMatch.html */
    private static double compareStrings(String str1, String str2) {
        char[] charStr1 = str1.toUpperCase().toCharArray();
        char[] charStr2 = str2.toUpperCase().toCharArray();
        ArrayList<Integer> pairs1 = wordLetterPairs(charStr1);
        ArrayList<Integer> pairs2 = wordLetterPairs(charStr2);
        int intersection = 0;
        int union = pairs1.size() + pairs2.size();
        for (Integer pairIndex1 : pairs1) {
            for (int j = 0; j < pairs2.size(); j++) {
                Integer pairIndex2 = pairs2.get(j);
                if (charStr1[pairIndex1] == charStr2[pairIndex2] &&
                        charStr1[pairIndex1 + 1] == charStr2[pairIndex2 + 1]) {
                    intersection++;
                    pairs2.remove(j);
                    break;
                }
            }
        }
        return (2.0 * intersection) / union;
    }

    private static ArrayList<Integer> wordLetterPairs(char[] str) {
        ArrayList<Integer> allPairs = new ArrayList<Integer>();
        for (int i = 0; i < str.length; i++) {
            if (i < str.length - 1 && !Character.isSpaceChar(str[i]) && !Character.isSpaceChar(str[i + 1])) {
                allPairs.add(i);
            }
        }
        return allPairs;
    }

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

    private static LinkedList<Map.Entry<String, LogEntry>> combineIntervals() {
        synchronized (NAME) {
            HashMap<String, LogEntry> combined = new HashMap<String, LogEntry>();
            for (Long key : intervals.keySet()) {
                for (String message : intervals.get(key).keySet()) {
                    combined.put(message, combined.containsKey(message) ?
                            new LogEntry(combined.get(message).level,
                                    combined.get(message).count + intervals.get(key).get(message).count,
                                    combined.get(message).streams.length() > intervals.get(key).get(message).streams.length() ?
                                            combined.get(message).streams : intervals.get(key).get(message).streams,
                                    combined.get(message).substringForMatching
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
            return sorted;
        }
    }

    private static void restoreState() throws IOException {
        if (new File(SAVED_STATE_FILE).exists()) {
            BufferedReader br = new BufferedReader(new FileReader(SAVED_STATE_FILE));
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
                intervals.get(stamp).put(message, new LogEntry(level, count, streams, message.substring(0, Math.min(message.length(), SUBSTRING_LENGTH))));
                timestamp = br.readLine();
            }
            br.close();
            if (!new File(SAVED_STATE_FILE).delete()) {
                System.err.println("panic: cannot delete saved-state");
            }
        }
    }

    private static void saveState() throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(SAVED_STATE_FILE));
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

    private static void generateHtmlWithEpicTemplateEngine() throws IOException {
        String stamp = new Date().getTime() + "" + new Random().nextLong();
        BufferedWriter bw = new BufferedWriter(new FileWriter(HTML_TEMP_FILE_PREFIX + stamp));
        BufferedWriter bwLite = new BufferedWriter(new FileWriter(HTML_TEMP_FILE_PREFIX + stamp + ".lite"));

        bw.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>");
        bwLite.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>");
        bw.write("<div style='margin-left: 3%; margin-right: 3%;'><br/><h2 style='display:inline;'><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" +
                new Date() +
                " </h2>&nbsp<span style='float:right;display:inline;'>" +
                (messageCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " messages/sec, " +
                (errWarnCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " err&warns/sec</span><table class='table table-striped table-condenced'>");
        bwLite.write("<div style='margin-left: 3%; margin-right: 3%;'><br/><h2 style='display:inline;'><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" +
                new Date() +
                " </h2>&nbsp<span style='float:right;display:inline;'>" +
                (messageCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " messages/sec, " +
                (errWarnCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " err&warns/sec</span><table class='table table-striped table-condenced'>");

        bw.write("<h3>Логи за последние " + (INTERVAL_SIZE / 60) * (SHOW_INTERVALS - 1) + " - " + (INTERVAL_SIZE / 60) * SHOW_INTERVALS + " минут</h3>");
        bwLite.write("<h3>Логи за последние " + (INTERVAL_SIZE / 60) * (SHOW_INTERVALS - 1) + " - " + (INTERVAL_SIZE / 60) * SHOW_INTERVALS + " минут</h3>");

        LinkedList<Map.Entry<String, LogEntry>> sorted = combineIntervals();

        int cnt = 0;
        for (Map.Entry<String, LogEntry> e : sorted) {
            bw.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + "><td>" + e.getValue().count + "</td><td>" + e.getValue().streams + "</td><td>" + e.getKey() + "</td></tr>\n");
            if (cnt < 100) {
                bwLite.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + "><td>" + e.getValue().count + "</td><td>" + e.getValue().streams + "</td><td>" + e.getKey() + "</td></tr>\n");
            }
            cnt++;
        }
        bw.write("</table></div></body></html>");
        bwLite.write("</table><a href='/panic/current-full.html'>Show me everything</a><br/><br/></div></body></html>");
        bw.close();
        bwLite.close();
        Files.move(Paths.get(HTML_TEMP_FILE_PREFIX + stamp + ".lite"), Paths.get(HTML_LITE), ATOMIC_MOVE);
        Files.move(Paths.get(HTML_TEMP_FILE_PREFIX + stamp), Paths.get(HTML_FULL), ATOMIC_MOVE);
    }

    private static void writeSync(List<LogMessage> messages) throws Exception {
        synchronized (NAME) {
            if (startedProcessing == null) {
                startedProcessing = new Date();
            }
            if (intervals.keySet().size() == 0) {
                restoreState();
            }
        }
        Long curTimestamp = new Date().getTime() / 1000;
        Long curInterval = curTimestamp - curTimestamp % INTERVAL_SIZE;
        int errWarnCount = 0;
        if (messages.size() > 0) {
            LinkedList<Map.Entry<String, LogEntry>> existing = combineIntervals();

            HashMap<String, LinkedList<Map.Entry<String, LogEntry>>> existingByFirstTwoLetters = new HashMap<String, LinkedList<Map.Entry<String, LogEntry>>>();
            for (Map.Entry<String, LogEntry> e : existing) {
                String theKey = e.getKey().substring(0, 2);
                if (!existingByFirstTwoLetters.containsKey(theKey)) {
                    existingByFirstTwoLetters.put(theKey, new LinkedList<Map.Entry<String, LogEntry>>());
                }
                existingByFirstTwoLetters.get(theKey).add(e);
            }

            for (LogMessage m : messages) {
                if (m.getLevel() <= 4 && !m.getShortMessage().startsWith("Error getting object ")) {
                    String escaped = escapeHtml4(m.getShortMessage().replace('\n', ' ').replace('\r', ' ')).replaceAll("[0-9]", "");
                    double bestSimilarity = 0;
                    String mostSimilar = escaped;
                    if (escaped.length() > 1) {
                        LinkedList<Map.Entry<String, LogEntry>> listToMatch = existingByFirstTwoLetters.get(escaped.substring(0, 2));
                        if (listToMatch != null) {
                            for (Map.Entry<String, LogEntry> e : listToMatch) {
                                if (e.getKey().length() > 1) {
                                    double curSimilarity = compareStrings(escaped.substring(0, Math.min(escaped.length(), SUBSTRING_LENGTH)), e.getValue().substringForMatching);
                                    if (curSimilarity > bestSimilarity) {
                                        bestSimilarity = curSimilarity;
                                        mostSimilar = e.getKey();
                                    }
                                }
                            }
                        }
                    }
                    if (bestSimilarity > MERGE_THRESHOLD) {
                        escaped = mostSimilar;
                    }
                    if (!intervals.containsKey(curInterval)) {
                        intervals.put(curInterval, new ConcurrentHashMap<String, LogEntry>());
                    }
                    LogEntry newEntry = intervals.get(curInterval).containsKey(escaped) ?
                            new LogEntry(intervals.get(curInterval).get(escaped).level,
                                    intervals.get(curInterval).get(escaped).count + 1,
                                    intervals.get(curInterval).get(escaped).streams,
                                    intervals.get(curInterval).get(escaped).substringForMatching)
                            :
                            new LogEntry(m.getLevel(), 1, getStreamsString(m.getStreams()), escaped.substring(0, Math.min(escaped.length(), SUBSTRING_LENGTH)));
                    intervals.get(curInterval).put(escaped, newEntry);
                    if (bestSimilarity <= MERGE_THRESHOLD) {
                        existingByFirstTwoLetters.get(escaped.substring(0, 2)).add(new AbstractMap.SimpleEntry<String, LogEntry>(escaped, newEntry));
                    }
                    errWarnCount++;
                }
            }
            generateHtmlWithEpicTemplateEngine();
        }
        synchronized (NAME) {
            messageCountProcessed += messages.size();
            errWarnCountProcessed += errWarnCount;
            /* clean up intervals */
            HashSet<Long> keysToRemove = new HashSet<Long>();
            for (Long key : intervals.keySet()) {
                if (key + INTERVAL_SIZE * SHOW_INTERVALS < curTimestamp) {
                    keysToRemove.add(key);
                }
            }
            for (Long key : keysToRemove) {
                intervals.remove(key);
            }
            if (new Random().nextInt(3) == 0) {
                saveState();
            }
        }
    }

    @Override
    public void initialize(Map<String, String> config) throws MessageOutputConfigurationException {
    }

    @Override
    public void write(List<LogMessage> messages, OutputStreamConfiguration streamConfiguration, GraylogServer server) throws Exception {
        writeSync(messages);
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
        String s1 = "GET /logic/resume/view?hash=ddeefffffaedf&field=lang&all_blocks=True&representation=hh.web. (...): user None not allowed to view resumes set([''])";
        String s2 = "GET /logic/resume/view?hash=baacffaacedfbe&field=lang&all_blocks=True&representation=hh.web. (...): user None not allowed to view resumes set([''])";
        System.out.println(compareStrings(
                s1.substring(0, Math.min(SUBSTRING_LENGTH, s1.length()))
                ,
                s2.substring(0, Math.min(SUBSTRING_LENGTH, s2.length()))
        ));
    }
}
