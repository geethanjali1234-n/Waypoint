package com.waypoint.service;

import com.waypoint.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads the predefined PDFs attached to every Offer Letter email from the
 * server-side classpath ({@code classpath:/offer-letter/}). Never served over
 * HTTP. If any file is missing or unreadable the whole send fails.
 */
@Slf4j
@Service
public class OfferLetterStaticAttachmentService {

    static final String UNAVAILABLE = "A required Offer Letter attachment could not be loaded, so the email was not sent.";

    private record Spec(String resourceName, String emailFileName) {}

    private static final List<Spec> SPECS = List.of(
            new Spec("Direct Deposit_Authorization.pdf", "Direct Deposit Authorization.pdf"),
            new Spec("I-9.pdf", "I-9.pdf"),
            new Spec("W-4 Form.pdf", "W-4 Form.pdf"));

    public List<EmailAttachment> load() {
        return SPECS.stream().map(this::read).toList();
    }

    private EmailAttachment read(Spec spec) {
        ClassPathResource resource = new ClassPathResource("offer-letter/" + spec.resourceName());
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII)))
                throw new IOException("not a PDF");
            return new EmailAttachment(spec.emailFileName(), bytes, "application/pdf");
        } catch (IOException failure) {
            log.error("Offer letter attachment unavailable [resource={}, reason={}]", spec.resourceName(), failure.getMessage());
            throw new BusinessException(UNAVAILABLE);
        }
    }
}
