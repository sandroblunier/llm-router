package ch.ace.llmrouter.api;

import ch.ace.llmrouter.api.Dtos.ErrorResponse;
import ch.ace.llmrouter.api.Dtos.FeedbackRequest;
import ch.ace.llmrouter.api.Dtos.RouteRequest;
import ch.ace.llmrouter.api.Dtos.RouteResponse;
import ch.ace.llmrouter.routing.AttachmentExtractor;
import ch.ace.llmrouter.routing.RouterService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.ArrayList;
import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class RouteResource {

    @Inject
    RouterService router;

    @Inject
    AttachmentExtractor extractor;

    /** Steps 1-4: classify, log, return decision (or answer with autoConfirm). */
    @POST
    @Path("route")
    @Consumes(MediaType.APPLICATION_JSON)
    public RouteResponse route(RouteRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new BadRequestException("prompt is required");
        }
        return router.route(request.prompt(), List.of(),
                Boolean.TRUE.equals(request.autoConfirm()), Boolean.TRUE.equals(request.dryRun()));
    }

    /** Same as {@link #route(RouteRequest)} with attachments (PDF, DOCX, TXT) in the form field "files". */
    @POST
    @Path("route")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public RouteResponse routeMultipart(@RestForm String prompt,
                                        @RestForm Boolean autoConfirm,
                                        @RestForm Boolean dryRun,
                                        @RestForm("files") List<FileUpload> files) {
        List<RouterService.Attachment> attachments = new ArrayList<>();
        if (files != null) {
            for (FileUpload f : files) {
                attachments.add(new RouterService.Attachment(f.fileName(), extractor.extract(f.uploadedFile(), f.fileName())));
            }
        }
        if ((prompt == null || prompt.isBlank()) && attachments.isEmpty()) {
            throw new BadRequestException("prompt or files required");
        }
        return router.route(prompt, attachments, Boolean.TRUE.equals(autoConfirm), Boolean.TRUE.equals(dryRun));
    }

    /** Step 5: OK forwards the request and returns the answer, NOK aborts. */
    @POST
    @Path("requests/{id}/feedback")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response feedback(@PathParam("id") String id, FeedbackRequest request) {
        String fb = request == null || request.feedback() == null ? "" : request.feedback().trim().toUpperCase();
        if (!fb.equals("OK") && !fb.equals("NOK")) {
            throw new BadRequestException("feedback must be OK or NOK");
        }
        return router.feedback(id, fb.equals("OK"))
                .map(r -> Response.ok(r).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse(id, "No pending request with this id (unknown, expired or already answered)"))
                        .build());
    }
}
