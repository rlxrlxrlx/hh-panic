package ru.hh.gl2plugins.panic;

import java.util.Date;

public class JiraTask {
    private final String summary;
    private final String issue;
    private Boolean closed;
    private Date lastUpdate;

    public JiraTask(String summary, String issue, Boolean closed, Date lastUpdate) {
        this.summary = summary;
        this.issue = issue;
        this.closed = closed;
        this.lastUpdate = lastUpdate;
    }

    public Date getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(Date lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public Boolean getClosed() {
        return closed;
    }

    public void setClosed(Boolean closed) {
        this.closed = closed;
    }

    public String getSummary() {
        return summary;
    }

    public String getIssue() {
        return issue;
    }
}
