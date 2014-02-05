package ru.hh.gl2plugins.panic;

import org.graylog2.plugin.GraylogServer;
import org.graylog2.plugin.logmessage.LogMessage;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.outputs.OutputStreamConfiguration;
import org.graylog2.plugin.streams.Stream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;
import static java.nio.file.StandardCopyOption.*;

public class Panic implements MessageOutput {
    // Graylog needs this (it seems)
    public static final String NAME = "Panic";

    private static final String OPTIONS_FILE = "/etc/panic.conf";
    private static Properties options = new Properties();

    private static Date startedProcessing = null;
    private static Long messageCountProcessed = 0L;
    private static Long errWarnCountProcessed = 0L;
    private static Date lastTimeMessagesReported = null;
    private static Date lastTimeJiraTaskCreated = null;
    private static JiraTask lastReportedJiraTask = null;
    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>> intervals = new ConcurrentHashMap<Long, ConcurrentHashMap<String, LogEntry>>();
    private static ConcurrentHashMap<String, CopyOnWriteArrayList<JiraTask>> jiraTasks = new ConcurrentHashMap<String, CopyOnWriteArrayList<JiraTask>>();

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

    private static String calcMD5sum(String s) {
        StringBuilder sb = new StringBuilder();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] rawDigest = md.digest(s.getBytes("UTF-8"));
            for (byte b : rawDigest) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
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
        String savedStateFile = options.getProperty("work_directory") + "/saved-state";
        if (new File(savedStateFile).exists()) {
            BufferedReader br = new BufferedReader(new FileReader(savedStateFile));
            String timestamp = br.readLine();
            while (timestamp != null && !timestamp.equals("%%JIRA_TASKS%%")) {
                String message = br.readLine();
                Integer count = Integer.parseInt(br.readLine());
                Integer level = Integer.parseInt(br.readLine());
                String streams = br.readLine();
                Long stamp = Long.parseLong(timestamp);
                if (!intervals.containsKey(stamp)) {
                    intervals.put(stamp, new ConcurrentHashMap<String, LogEntry>());
                }
                intervals.get(stamp).put(message, new LogEntry(level, count, streams,
                        message.substring(0,
                                Math.min(message.length(),
                                        Integer.parseInt(options.getProperty("substring_length"))))));
                timestamp = br.readLine();
            }
            if (timestamp != null && timestamp.equals("%%JIRA_TASKS%%")) {
                String message = br.readLine();
                while (message != null) {
                    String summary = message;
                    Integer taskCount = Integer.parseInt(br.readLine());
                    CopyOnWriteArrayList<JiraTask> taskList = new CopyOnWriteArrayList<JiraTask>();
                    for (int i = 0; i < taskCount; i++) {
                        String issue = br.readLine();
                        Boolean closed = Boolean.parseBoolean(br.readLine());
                        Date lastUpdate = new Date(Long.parseLong(br.readLine()));
                        JiraTask task = new JiraTask(summary, issue, closed, lastUpdate);
                        taskList.add(task);
                    }
                    jiraTasks.put(summary, taskList);
                    message = br.readLine();
                }
            }
            br.close();
            if (!new File(savedStateFile).delete()) {
                System.err.println("panic: cannot delete saved-state");
            }
        }
    }

    private static void saveState() throws IOException {
        String savedStateFile = options.getProperty("work_directory") + "/saved-state";
        String stamp = new Date().getTime() + "" + new Random().nextLong();
        BufferedWriter bw = new BufferedWriter(new FileWriter(savedStateFile + stamp));
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
        bw.write("%%JIRA_TASKS%%\n");
        for (String key : jiraTasks.keySet()) {
            bw.write(key + "\n");
            Integer taskCount = jiraTasks.get(key).size();
            bw.write(taskCount + "\n");
            for (int i = 0; i < taskCount; i++) {
                bw.write(jiraTasks.get(key).get(i).getIssue() + "\n");
                bw.write(jiraTasks.get(key).get(i).getClosed() + "\n");
                bw.write(jiraTasks.get(key).get(i).getLastUpdate().getTime() + "\n");
            }
        }
        bw.close();
        Files.move(Paths.get(savedStateFile + stamp), Paths.get(savedStateFile), ATOMIC_MOVE);
    }

    private static void generateHtmlWithEpicTemplateEngine() throws IOException {
        String fullFile = options.getProperty("apache_root") + "/current-full.html";
        String liteFile = options.getProperty("apache_root") + "/current.html";
        String stamp = new Date().getTime() + "" + new Random().nextLong();
        BufferedWriter bw = new BufferedWriter(new FileWriter(fullFile + stamp));
        BufferedWriter bwLite = new BufferedWriter(new FileWriter(liteFile + stamp));

        String header = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>panic</title><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap.min.css\"><link rel=\"stylesheet\" href=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/css/bootstrap-theme.min.css\"><script src=\"//netdna.bootstrapcdn.com/bootstrap/3.0.2/js/bootstrap.min.js\"></script><meta http-equiv=\"refresh\" content=\"60\"></head><body>";
        bw.write(header);
        bwLite.write(header);
        String top = "<div style='margin-left: 3%; margin-right: 3%;'><br/><h2 style='display:inline;'><a href='http://graylog.hh.ru'>GRAYLOG</a>&nbsp;&nbsp;&nbsp;" +
                new Date() +
                " </h2>&nbsp<span style='float:right;display:inline;'>" +
                (messageCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " messages/sec, " +
                (errWarnCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) +
                " err&warns/sec</span><table class='table table-striped table-condenced'>";
        bw.write(top);
        bwLite.write(top);

        String flash = "<h3>Логи за последние " + (Integer.parseInt(options.getProperty("interval_size")) / 60) *
                (Integer.parseInt(options.getProperty("show_intervals")) - 1) +
                " - " + (Integer.parseInt(options.getProperty("interval_size")) / 60) *
                Integer.parseInt(options.getProperty("show_intervals")) + " минут</h3>";
        bw.write(flash);
        bwLite.write(flash);

        LinkedList<Map.Entry<String, LogEntry>> sorted = combineIntervals();

        int cnt = 0;
        for (Map.Entry<String, LogEntry> e : sorted) {
            bw.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + ">" +
                    "<td>" + e.getValue().count + "</td>" +
                    "<td>" + e.getValue().streams + "</td>" +
                    "<td><a href='/panic/full_messages/" + calcMD5sum(e.getKey()) + ".txt'>example</a></td>" +
                    "<td>" + e.getKey() + "</td></tr>\n");
            if (cnt < 50) {
                String jiraTasksLink = "";
                double bestSimilarity = 0;
                CopyOnWriteArrayList<JiraTask> taskList = null;
                for (String reported : jiraTasks.keySet()) {
                    double curSimilarity = StrikeAMatch.compareStrings(reported, e.getValue().substringForMatching);
                    if (curSimilarity > bestSimilarity) {
                        bestSimilarity = curSimilarity;
                        taskList = jiraTasks.get(reported);
                    }
                }
                if (bestSimilarity > Double.parseDouble(options.getProperty("merge_threshold")) && taskList != null) {
                    for (JiraTask task : taskList) {
                        if (task.getClosed()) {
                            jiraTasksLink += "<a style='text-decoration:line-through;' href='https://jira.hh.ru/browse/" + task.getIssue() + "'>" + task.getIssue() + "</a> ";
                        }
                        else {
                            jiraTasksLink += "<a href='https://jira.hh.ru/browse/" + task.getIssue() + "'>" + task.getIssue() + "</a> ";
                        }
                    }
                }
                bwLite.write("<tr" + (e.getValue().level <= 3 ? " class='danger'" : "") + ">" +
                        "<td>" + e.getValue().count + "</td>" +
                        "<td>" + e.getValue().streams + "</td>" +
                        "<td><a href='/panic/full_messages/" + calcMD5sum(e.getKey()) + ".txt'>example</a></td>" +
                        "<td>" + jiraTasksLink + "</td>" +
                        "<td>" + e.getKey() + "</td></tr>\n");
            }
            cnt++;
        }
        bw.write("</table></div></body></html>");
        bwLite.write("</table><a href='/panic/current-full.html'>Show me everything</a><br/><br/></div></body></html>");
        bw.close();
        bwLite.close();
        Files.move(Paths.get(liteFile + stamp), Paths.get(liteFile), ATOMIC_MOVE);
        Files.move(Paths.get(fullFile + stamp), Paths.get(fullFile), ATOMIC_MOVE);
    }

    private static String createJiraTask(Map.Entry<String, LogEntry> e) throws IOException {
        String shortMessage = e.getValue().substringForMatching;
        String fullMessage = "[PANIC] more than " + e.getValue().count + " " +
                (e.getValue().level <= 3 ? "errors" : "warnings") + " in 10 minutes\n\n{noformat}\n";
        File fullMessageFile = new File(options.getProperty("work_directory") + "/full_messages/" + calcMD5sum(e.getKey()) + ".txt");
        if (fullMessageFile.exists()) {
            BufferedReader br = new BufferedReader(new FileReader(fullMessageFile));
            String line = br.readLine();
            while (line != null) {
                fullMessage += line + "\n";
                line = br.readLine();
            }
            br.close();
        } else {
            fullMessage = shortMessage;
        }
        fullMessage += "\n{noformat}\n\nhttp://graylog.hh.ru/panic/current.html";

        JiraApiClient client = new JiraApiClient("http://jira.hh.ru/rest/api/2",
                options.getProperty("jira_user"),
                options.getProperty("jira_password"));
        return client.createIssue(shortMessage, fullMessage);
    }

    private static void reportMessages() throws IOException {
        if (lastTimeMessagesReported != null && new Date().getTime() - lastTimeMessagesReported.getTime() > Integer.parseInt(options.getProperty("reporting_interval"))) {
            LinkedList<Map.Entry<String, LogEntry>> combined = combineIntervals();
            for (Map.Entry<String, LogEntry> e : combined) {
                if ((e.getValue().level <= 3 && e.getValue().count >= Integer.parseInt(options.getProperty("error_reporting_threshold")))
                        || (e.getValue().level == 4 && e.getValue().count >= Integer.parseInt(options.getProperty("warning_reporting_threshold")))) {
                    double bestSimilarity = 0;
                    String mostSimilarTaskSummary = null;
                    for (String reported : jiraTasks.keySet()) {
                        double curSimilarity = StrikeAMatch.compareStrings(reported, e.getValue().substringForMatching);
                        if (curSimilarity > bestSimilarity) {
                            bestSimilarity = curSimilarity;
                            mostSimilarTaskSummary = reported;
                        }
                    }
                    if (bestSimilarity > Double.parseDouble(options.getProperty("merge_threshold"))) {
                        Boolean hasOpenTasks = false;
                        for (JiraTask task : jiraTasks.get(mostSimilarTaskSummary)) {
                            if (!task.getClosed()) {
                                hasOpenTasks = true;
                                if (task.getLastUpdate().getTime() / 1000 + Integer.parseInt(options.getProperty("report_repeat_interval")) < new Date().getTime() / 1000) {
                                    JiraApiClient client = new JiraApiClient("http://jira.hh.ru/rest/api/2",
                                            options.getProperty("jira_user"),
                                            options.getProperty("jira_password"));
                                    client.commentOnIssue(task.getIssue(), "Reminder: the problem still persists.");
                                    task.setLastUpdate(new Date());
                                }
                            }
                        }
                        if (!hasOpenTasks) {
                            if (lastTimeJiraTaskCreated == null || new Date().getTime() - lastTimeJiraTaskCreated.getTime() > Integer.parseInt(options.getProperty("jira_throttle_interval"))) {
                                String issueKey = createJiraTask(e);
                                if (issueKey != null) {
                                    JiraTask task = new JiraTask(e.getValue().substringForMatching, issueKey, false, new Date());
                                    jiraTasks.get(mostSimilarTaskSummary).add(task);
                                    lastReportedJiraTask = task;
                                    lastTimeJiraTaskCreated = new Date();
                                }
                                lastTimeMessagesReported = new Date();
                            }
                            else {
                                if (lastReportedJiraTask != null) {
                                    JiraApiClient client = new JiraApiClient("http://jira.hh.ru/rest/api/2",
                                            options.getProperty("jira_user"),
                                            options.getProperty("jira_password"));
                                    client.commentOnIssue(lastReportedJiraTask.getIssue(), "Maybe related problem: \n\n" + e.getKey());
                                    lastReportedJiraTask.setLastUpdate(new Date());
                                }
                            }
                        }
                    }
                    else {
                        if (lastTimeJiraTaskCreated == null || new Date().getTime() - lastTimeJiraTaskCreated.getTime() > Integer.parseInt(options.getProperty("jira_throttle_interval"))) {
                            String issueKey = createJiraTask(e);
                            if (issueKey != null) {
                                JiraTask task = new JiraTask(e.getValue().substringForMatching, issueKey, false, new Date());
                                CopyOnWriteArrayList<JiraTask> taskList = new CopyOnWriteArrayList<JiraTask>();
                                taskList.add(task);
                                jiraTasks.put(e.getValue().substringForMatching, taskList);
                                lastReportedJiraTask = task;
                                lastTimeJiraTaskCreated = new Date();
                            }
                            lastTimeMessagesReported = new Date();
                        }
                        else {
                            if (lastReportedJiraTask != null) {
                                JiraApiClient client = new JiraApiClient("http://jira.hh.ru/rest/api/2",
                                        options.getProperty("jira_user"),
                                        options.getProperty("jira_password"));
                                client.commentOnIssue(lastReportedJiraTask.getIssue(), "Maybe related problem: \n\n" + e.getKey());
                                lastReportedJiraTask.setLastUpdate(new Date());
                            }
                        }
                    }
                }
            }
        }
    }

    private static void writeSync(List<LogMessage> messages) throws Exception {
        options.load(new FileInputStream(OPTIONS_FILE));
        Long curTimestamp = new Date().getTime() / 1000;
        synchronized (NAME) {
            if (startedProcessing == null) {
                startedProcessing = new Date();
                lastTimeMessagesReported = new Date(new Date().getTime() - Integer.parseInt(options.getProperty("reporting_interval")));
                lastTimeJiraTaskCreated = new Date(new Date().getTime() - Integer.parseInt(options.getProperty("jira_throttle_interval")));
            } else if (curTimestamp - startedProcessing.getTime() / 1000 > 600) {
                messageCountProcessed = (messageCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) * 30;
                errWarnCountProcessed = (errWarnCountProcessed / (Math.max((new Date().getTime() - startedProcessing.getTime()) / 1000, 1))) * 30;
                startedProcessing = new Date((curTimestamp - 30) * 1000);
            }
            if (intervals.keySet().size() == 0) {
                restoreState();
            }
        }
        Long curInterval = curTimestamp - curTimestamp % Integer.parseInt(options.getProperty("interval_size"));
        int errWarnCount = 0;
        if (messages.size() > 0) {
            LinkedList<Map.Entry<String, LogEntry>> existing = combineIntervals();

            HashMap<String, LinkedList<Map.Entry<String, LogEntry>>> existingByFirstTwoLetters = new HashMap<String, LinkedList<Map.Entry<String, LogEntry>>>();
            for (Map.Entry<String, LogEntry> e : existing) {
                String theKey = e.getKey().substring(0, Math.min(2, e.getKey().length()));
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
                                    double curSimilarity = StrikeAMatch.compareStrings(escaped.substring(0, Math.min(escaped.length(), Integer.parseInt(options.getProperty("substring_length")))), e.getValue().substringForMatching);
                                    if (curSimilarity > bestSimilarity) {
                                        bestSimilarity = curSimilarity;
                                        mostSimilar = e.getKey();
                                    }
                                }
                            }
                        }
                    }
                    if (bestSimilarity > Double.parseDouble(options.getProperty("merge_threshold"))) {
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
                            new LogEntry(m.getLevel(), 1, getStreamsString(m.getStreams()), escaped.substring(0, Math.min(escaped.length(), Integer.parseInt(options.getProperty("substring_length")))));
                    intervals.get(curInterval).put(escaped, newEntry);
                    if (bestSimilarity <= Double.parseDouble(options.getProperty("merge_threshold"))) {
                        existingByFirstTwoLetters.get(escaped.substring(0, 2)).add(new AbstractMap.SimpleEntry<String, LogEntry>(escaped, newEntry));
                    }
                    String filePath = options.getProperty("work_directory") + "/full_messages/" + calcMD5sum(escaped) + ".txt";
                    if (new Random().nextInt(8) == 0) {
                        String stamp = new Date().getTime() + "" + new Random().nextLong();
                        BufferedWriter bw = new BufferedWriter(new FileWriter(filePath + stamp));
                        bw.write("short message example\n------------\n");
                        bw.write(m.getShortMessage());
                        bw.write("\n\nfull message example\n------------\n");
                        bw.write(m.getFullMessage());
                        bw.write("\n\nstreams\n-------\n");
                        for (Stream s : m.getStreams()) {
                            if (!(s.getTitle().equals("panic") && m.getStreams().size() > 1)) {
                                bw.write("http://graylog.hh.ru/streams/" + s.getId() + "-" + s.getTitle() + "/messages\n");
                            }
                        }
                        bw.close();
                        Files.move(Paths.get(filePath + stamp), Paths.get(filePath), ATOMIC_MOVE);
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
                if (key + Integer.parseInt(options.getProperty("interval_size")) * Integer.parseInt(options.getProperty("show_intervals")) < curTimestamp) {
                    keysToRemove.add(key);
                }
            }
            for (Long key : keysToRemove) {
                intervals.remove(key);
            }
            if (new Random().nextInt(3) == 0) {
                saveState();
            }
            if (new Random().nextInt(5) == 0) {
                reportMessages();
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
        // tests
    }
}
