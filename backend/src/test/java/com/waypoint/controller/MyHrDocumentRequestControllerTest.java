package com.waypoint.controller;

import com.waypoint.dto.myhr.DocumentRequestRejectRequest;
import com.waypoint.dto.myhr.DocumentRequestResponse;
import com.waypoint.dto.myhr.OfferLetterEmailDraftResponse;
import com.waypoint.dto.myhr.OfferLetterEmailRequest;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.DocumentRequestService;
import com.waypoint.service.OfferLetterService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyHrDocumentRequestControllerTest {
    @Mock CurrentUserResolver resolver;
    @Mock DocumentRequestService service;
    @Mock OfferLetterService offerLetterService;
    @Mock HttpServletRequest servletRequest;
    MyHrDocumentRequestController controller;

    @BeforeEach void setUp() {
        controller = new MyHrDocumentRequestController(resolver, service, offerLetterService);
        lenient().when(resolver.resolveUserId(servletRequest)).thenReturn("actor-1");
    }

    @Test void rejectionUsesAuthenticatedActorAndValidatedRequestId() {
        DocumentRequestResponse response = response(DocumentRequestStatus.REJECTED);
        when(service.reject("actor-1", "request-1", "Unavailable")).thenReturn(response);
        assertThat(controller.reject(servletRequest, "request-1", new DocumentRequestRejectRequest("Unavailable")).getData())
                .isSameAs(response);
    }

    @Test void documentDownloadNeverAcceptsAStorageKeyAndForcesUnknownTypesToAttachment() {
        when(service.getDocumentContent("actor-1", "request-1", "document-1"))
                .thenReturn(new DocumentRequestService.RequestDocumentContent("unsafe\"name.exe",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1}));
        var response = controller.document(servletRequest, "request-1", "document-1", false);
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        verify(service).getDocumentContent("actor-1", "request-1", "document-1");
    }

    @Test void offerLetterEmailUsesAuthenticatedActorAndOnlyTheEditableFields() {
        OfferLetterEmailRequest input = new OfferLetterEmailRequest("employee@example.com", "Offer Letter", "Hi");

        var result = controller.emailOfferLetter(servletRequest, "request-1", input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Offer letter emailed");
        verify(offerLetterService).email("actor-1", "request-1", input);
    }

    @Test void offerLetterEmailDraftUsesAuthenticatedActor() {
        OfferLetterEmailDraftResponse draft = new OfferLetterEmailDraftResponse("employee@example.com", "Subject", "Hi", "Offer_Letter.pdf");
        when(offerLetterService.emailDraft("actor-1", "request-1", "Developer", "Jane")).thenReturn(draft);

        assertThat(controller.offerLetterEmailDraft(servletRequest, "request-1", "Developer", "Jane").getData()).isSameAs(draft);
    }

    private DocumentRequestResponse response(DocumentRequestStatus status) {
        return new DocumentRequestResponse("request-1", "from", "TD001", "from@example.com", "1 Main St", "Austin", "TX", "73301", "From", null,
                "to", "TD002", "To", null, null, "Letter", null, "Reason",
                "EMPLOYMENT", "NORMAL", "TO_MANAGER", null, null, null, status,
                null, null, null, null, "Unavailable", "actor-1", null, null, null, null);
    }
}
