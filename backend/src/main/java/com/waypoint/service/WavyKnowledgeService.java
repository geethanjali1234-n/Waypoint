package com.waypoint.service;

import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Versioned product guidance only: organization policies and deadlines are not inferred. */
@Service
public class WavyKnowledgeService {
    public record Article(String id, String title, String text) { }
    private static final List<Article> ARTICLES = List.of(
            new Article("timesheets-v1", "Waypoint timesheet workflow", "Use My Timesheets to enter hours for a selected period and attach supporting documents. Review the entries before submitting. Draft, Submitted, Approved and Rejected are distinct states. A rejected submission includes review feedback; revise it through the available resubmission workflow. Only the actual API can confirm recorded hours, status or deadlines."),
            new Article("approvals-v1", "Waypoint approval workflow", "HR and Company Admin users can open the team timesheet workspace to inspect visible submissions. Approval and rejection happen on the review page and are authorized by the server. Wavy cannot submit, approve or reject requests. Profile changes have a separate request queue. Ask which queue the user means when ambiguous."),
            new Article("profile-v1", "Waypoint profile and reporting assignments", "My Profile shows the signed-in person's profile and reporting-manager assignment. Request changes through Profile Change Requests. A reporting manager is not automatically an assigned HR contact. The assigned person's server-provided role must be HR to describe them as HR; otherwise explain that no separate HR assignment is available."),
            new Article("documents-v1", "Waypoint document requests", "Open Document Requests for letters, documents and request history. Available forms and actions depend on role and workflow stage. Review, signature, approval and delivery are different stages. Do not claim a file is signed or delivered without a current server result. If a format cannot be previewed, download the original using the document page."),
            new Article("payroll-v1", "Waypoint payroll readiness", "Payroll Readiness is available within Timesheets to authorized HR and Company Admin accounts. Its summary and cutoff date come from the authenticated payroll-readiness service. Counts apply to the current server-scoped period. Do not infer salary, pay amount, pay date, leave balance or organization policy from readiness counts."),
            new Article("help-v1", "Waypoint account and navigation help", "Wavy is a workplace assistant, not a human or an HR decision maker. It can explain workflows, offer navigation and check authorized read-only information. It must never request a password, OTP or API key. Session and access errors should direct the user to sign in again or contact an administrator. Unknown organization policies should be referred to HR rather than invented.")
    );

    public List<Article> search(String query) {
        Set<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(word -> word.length() > 3).collect(Collectors.toSet());
        return ARTICLES.stream().sorted(Comparator.comparingLong((Article article) -> words.stream()
                .filter(word -> (article.title() + " " + article.text()).toLowerCase(Locale.ROOT).contains(word)).count()).reversed())
                .limit(3).toList();
    }
}
