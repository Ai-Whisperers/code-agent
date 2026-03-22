package com.eneve.agent.attachment;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/attachments")
@Tag(name = "Chat Attachments", description = "File attachment management for chat conversations")
@Produces(MediaType.APPLICATION_JSON)
public class AttachmentsResource {

    private static final Logger LOG = Logger.getLogger(AttachmentsResource.class);

    @Inject
    AttachmentService attachmentService;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        operationId = "uploadAttachment",
        summary = "Upload a file attachment",
        description = "Upload a file to be attached to a chat conversation. Files are stored in S3 with metadata tracked in database."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "File uploaded successfully"),
        @APIResponse(responseCode = "400", description = "Invalid file or request"),
        @APIResponse(responseCode = "413", description = "File too large"),
        @APIResponse(responseCode = "500", description = "Upload failed")
    })
    public Response uploadAttachment(FileUploadForm form) {
        try {
            LOG.infof("Processing attachment upload for conversation: %s", form.conversationId);

            // Validate required fields
            if (form.conversationId == null || form.conversationId.isBlank()) {
                LOG.warnf("Missing conversationId in upload request");
                return Response.status(400)
                    .entity(Map.of("error", "conversationId is required"))
                    .build();
            }

            if (form.file == null) {
                LOG.warnf("Missing file in upload request");
                return Response.status(400)
                    .entity(Map.of("error", "No file provided"))
                    .build();
            }

            if (form.filename == null || form.filename.isBlank()) {
                LOG.warnf("Missing filename in upload request");
                return Response.status(400)
                    .entity(Map.of("error", "filename is required"))
                    .build();
            }

            if (form.contentType == null || form.contentType.isBlank()) {
                LOG.warnf("Missing contentType in upload request");
                return Response.status(400)
                    .entity(Map.of("error", "contentType is required"))
                    .build();
            }

            // Upload the attachment
            ChatAttachment attachment = attachmentService.uploadAttachment(
                form.conversationId,
                form.messageId, // can be null
                form.filename,
                form.contentType,
                form.fileSize != null ? form.fileSize : 0,
                form.file
            );

            LOG.infof("Uploaded attachment: %s (%s)", attachment.attachmentId(), attachment.filename());

            return Response.ok(attachment).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid attachment upload request: %s", e.getMessage());
            return Response.status(400)
                .entity(Map.of("error", e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to upload attachment: %s", e.getMessage());
            return Response.status(500)
                .entity(Map.of("error", "Upload failed: " + e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
    }

    @GET
    @Path("/{attachmentId}")
    @Operation(
        operationId = "getAttachment",
        summary = "Get attachment metadata",
        description = "Retrieve metadata for a specific attachment"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Attachment found"),
        @APIResponse(responseCode = "404", description = "Attachment not found")
    })
    public Response getAttachment(
            @Parameter(description = "Attachment ID", required = true)
            @PathParam("attachmentId") String attachmentId) {
        
        Optional<ChatAttachment> attachment = attachmentService.getAttachment(attachmentId);
        if (attachment.isEmpty()) {
            return Response.status(404)
                .entity(Map.of("error", "Attachment not found"))
                .build();
        }

        return Response.ok(attachment.get()).build();
    }

    @GET
    @Path("/{attachmentId}/download")
    @Operation(
        operationId = "downloadAttachment",
        summary = "Get download URL for attachment",
        description = "Generate a presigned URL for downloading the attachment file"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Download URL generated"),
        @APIResponse(responseCode = "404", description = "Attachment not found")
    })
    public Response getDownloadUrl(
            @Parameter(description = "Attachment ID", required = true)
            @PathParam("attachmentId") String attachmentId,
            @Parameter(description = "URL expiration in minutes", example = "15")
            @QueryParam("expirationMinutes") Integer expirationMinutes) {
        
        try {
            int expiration = expirationMinutes != null ? expirationMinutes : 15;
            String downloadUrl = attachmentService.generatePresignedDownloadUrl(attachmentId, expiration);
            
            return Response.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expirationMinutes", expiration
            )).build();

        } catch (IllegalArgumentException e) {
            return Response.status(404)
                .entity(Map.of("error", "Attachment not found"))
                .build();
        } catch (Exception e) {
            LOG.errorf("Failed to generate download URL for %s: %s", attachmentId, e.getMessage());
            return Response.status(500)
                .entity(Map.of("error", "Failed to generate download URL"))
                .build();
        }
    }

    @GET
    @Path("/conversation/{conversationId}")
    @Operation(
        operationId = "getConversationAttachments",
        summary = "Get attachments for conversation",
        description = "Retrieve all attachments for a specific conversation"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Attachments retrieved")
    })
    public Response getConversationAttachments(
            @Parameter(description = "Conversation ID", required = true)
            @PathParam("conversationId") String conversationId) {
        
        List<ChatAttachment> attachments = attachmentService.getAttachmentsByConversation(conversationId);
        return Response.ok(attachments).build();
    }

    @DELETE
    @Path("/{attachmentId}")
    @Operation(
        operationId = "deleteAttachment",
        summary = "Delete an attachment",
        description = "Remove an attachment file and its metadata"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Attachment deleted"),
        @APIResponse(responseCode = "404", description = "Attachment not found"),
        @APIResponse(responseCode = "500", description = "Delete failed")
    })
    public Response deleteAttachment(
            @Parameter(description = "Attachment ID", required = true)
            @PathParam("attachmentId") String attachmentId) {
        
        try {
            boolean deleted = attachmentService.deleteAttachment(attachmentId);
            if (!deleted) {
                return Response.status(404)
                    .entity(Map.of("error", "Attachment not found"))
                    .build();
            }

            return Response.ok(Map.of("deleted", true)).build();

        } catch (Exception e) {
            LOG.errorf("Failed to delete attachment %s: %s", attachmentId, e.getMessage());
            return Response.status(500)
                .entity(Map.of("error", "Delete failed"))
                .build();
        }
    }

    /**
     * Form data structure for file uploads
     */
    public static class FileUploadForm {
        
        @RestForm("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream file;

        @RestForm("conversationId")
        @PartType(MediaType.TEXT_PLAIN)
        public String conversationId;

        @RestForm("messageId")
        @PartType(MediaType.TEXT_PLAIN)
        public Long messageId; // optional

        @RestForm("filename")
        @PartType(MediaType.TEXT_PLAIN)
        public String filename;

        @RestForm("contentType")
        @PartType(MediaType.TEXT_PLAIN)
        public String contentType;

        @RestForm("fileSize")
        @PartType(MediaType.TEXT_PLAIN)
        public Long fileSize; // optional
    }
}
