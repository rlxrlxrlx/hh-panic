package ru.hh.gl2plugins.panic;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.util.EntityUtils;
import org.codehaus.jettison.json.JSONObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JiraApiClient {
    private String jiraUrl;
    private String jiraUser;
    private String jiraPassword;

    public JiraApiClient(String jiraUrl, String jiraUser, String jiraPassword) {
        this.jiraUrl = jiraUrl;
        this.jiraUser = jiraUser;
        this.jiraPassword = jiraPassword;
    }

    private String postJsonRequest(String json, String subUrl) throws IOException {
        String result = "";

        HttpHost host = new HttpHost("jira.hh.ru", 80, "http");
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            client.getCredentialsProvider().setCredentials(new AuthScope(host.getHostName(), host.getPort()),
                    new UsernamePasswordCredentials(jiraUser, jiraPassword));
            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(host, basicAuth);
            BasicHttpContext context = new BasicHttpContext();
            context.setAttribute(ClientContext.AUTH_CACHE, authCache);

            HttpPost post = new HttpPost(jiraUrl + subUrl);
            post.addHeader("Content-Type", "application/json");
            StringEntity params = new StringEntity(json);
            post.setEntity(params);

            ResponseHandler<String> handler = new ResponseHandler<String>() {
                @Override
                public String handleResponse(HttpResponse response) throws IOException {
                    int status = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();
                    if (status >= 200 && status < 300) {
                        return entity != null ? EntityUtils.toString(entity) : null;
                    }
                    else {
                        throw new ClientProtocolException("Unexpected response status from Jira: " + status + " " +
                                (entity != null ? EntityUtils.toString(entity) : ""));
                    }
                }
            };
            result = client.execute(host, post, handler, context);
        }
        finally {
            client.getConnectionManager().shutdown();
        }
        return result;
    }

    private String getRequest(String subUrl) throws IOException {
        String result = "";

        HttpHost host = new HttpHost("jira.hh.ru", 80, "http");
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            client.getCredentialsProvider().setCredentials(new AuthScope(host.getHostName(), host.getPort()),
                    new UsernamePasswordCredentials(jiraUser, jiraPassword));
            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(host, basicAuth);
            BasicHttpContext context = new BasicHttpContext();
            context.setAttribute(ClientContext.AUTH_CACHE, authCache);

            HttpGet get = new HttpGet(jiraUrl + subUrl);

            ResponseHandler<String> handler = new ResponseHandler<String>() {
                @Override
                public String handleResponse(HttpResponse response) throws IOException {
                    int status = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();
                    if (status >= 200 && status < 300) {
                        return entity != null ? EntityUtils.toString(entity) : null;
                    }
                    else {
                        throw new ClientProtocolException("Unexpected response status from Jira: " + status + " " +
                                (entity != null ? EntityUtils.toString(entity) : ""));
                    }
                }
            };
            result = client.execute(host, get, handler, context);
        }
        finally {
            client.getConnectionManager().shutdown();
        }
        return result;
    }

    public String createIssue(String shortMessage, String fullMessage) throws IOException {
        String result = postJsonRequest("{\"fields\":{\"project\":{\"key\":\"PANIC\"},\"summary\":" + JSONObject.quote(shortMessage) + ",\"issuetype\":{\"id\":330},\"description\":" + JSONObject.quote(fullMessage) + "}}",
                "/issue").replace('\n', ' ');
        Pattern p = Pattern.compile(".*(PANIC-[0-9]+).*");
        Matcher m = p.matcher(result);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    public void commentOnIssue(String issueKey, String comment) throws IOException {
        postJsonRequest("{\"body\":" + JSONObject.quote(comment) + "}",
                "/issue/" + issueKey + "/comment");
    }

    private class StatusChecker implements Runnable {
        JiraTask task;
        private StatusChecker(JiraTask task) {
            this.task = task;
        }
        @Override
        public void run() {
            String result = null;
            try {
                result = getRequest("/issue/" + task.getIssue() + "?fields=status");
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            if (result != null) {
                Pattern p = Pattern.compile(".*Closed.*");
                Matcher m = p.matcher(result);
                task.setClosed(m.matches());
            }
        }
    }

    public void syncTaskStatus(JiraTask task) throws IOException {
        new Thread(new StatusChecker(task)).start();
    }
}
