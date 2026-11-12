package eu.mikart.tava.transfer;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

final class TransferIssues extends AbstractList<TransferIssue> {
    private final List<TransferIssue> values = new ArrayList<>();
    private final int limit;

    TransferIssues(int limit) {
        this.limit = limit;
    }

    private static String shorten(String value) {
        return value == null || value.length() <= 4096 ? value : value.substring(0, 4096) + "…";
    }

    @Override
    public TransferIssue get(int index) {
        return values.get(index);
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean add(TransferIssue issue) {
        issue = new TransferIssue(
                shorten(issue.entity()), shorten(issue.field()), issue.severity(), shorten(issue.detail()));
        if (values.contains(issue)) return false;
        if (values.size() < limit - 1) return values.add(issue);
        if (issue.severity() == TransferIssue.Severity.ERROR) {
            if (values.size() == limit) {
                TransferIssue last = values.getLast();
                if (last.entity().equals("*") && last.severity() == TransferIssue.Severity.WARNING)
                    values.set(limit - 2, last);
                values.set(limit - 1, issue);
            } else values.add(issue);
        } else if (values.size() < limit) {
            values.add(new TransferIssue(
                    "*",
                    null,
                    TransferIssue.Severity.WARNING,
                    "Additional transfer diagnostics omitted; diagnostic limit reached"));
        }
        return true;
    }
}
