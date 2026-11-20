package ch.ace.llmrouter.api;

import ch.ace.llmrouter.api.Dtos.ErrorResponse;
import ch.ace.llmrouter.api.Dtos.RequestLog;
import ch.ace.llmrouter.log.DecisionLog;
import ch.ace.llmrouter.log.LogEvent;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/logs")
@Produces(MediaType.APPLICATION_JSON)
public class LogResource {

    @Inject
    DecisionLog log;

    /** All events in file order. */
    @GET
    @Path("all")
    public List<LogEvent> all() {
        return log.all();
    }

    /** The last {@code count} requests, newest first, each with all its events. */
    @GET
    @Path("latest/{count}")
    public List<RequestLog> latest(@PathParam("count") int count) {
        if (count < 1) {
            throw new BadRequestException("count must be >= 1");
        }
        return log.latest(count).entrySet().stream()
                .map(e -> new RequestLog(e.getKey(), e.getValue()))
                .toList();
    }

    /** All events of one request. */
    @GET
    @Path("{id}")
    public Response byId(@PathParam("id") String id) {
        List<LogEvent> events = log.byId(id);
        if (events.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(id, "unknown id")).build();
        }
        return Response.ok(new RequestLog(id, events)).build();
    }
}
