package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.api.Dtos.ErrorResponse;
import ch.ace.llmrouter.evaluation.EvaluationModel.Event;
import ch.ace.llmrouter.evaluation.EvaluationModel.Export;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Gold-set evaluation in the backend. */
@Path("/evaluations")
@Produces(MediaType.APPLICATION_JSON)
public class EvaluationResource {

    @Inject
    EvaluationService service;

    @Inject
    ObjectMapper mapper;

    /** Upload an xlsx (form field "file") and start a job. 409 if a job is already running. */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response start(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw new BadRequestException("form field 'file' (xlsx) is required");
        }
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            var rows = GoldSetReader.read(in);
            return Response.accepted(service.start(file.fileName(), rows)).build();
        } catch (GoldSetReader.GoldSetException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(null, e.getMessage())).build();
        } catch (EvaluationService.JobRunningException e) {
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(null, e.getMessage())).build();
        } catch (IOException e) {
            throw new BadRequestException("cannot read upload: " + e.getMessage());
        }
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String id) {
        return service.view(id).map(v -> Response.ok(v).build()).orElseThrow(() -> notFound(id));
    }

    @POST
    @Path("{id}/cancel")
    public Response cancel(@PathParam("id") String id) {
        return service.cancel(id).map(v -> Response.ok(v).build()).orElseThrow(() -> notFound(id));
    }

    /** Live progress: replays rows so far, then one event per finished row, then "done". */
    @GET
    @Path("{id}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Event> events(@PathParam("id") String id) {
        EvaluationJob job = service.job(id).orElseThrow(() -> notFound(id));
        return Multi.createFrom().<Event>emitter(emitter -> job.subscribe(emitter));
    }

    @GET
    @Path("{id}/export")
    @Produces({"text/csv", MediaType.APPLICATION_JSON})
    public Response export(@PathParam("id") String id, @QueryParam("format") String format) throws JsonProcessingException {
        Export export = service.export(id).orElseThrow(() -> notFound(id));
        String base = "evaluation_" + export.meta().fileName().replaceAll("(?i)\\.xlsx$", "").replaceAll("[^A-Za-z0-9._-]", "_")
                + "_" + id.substring(0, Math.min(8, id.length()));
        if (format == null || format.equalsIgnoreCase("csv")) {
            return Response.ok(EvaluationMath.toCsv(export.rows()), "text/csv; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"" + base + ".csv\"").build();
        }
        if (format.equalsIgnoreCase("json")) {
            return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(export), MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=\"" + base + ".json\"").build();
        }
        throw new BadRequestException("format must be csv or json");
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("unknown evaluation " + id);
    }
}
