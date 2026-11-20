package ch.ace.llmrouter.api;

import ch.ace.llmrouter.api.Dtos.ErrorResponse;
import ch.ace.llmrouter.routing.AttachmentExtractor;
import ch.ace.llmrouter.routing.RouterService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

public class ExceptionMappers {

    /** Target LLM failed: 502 with the request id. No fallback to another target. */
    @ServerExceptionMapper
    public Response llmFailed(RouterService.LlmCallFailedException e) {
        return json(Response.Status.BAD_GATEWAY, new ErrorResponse(e.id(), e.getMessage()));
    }

    @ServerExceptionMapper
    public Response attachment(AttachmentExtractor.UnsupportedAttachmentException e) {
        return json(Response.Status.UNSUPPORTED_MEDIA_TYPE, new ErrorResponse(null, e.getMessage()));
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e) {
        return Response.status(e.getResponse().getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(null, e.getMessage()))
                .build();
    }

    private static Response json(Response.Status status, Object entity) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(entity).build();
    }
}
