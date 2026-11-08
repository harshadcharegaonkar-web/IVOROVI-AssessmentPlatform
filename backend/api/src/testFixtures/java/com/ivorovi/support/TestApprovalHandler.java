package com.ivorovi.support;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The framework's test request type ({@code test.change}): records callbacks; {@link #changed} simulates an edited subject. */
public class TestApprovalHandler implements ApprovalHandler {

    public static final String TYPE = "test.change";
    public final List<String> approved = new CopyOnWriteArrayList<>();
    public final List<String> rejected = new CopyOnWriteArrayList<>();
    public final List<String> closed = new CopyOnWriteArrayList<>();
    public volatile boolean changed;

    @Override
    public String type() { return TYPE; }

    @Override
    public String label() { return "Test change"; }

    @Override
    public void onApproved(ApprovalService.Request r) { approved.add(r.subjectId()); }

    @Override
    public void onRejected(ApprovalService.Request r) { rejected.add(r.subjectId()); }

    @Override
    public void onClosed(ApprovalService.Request r) { closed.add(r.subjectId()); }

    @Override
    public boolean unchanged(ApprovalService.Request r) { return !changed; }
}
