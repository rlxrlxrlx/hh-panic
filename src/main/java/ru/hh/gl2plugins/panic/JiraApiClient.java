package ru.hh.gl2plugins.panic;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope(host.getHostName(), host.getPort()),
                new UsernamePasswordCredentials(jiraUser, jiraPassword));
        CloseableHttpClient client = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();
        try {
            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(host, basicAuth);
            HttpClientContext context = HttpClientContext.create();
            context.setCredentialsProvider(credentialsProvider);
            context.setAuthCache(authCache);

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
            result = client.execute(post, handler, context);
        }
        finally {
            client.close();
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
}
