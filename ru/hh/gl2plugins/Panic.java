package ru.hh.gl2plugins;

import org.graylog2.plugin.GraylogServer;
import org.graylog2.plugin.logmessage.LogMessage;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.outputs.OutputStreamConfiguration;
import org.graylog2.plugin.streams.Stream;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.*;

public class Panic implements MessageOutput {
  public static final String NAME = "Panic";

  @Override
  public void initialize(Map<String, String> config) throws MessageOutputConfigurationException {
  }

  private String escapeHtml(String s) {
    s = s.replace("<", "&lt;");
    s = s.replace(">", "&gt;");
    s = s.replace("&", "&amp;");
    s = s.replace("\"", "&quot;");
    return s;
  }

  @Override
  public void write(List<LogMessage> messages, OutputStreamConfiguration streamConfiguration, GraylogServer server) throws Exception {
    if (messages.size() > 0) {
      Date now = new Date();
      String filename = (now.getTime() - now.getTime() % 300000) + ".txt";
      BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/panic-www/" + filename, true));

      for (LogMessage logMessage : messages) {
        int streamCount = 0;
        for (Stream stream : logMessage.getStreams()) {
          if (stream != null && stream.getTitle() != null && !stream.getTitle().equals("panic")) {
            streamCount++;
          }
        }
        String[] streamArray = new String[streamCount];
        int i = 0;
        for (Stream stream : logMessage.getStreams()) {
          if (stream != null && stream.getTitle() != null && !stream.getTitle().equals("panic")) {
            streamArray[i++] = stream.getTitle();
          }
        }
        Arrays.sort(streamArray);

        String streams = "<span style='font-weight:bold;color:maroon;'>[";
        for (String stream : streamArray) {
          streams += escapeHtml(stream);
          if (i < streamArray.length - 1) {
            streams += ",";
          }
        }
        streams += "] </span>";
        bw.write(streams);
        bw.write(escapeHtml(logMessage.getFullMessage().replace('\n', ' ')));
        bw.newLine();
      }
      bw.close();
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
